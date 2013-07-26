/*
 *      Copyright (C) 2012 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Uninterruptibles;

import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverInternalError;

import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.transport.*;
import org.apache.cassandra.transport.messages.*;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// For LoggingHandler
//import org.jboss.netty.handler.logging.LoggingHandler;
//import org.jboss.netty.logging.InternalLogLevel;

/**
 * A connection to a Cassandra Node.
 */
class Connection extends org.apache.cassandra.transport.Connection
{
    public static final int MAX_STREAM_PER_CONNECTION = 128;

    private static final Logger logger = LoggerFactory.getLogger(Connection.class);

    // TODO: that doesn't belong here
    private static final String CQL_VERSION = "3.0.0";

    private static final org.apache.cassandra.transport.Connection.Tracker EMPTY_TRACKER = new org.apache.cassandra.transport.Connection.Tracker() {
        @Override
        public void addConnection(Channel ch, org.apache.cassandra.transport.Connection connection) {}

        @Override
        public void closeAll() {}
    };

    public final InetAddress address;
    private final String name;

    private final Channel channel;
    private final Factory factory;
    private final Dispatcher dispatcher = new Dispatcher();

    // Used by connnection pooling to count how many requests are "in flight" on that connection.
    public final AtomicInteger inFlight = new AtomicInteger(0);

    private final AtomicInteger writer = new AtomicInteger(0);
    private volatile String keyspace;

    private volatile boolean isDefunct;
    private volatile ConnectionException exception;

    private final AtomicReference<ConnectionShutdownFuture> shutdownFuture = new AtomicReference<ConnectionShutdownFuture>();

    /**
     * Create a new connection to a Cassandra node.
     *
     * The connection is open and initialized by the constructor.
     *
     * @throws ConnectionException if the connection attempts fails or is
     * refused by the server.
     */
    private Connection(String name, InetAddress address, Factory factory) throws ConnectionException, InterruptedException {
        super(EMPTY_TRACKER);

        this.address = address;
        this.factory = factory;
        this.name = name;

        ClientBootstrap bootstrap = factory.newBootstrap();
        if (factory.configuration.getProtocolOptions().sslOptions == null)
            bootstrap.setPipelineFactory(new PipelineFactory(this));
        else
            bootstrap.setPipelineFactory(new SecurePipelineFactory(this, factory.configuration.getProtocolOptions().sslOptions));

        ChannelFuture future = bootstrap.connect(new InetSocketAddress(address, factory.getPort()));

        writer.incrementAndGet();
        try {
            // Wait until the connection attempt succeeds or fails.
            this.channel = future.awaitUninterruptibly().getChannel();
            this.factory.allChannels.add(this.channel);
            if (!future.isSuccess())
            {
                if (logger.isDebugEnabled())
                    logger.debug(String.format("[%s] Error connecting to %s%s", name, address, extractMessage(future.getCause())));
                throw new TransportException(address, "Cannot connect", future.getCause());
            }
        } finally {
            writer.decrementAndGet();
        }

        logger.trace("[{}] Connection opened successfully", name);
        initializeTransport();
        logger.trace("[{}] Transport initialized and ready", name);
    }

    private static String extractMessage(Throwable t) {
        String msg = t == null || t.getMessage() == null || t.getMessage().isEmpty()
                   ? t.toString()
                   : t.getMessage();
        return " (" + msg + ")";
    }

    private void initializeTransport() throws ConnectionException, InterruptedException {

        // TODO: we will need to get fancy about handling protocol version at
        // some point, but keep it simple for now.
        ImmutableMap.Builder<String, String> options = new ImmutableMap.Builder<String, String>();
        options.put(StartupMessage.CQL_VERSION, CQL_VERSION);
        ProtocolOptions.Compression compression = factory.configuration.getProtocolOptions().getCompression();
        if (compression != ProtocolOptions.Compression.NONE)
        {
            options.put(StartupMessage.COMPRESSION, compression.toString());
            setCompressor(compression.compressor());
        }
        StartupMessage startup = new StartupMessage(options.build());
        try {
            Message.Response response = write(startup).get();
            switch (response.type) {
                case READY:
                    break;
                case ERROR:
                    throw defunct(new TransportException(address, String.format("Error initializing connection: %s", ((ErrorMessage)response).error.getMessage())));
                case AUTHENTICATE:
                    CredentialsMessage creds = new CredentialsMessage();
                    creds.credentials.putAll(factory.authProvider.getAuthInfo(address));
                    Message.Response authResponse = write(creds).get();
                    switch (authResponse.type) {
                        case READY:
                            break;
                        case ERROR:
                            throw new AuthenticationException(address, (((ErrorMessage)authResponse).error).getMessage());
                        default:
                            throw defunct(new TransportException(address, String.format("Unexpected %s response message from server to a CREDENTIALS message", authResponse.type)));
                    }
                    break;
                default:
                    throw defunct(new TransportException(address, String.format("Unexpected %s response message from server to a STARTUP message", response.type)));
            }
        } catch (BusyConnectionException e) {
            throw new DriverInternalError("Newly created connection should not be busy");
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, String.format("Unexpected error during transport initialization (%s)", e.getCause()), e.getCause()));
        }
    }

    public boolean isDefunct() {
        return isDefunct;
    }

    public ConnectionException lastException() {
        return exception;
    }

    ConnectionException defunct(ConnectionException e) {
        if (logger.isDebugEnabled())
            logger.debug("Defuncting connection to " + address, e);
        exception = e;
        isDefunct = true;
        dispatcher.errorOutAllHandler(e);
        close();
        return e;
    }

    public String keyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) throws ConnectionException {
        if (keyspace == null)
            return;

        if (this.keyspace != null && this.keyspace.equals(keyspace))
            return;

        try {
            logger.trace("[{}] Setting keyspace {}", name, keyspace);
            // Note: we quote the keyspace below, because the name is the one coming from Cassandra, so it's in the right case already
            long timeout = factory.getConnectTimeoutMillis();
            Future future = write(new QueryMessage("USE \"" + keyspace + "\"", ConsistencyLevel.DEFAULT_CASSANDRA_CL));
            Message.Response response = Uninterruptibles.getUninterruptibly(future, timeout, TimeUnit.MILLISECONDS);
            switch (response.type) {
                case RESULT:
                    this.keyspace = keyspace;
                    break;
                default:
                    // The code set the keyspace only when a successful 'use'
                    // has been perform, so there shouldn't be any error here.
                    // It can happen however that the node we're connecting to
                    // is not up on the schema yet. In that case, defuncting
                    // the connection is not a bad choice.
                    defunct(new ConnectionException(address, String.format("Problem while setting keyspace, got %s as response", response)));
                    break;
            }
        } catch (ConnectionException e) {
            throw defunct(e);
        } catch (TimeoutException e) {
            logger.warn(String.format("Timeout while setting keyspace on connection to %s. This should not happen but is not critical (it will retried)", address));
        } catch (BusyConnectionException e) {
            logger.warn(String.format("Tried to set the keyspace on busy connection to %s. This should not happen but is not critical (it will retried)", address));
        } catch (ExecutionException e) {
            throw defunct(new ConnectionException(address, "Error while setting keyspace", e));
        }
    }

    /**
     * Write a request on this connection.
     *
     * @param request the request to send
     * @return a future on the server response
     *
     * @throws ConnectionException if the connection is closed
     * @throws TransportException if an I/O error while sending the request
     */
    public Future write(Message.Request request) throws ConnectionException, BusyConnectionException {
        Future future = new Future(request);
        write(future);
        return future;
    }

    public void write(ResponseCallback callback) throws ConnectionException, BusyConnectionException {

        Message.Request request = callback.request();

        request.attach(this);

        ResponseHandler handler = new ResponseHandler(dispatcher, callback);
        dispatcher.add(handler);
        request.setStreamId(handler.streamId);

        /*
         * We check for close/defunct *after* having set the handler because closing/defuncting
         * will set their flag and then error out handler if need. So, by doing the check after
         * having set the handler, we guarantee that even if we race with defunct/close, we may
         * never leave a handler that won't get an answer or be errored out.
         */
        if (isDefunct) {
            dispatcher.removeHandler(handler.streamId);
            throw new ConnectionException(address, "Write attempt on defunct connection");
        }

        if (isClosed()) {
            dispatcher.removeHandler(handler.streamId);
            throw new ConnectionException(address, "Connection has been closed");
        }

        logger.trace("[{}] writing request {}", name, request);
        writer.incrementAndGet();
        channel.write(request).addListener(writeHandler(request, handler));
    }

    private ChannelFutureListener writeHandler(final Message.Request request, final ResponseHandler handler) {
        return new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture writeFuture) {

                writer.decrementAndGet();

                if (!writeFuture.isSuccess()) {

                    logger.debug("[{}] Error writing request {}", name, request);
                    // Remove this handler from the dispatcher so it don't get notified of the error
                    // twice (we will fail that method already)
                    dispatcher.removeHandler(handler.streamId);

                    ConnectionException ce;
                    if (writeFuture.getCause() instanceof java.nio.channels.ClosedChannelException) {
                        ce = new TransportException(address, "Error writing: Closed channel");
                    } else {
                        ce = new TransportException(address, "Error writing", writeFuture.getCause());
                    }
                    handler.callback.onException(Connection.this, defunct(ce));
                } else {
                    logger.trace("[{}] request sent successfully", name);
                }
            }
        };
    }

    public boolean isClosed() {
        return shutdownFuture.get() != null;
    }

    public ShutdownFuture close() {

        ConnectionShutdownFuture future = new ConnectionShutdownFuture();
        if (!shutdownFuture.compareAndSet(null, future))
        {
            // Shutdown had already been called, return the existing future
            return shutdownFuture.get();
        }

        logger.trace("[{}] closing connection", name);

        // New writes will be refused now that the future is setup.
        // We must now wait on the last ongoing queries to return, which will in turn trigger
        // the closing of the channel. However, if there no ongoing query, signal now.
        if (dispatcher.pending.isEmpty())
            future.force();
        return future;
    }

    @Override
    public String toString() {
        return String.format("Connection[%s, inFlight=%d, closed=%b]", name, inFlight.get(), isClosed());
    }

    // Cruft needed because we reuse server side classes, but we don't care about it
    public void validateNewMessage(Message.Type type) {};
    public void applyStateTransition(Message.Type requestType, Message.Type responseType) {};
    public ClientState clientState() { return null; };

    public static class Factory {

        private final ExecutorService bossExecutor = Executors.newCachedThreadPool();
        private final ExecutorService workerExecutor = Executors.newCachedThreadPool();

        private final ChannelFactory channelFactory = new NioClientSocketChannelFactory(bossExecutor, workerExecutor);
        private final ChannelGroup allChannels = new DefaultChannelGroup();

        private final ConcurrentMap<Host, AtomicInteger> idGenerators = new ConcurrentHashMap<Host, AtomicInteger>();
        public final DefaultResponseHandler defaultHandler;
        public final Configuration configuration;

        public final AuthInfoProvider authProvider;
        private volatile boolean isShutdown;

        public Factory(Cluster.Manager manager, AuthInfoProvider authProvider) {
            this(manager, manager.configuration, authProvider);
        }

        private Factory(DefaultResponseHandler defaultHandler, Configuration configuration, AuthInfoProvider authProvider) {
            this.defaultHandler = defaultHandler;
            this.configuration = configuration;
            this.authProvider = authProvider;
        }

        public int getPort() {
            return configuration.getProtocolOptions().getPort();
        }

        /**
         * Opens a new connection to the node this factory points to.
         *
         * @return the newly created (and initialized) connection.
         *
         * @throws ConnectionException if connection attempt fails.
         */
        public Connection open(Host host) throws ConnectionException, InterruptedException {
            InetAddress address = host.getAddress();

            if (isShutdown)
                throw new ConnectionException(address, "Connection factory is shut down");

            String name = address.toString() + "-" + getIdGenerator(host).getAndIncrement();
            return new Connection(name, address, this);
        }

        private AtomicInteger getIdGenerator(Host host) {
            AtomicInteger g = idGenerators.get(host);
            if (g == null) {
                g = new AtomicInteger(1);
                AtomicInteger old = idGenerators.putIfAbsent(host, g);
                if (old != null)
                    g = old;
            }
            return g;
        }

        public long getConnectTimeoutMillis() {
            return configuration.getSocketOptions().getConnectTimeoutMillis();
        }

        private ClientBootstrap newBootstrap() {
            ClientBootstrap b = new ClientBootstrap(channelFactory);

            SocketOptions options = configuration.getSocketOptions();

            b.setOption("connectTimeoutMillis", options.getConnectTimeoutMillis());
            Boolean keepAlive = options.getKeepAlive();
            if (keepAlive != null)
                b.setOption("keepAlive", keepAlive);
            Boolean reuseAddress = options.getReuseAddress();
            if (reuseAddress != null)
                b.setOption("reuseAddress", reuseAddress);
            Integer soLinger = options.getSoLinger();
            if (soLinger != null)
                b.setOption("soLinger", soLinger);
            Boolean tcpNoDelay = options.getTcpNoDelay();
            if (tcpNoDelay != null)
                b.setOption("tcpNoDelay", tcpNoDelay);
            Integer receiveBufferSize = options.getReceiveBufferSize();
            if (receiveBufferSize != null)
                b.setOption("receiveBufferSize", receiveBufferSize);
            Integer sendBufferSize = options.getSendBufferSize();
            if (sendBufferSize != null)
                b.setOption("sendBufferSize", sendBufferSize);

            return b;
        }

        public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
            // Make sure we skip creating connection from now on.
            isShutdown = true;

            long start = System.nanoTime();
            ChannelGroupFuture future = allChannels.close();

            channelFactory.releaseExternalResources();

            return future.await(timeout, unit)
                && bossExecutor.awaitTermination(timeout - Cluster.timeSince(start, unit), unit)
                && workerExecutor.awaitTermination(timeout - Cluster.timeSince(start, unit), unit);
        }
    }

    private class Dispatcher extends SimpleChannelUpstreamHandler {

        public final StreamIdGenerator streamIdHandler = new StreamIdGenerator();
        private final ConcurrentMap<Integer, ResponseHandler> pending = new ConcurrentHashMap<Integer, ResponseHandler>();

        public void add(ResponseHandler handler) {
            ResponseHandler old = pending.put(handler.streamId, handler);
            assert old == null;
        }

        public void removeHandler(int streamId) {
            pending.remove(streamId);
            streamIdHandler.release(streamId);
        }

        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            if (!(e.getMessage() instanceof Message.Response)) {
                logger.error("[{}] Received unexpected message: {}", name, e.getMessage());
                defunct(new TransportException(address, "Unexpected message received: " + e.getMessage()));
            } else {
                Message.Response response = (Message.Response)e.getMessage();
                int streamId = response.getStreamId();

                logger.trace("[{}] received: {}", name, e.getMessage());

                if (streamId < 0) {
                    factory.defaultHandler.handle(response);
                    return;
                }

                ResponseHandler handler = pending.remove(streamId);
                streamIdHandler.release(streamId);
                if (handler == null) {
                    if (!isDefunct()) {
                        /*
                         * In general, a defunct connection is one that is broken, in which case we won't receive a message anymore.
                         * However, we could be defunct because of a internal error (anecdotally, this will happens if you have
                         * an old version of the google-collections (the guava precursor) in the classpath, which happened to at least
                         * 2 users already). In that case, the initial defunct already has registered what the problem was and we can ignore this.
                         *
                         * But if the connection is not defunct, this is a bug, either of us or cassandra. So log it, but I'm not sure it's worth
                         * breaking the connection for that.
                         */
                        logger.error("[{}] No handler set for stream {} (this is a bug, either of this driver or of Cassandra, you should report it). Received message is {}", 
                                     name, streamId, response);
                    }
                    return;
                }
                handler.callback.onSet(Connection.this, response);

                // If we happen to be shutdown and we're the last outstand request, we need to signal the shutdown future
                // (note: this is racy as the signaling can be called more than once, but that's not a problem)
                if (isClosed() && pending.isEmpty())
                    shutdownFuture.get().force();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
            if (logger.isTraceEnabled())
                logger.trace(String.format("[%s] connection error", name), e.getCause());

            // Ignore exception while writing, this will be handled by write() directly
            if (writer.get() > 0)
                return;

            defunct(new TransportException(address, String.format("Unexpected exception triggered (%s)", e.getCause()), e.getCause()));
        }

        public void errorOutAllHandler(ConnectionException ce) {
            Iterator<ResponseHandler> iter = pending.values().iterator();
            while (iter.hasNext())
            {
                iter.next().callback.onException(Connection.this, ce);
                iter.remove();
            }
        }

        @Override
        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e)
        {
            // If we've closed the channel server side then we don't really want to defunct the connection, but
            // if there is remaining thread waiting on us, we still want to wake them up
            if (isClosed())
                errorOutAllHandler(new TransportException(address, "Channel has been closed"));
            else
                defunct(new TransportException(address, "Channel has been closed"));
        }
    }

    private class ConnectionShutdownFuture extends ShutdownFuture {

        @Override
        public ConnectionShutdownFuture force() {
            // Note: we must not call releaseExternalResources on the bootstrap, because this shutdown the executors, which are shared

            // This method can be thrown during Connection ctor, at which point channel is not yet set. This is ok.
            if (channel == null) {
                set(null);
                return this;
            }

            // We're going to close this channel. If anyone is waiting on that connection, we should defunct it otherwise it'll wait
            // forever. In general this won't happen since we get there only when all ongoing query are done, but this can happen
            // if the shutdown is forced. This is a no-op if there is no handler set anymore.
            dispatcher.errorOutAllHandler(new TransportException(address, "Connection has been closed"));

            ChannelFuture future = channel.close();
            future.addListener(new ChannelFutureListener() {
                public void operationComplete(ChannelFuture future) {
                    if (future.getCause() != null)
                        ConnectionShutdownFuture.this.setException(future.getCause());
                    else
                        ConnectionShutdownFuture.this.set(null);
                }
            });
            return this;
        }
    }

    static class Future extends SimpleFuture<Message.Response> implements RequestHandler.Callback {

        private final Message.Request request;
        private volatile InetAddress address;

        public Future(Message.Request request) {
            this.request = request;
        }

        @Override
        public Message.Request request() {
            return request;
        }

        @Override
        public void onSet(Connection connection, Message.Response response, ExecutionInfo info, Query query) {
            onSet(connection, response);
        }

        @Override
        public void onSet(Connection connection, Message.Response response) {
            this.address = connection.address;
            super.set(response);
        }

        @Override
        public void onException(Connection connection, Exception exception) {
            super.setException(exception);
        }

        public InetAddress getAddress() {
            return address;
        }
    }

    interface ResponseCallback {
        public Message.Request request();
        public void onSet(Connection connection, Message.Response response);
        public void onException(Connection connection, Exception exception);
    }

    private static class ResponseHandler {

        public final int streamId;
        public final ResponseCallback callback;

        public ResponseHandler(Dispatcher dispatcher, ResponseCallback callback) throws BusyConnectionException {
            this.streamId = dispatcher.streamIdHandler.next();
            this.callback = callback;
        }
    }

    public interface DefaultResponseHandler {
        public void handle(Message.Response response);
    }

    private static class PipelineFactory implements ChannelPipelineFactory {
        // Stateless handlers
        private static final Message.ProtocolDecoder messageDecoder = new Message.ProtocolDecoder();
        private static final Message.ProtocolEncoder messageEncoder = new Message.ProtocolEncoder();
        private static final Frame.Decompressor frameDecompressor = new Frame.Decompressor();
        private static final Frame.Compressor frameCompressor = new Frame.Compressor();
        private static final Frame.Encoder frameEncoder = new Frame.Encoder();

        // One more fallout of using server side classes; not a big deal
        private static final org.apache.cassandra.transport.Connection.Tracker tracker;
        static {
            tracker = new org.apache.cassandra.transport.Connection.Tracker() {
                @Override
                public void addConnection(Channel ch, org.apache.cassandra.transport.Connection connection) {}

                @Override
                public void closeAll() {}
            };
        }

        private final Connection connection;
        private final org.apache.cassandra.transport.Connection.Factory cfactory;

        public PipelineFactory(final Connection connection) {
            this.connection = connection;
            this.cfactory = new org.apache.cassandra.transport.Connection.Factory() {
                @Override
                public Connection newConnection(org.apache.cassandra.transport.Connection.Tracker tracker) {
                    return connection;
                }
            };
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = Channels.pipeline();

            //pipeline.addLast("debug", new LoggingHandler(InternalLogLevel.INFO));

            pipeline.addLast("frameDecoder", new Frame.Decoder(tracker, cfactory));
            pipeline.addLast("frameEncoder", frameEncoder);

            pipeline.addLast("frameDecompressor", frameDecompressor);
            pipeline.addLast("frameCompressor", frameCompressor);

            pipeline.addLast("messageDecoder", messageDecoder);
            pipeline.addLast("messageEncoder", messageEncoder);

            pipeline.addLast("dispatcher", connection.dispatcher);

            return pipeline;
        }
    }

    private static class SecurePipelineFactory extends PipelineFactory {

        private final SSLOptions options;

        public SecurePipelineFactory(final Connection connection, SSLOptions options) {
            super(connection);
            this.options = options;
        }

        public ChannelPipeline getPipeline() throws Exception {
            SSLEngine engine = options.context.createSSLEngine();
            engine.setUseClientMode(true);
            engine.setEnabledCipherSuites(options.cipherSuites);
            ChannelPipeline pipeline = super.getPipeline();
            pipeline.addFirst("ssl", new SslHandler(engine));
            return pipeline;
        }
    }
}

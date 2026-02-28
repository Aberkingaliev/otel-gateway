package com.acme.finops.gateway.transport.api;

/**
 * SPI for pluggable network transports (gRPC, HTTP/protobuf, etc.).
 *
 * <p>Lifecycle: call {@link #setInboundHandler} before {@link #start()}.
 * {@link #close()} delegates to {@link #stop()}. Implementations must
 * tolerate multiple stop/close calls without error.
 */
public interface TransportAdapter extends AutoCloseable {
    /** Returns the wire protocol this adapter speaks. */
    ProtocolKind protocol();

    /** Starts accepting inbound connections. Must not be called before setting the handler. */
    void start() throws Exception;

    /** Gracefully stops the transport, draining in-flight requests. */
    void stop() throws Exception;

    /** Sets the handler that receives decoded inbound packets. Must be called before start. */
    void setInboundHandler(InboundHandler handler);

    /** Callback for inbound packets delivered by the transport. */
    interface InboundHandler {
        /** Handles a single inbound packet and returns the transport-level response. */
        TransportResponse onPacket(InboundPacket packet);
    }

    @Override
    default void close() throws Exception { stop(); }
}

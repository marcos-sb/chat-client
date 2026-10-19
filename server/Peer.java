import java.io.IOException;

/**
 * One transport endpoint in the chat: somewhere already-formatted lines can be
 * delivered.
 *
 * <p>A peer knows nothing about names or rooms — that state lives in the
 * {@link Session} wrapping it, because both change over a connection's lifetime.
 * Implementations are the transport-specific sinks (TCP, UDP, WebSocket).
 */
interface Peer {
    /** Deliver one already-formatted chat line, framed for this transport. */
    void send(String line) throws IOException;

    /**
     * Whether this transport can act on control lines — the server talking to a
     * client rather than relaying chat to it. Transports that cannot (the
     * browser page, UDP) never receive them.
     */
    default boolean supportsControl() {
        return false;
    }
}

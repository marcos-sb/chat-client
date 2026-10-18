import java.io.IOException;

/** One participant in the room, independent of the transport it arrived on. */
interface Peer {
    /** Display name, as shown to the other peers. */
    String name();

    /** Deliver one already-formatted chat line, framed for this transport. */
    void send(String line) throws IOException;
}

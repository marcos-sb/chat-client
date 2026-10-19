import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected participant: its transport, the nick it is currently using, and
 * the room it is currently in.
 *
 * <p>The nick and room change as the user issues commands, and other threads
 * read them while broadcasting or listing members, so both are {@code volatile}.
 * {@link #markGone()} makes teardown idempotent: a session can be dropped by a
 * failed write in one thread and by its own transport loop in another.
 */
final class Session {
    private final Peer peer;
    private final AtomicBoolean gone = new AtomicBoolean();
    private volatile String nick;
    private volatile Room room;
    /** The {@code host:port} this client accepts direct links on, or null. */
    private volatile String link;

    Session(Peer peer, String nick, Room room) {
        this.peer = peer;
        this.nick = nick;
        this.room = room;
    }

    Peer peer() {
        return peer;
    }

    String nick() {
        return nick;
    }

    void nick(String nick) {
        this.nick = nick;
    }

    Room room() {
        return room;
    }

    void room(Room room) {
        this.room = room;
    }

    String link() {
        return link;
    }

    void link(String link) {
        this.link = link;
    }

    void send(String line) throws IOException {
        peer.send(line);
    }

    /** Returns true exactly once, for the caller that should tear this session down. */
    boolean markGone() {
        return gone.compareAndSet(false, true);
    }
}

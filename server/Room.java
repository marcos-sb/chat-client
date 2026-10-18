import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A named room: the set of sessions currently in it, whatever transport each
 * arrived on.
 *
 * <p>Members live in a {@link CopyOnWriteArrayList} so {@link #members()} walks a
 * stable snapshot while other threads join, leave, and are evicted after a write
 * failure. Delivery and eviction live in {@link Hub}, so a room is only a set.
 */
final class Room {
    private final String name;
    private final CopyOnWriteArrayList<Session> members = new CopyOnWriteArrayList<>();

    Room(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }

    void add(Session session) {
        members.add(session);
    }

    boolean remove(Session session) {
        return members.remove(session);
    }

    /** A live snapshot; safe to iterate while the room changes underneath. */
    List<Session> members() {
        return members;
    }

    int size() {
        return members.size();
    }
}

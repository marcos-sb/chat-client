import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single chat room: every joined peer, whatever transport it used.
 *
 * <p>Peers live in a {@link CopyOnWriteArrayList} so {@link #broadcast} walks a
 * stable snapshot while other threads join and leave, and so evicting one peer
 * after a write failure does not disturb the rest.
 */
final class Room {
    private final CopyOnWriteArrayList<Peer> peers = new CopyOnWriteArrayList<>();

    void join(Peer peer) {
        peers.add(peer);
        System.out.println("[server] " + peer.name() + " connected");
    }

    void leave(Peer peer) {
        peers.remove(peer);
        System.out.println("[server] " + peer.name() + " disconnected");
    }

    void broadcast(String line) {
        for (Peer peer : peers) {
            try {
                peer.send(line);
            } catch (IOException e) {
                peers.remove(peer);
            }
        }
    }
}

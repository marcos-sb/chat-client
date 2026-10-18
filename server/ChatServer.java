import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatServer {
    private static final String HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private static final int UDP_BUFFER_SIZE = 2048;
    private static final long UDP_IDLE_TIMEOUT_MS = 60_000L;
    private static final long UDP_PRUNE_INTERVAL_MS = 10_000L;

    private static final int UDP_SOCKET_BUFFER_BYTES = 4 * 1024 * 1024;

    private static final int MAX_NAME_LENGTH = 32;

    /** A TCP connection: its name, its socket, and a writer guarded by that peer's monitor. */
    private static final class TcpPeer implements Peer {
        private final String name;
        private final Socket socket;
        private final BufferedWriter writer;

        TcpPeer(String name, Socket socket, BufferedWriter writer) {
            this.name = name;
            this.socket = socket;
            this.writer = writer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void send(String line) throws IOException {
            synchronized (writer) {
                writer.write(line + "\n");
                writer.flush();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** A UDP address that has sent us datagrams recently; idle ones are pruned. */
    private static final class UdpPeer implements Peer {
        private final String name;
        private final SocketAddress address;
        private final DatagramSocket socket;
        private volatile long lastSeen;

        UdpPeer(String name, SocketAddress address, DatagramSocket socket) {
            this.name = name;
            this.address = address;
            this.socket = socket;
            this.lastSeen = System.currentTimeMillis();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void send(String line) throws IOException {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, address));
        }

        void touch() {
            lastSeen = System.currentTimeMillis();
        }
    }

    private static String truncate(String name) {
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static void handle(Socket conn, Room room) {
        String name = "anon";
        TcpPeer peer = null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String candidate = line.strip();
                if (!candidate.isEmpty()) {
                    name = truncate(candidate);
                    break;
                }
            }
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
            peer = new TcpPeer(name, conn, writer);
            room.join(peer);
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    room.broadcast(name + ": " + trimmed);
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (peer != null) {
                room.leave(peer);
            }
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void runUdp(int port, Room room) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(HOST, port));
        socket.setReceiveBufferSize(UDP_SOCKET_BUFFER_BYTES);
        Map<SocketAddress, UdpPeer> peers = new ConcurrentHashMap<>();
        System.out.println("[server] listening on " + HOST + ":" + port + " (udp)");
        Thread.ofVirtual().name("udp-pruner").start(() -> {
            while (true) {
                try {
                    Thread.sleep(UDP_PRUNE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (Map.Entry<SocketAddress, UdpPeer> e : new ArrayList<>(peers.entrySet())) {
                    if (now - e.getValue().lastSeen > UDP_IDLE_TIMEOUT_MS) {
                        UdpPeer peer = peers.remove(e.getKey());
                        if (peer != null) {
                            room.leave(peer);
                        }
                    }
                }
            }
        });
        byte[] buf = new byte[UDP_BUFFER_SIZE];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                continue;
            }
            SocketAddress addr = packet.getSocketAddress();
            UdpPeer peer = peers.get(addr);
            if (peer == null) {
                peer = new UdpPeer(truncate(text), addr, socket);
                peers.put(addr, peer);
                room.join(peer);
            } else {
                peer.touch();
                room.broadcast(peer.name() + ": " + text);
            }
        }
    }

    private static void runTcp(int port, Room room) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(HOST, port), 16);
        System.out.println("[server] listening on " + HOST + ":" + port);
        while (true) {
            Socket conn = server.accept();
            // Virtual threads are always daemon threads.
            Thread.ofVirtual().name("tcp-" + conn.getRemoteSocketAddress()).start(() -> handle(conn, room));
        }
    }

    public static void main(String[] args) throws IOException {
        boolean udp = false;
        int ai = 0;
        if (args.length > 0 && (args[0].equals("-u") || args[0].equals("--udp"))) {
            udp = true;
            ai = 1;
        }
        int port = DEFAULT_PORT;
        if (args.length > ai) {
            try {
                port = Integer.parseInt(args[ai]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java ChatServer [-u] [port]");
                System.exit(1);
            }
        }
        Room room = new Room();
        if (udp) {
            runUdp(port, room);
        } else {
            runTcp(port, room);
        }
    }
}

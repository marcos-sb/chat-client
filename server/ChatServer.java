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

    /** A TCP connection: a socket and a writer guarded by that peer's monitor. */
    private static final class TcpPeer implements Peer {
        private final Socket socket;
        private final BufferedWriter writer;

        TcpPeer(Socket socket, BufferedWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        @Override
        public void send(String line) throws IOException {
            synchronized (writer) {
                writer.write(line + "\n");
                writer.flush();
            }
        }
    }

    /** A UDP address that has sent us datagrams recently; idle ones are pruned. */
    private static final class UdpClient {
        final UdpPeer peer;
        final Session session;
        volatile long lastSeen;

        UdpClient(UdpPeer peer, Session session) {
            this.peer = peer;
            this.session = session;
            this.lastSeen = System.currentTimeMillis();
        }
    }

    private static final class UdpPeer implements Peer {
        private final SocketAddress address;
        private final DatagramSocket socket;

        UdpPeer(SocketAddress address, DatagramSocket socket) {
            this.address = address;
            this.socket = socket;
        }

        @Override
        public void send(String line) throws IOException {
            byte[] payload = line.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(payload, payload.length, address));
        }
    }

    /** Reads the first non-empty line as the requested display name. */
    private static String readName(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String candidate = line.strip();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static void handle(Socket conn, Hub hub) {
        Session session = null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
            String name = readName(reader);
            if (name == null) {
                return;
            }
            session = hub.connect(new TcpPeer(conn, writer), name);
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    hub.input(session, trimmed);
                }
            }
        } catch (Hub.Quit ignored) {
            // The user typed /quit; fall through to teardown.
        } catch (IOException ignored) {
        } finally {
            if (session != null) {
                hub.disconnect(session);
            }
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void runUdp(int port, Hub hub) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(HOST, port));
        socket.setReceiveBufferSize(UDP_SOCKET_BUFFER_BYTES);
        Map<SocketAddress, UdpClient> clients = new ConcurrentHashMap<>();
        System.out.println("[server] listening on " + HOST + ":" + port + " (udp)");
        Thread.ofVirtual().name("udp-pruner").start(() -> {
            while (true) {
                try {
                    Thread.sleep(UDP_PRUNE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (Map.Entry<SocketAddress, UdpClient> e : new ArrayList<>(clients.entrySet())) {
                    if (now - e.getValue().lastSeen > UDP_IDLE_TIMEOUT_MS) {
                        UdpClient client = clients.remove(e.getKey());
                        if (client != null) {
                            hub.disconnect(client.session);
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
            UdpClient client = clients.get(addr);
            if (client == null) {
                // The first datagram from an address names the sender.
                UdpPeer peer = new UdpPeer(addr, socket);
                client = new UdpClient(peer, hub.connect(peer, text));
                clients.put(addr, client);
                continue;
            }
            client.lastSeen = System.currentTimeMillis();
            try {
                hub.input(client.session, text);
            } catch (Hub.Quit e) {
                clients.remove(addr);
                hub.disconnect(client.session);
            }
        }
    }

    private static void runTcp(int port, Hub hub) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(HOST, port), 16);
        System.out.println("[server] listening on " + HOST + ":" + port);
        while (true) {
            Socket conn = server.accept();
            // Virtual threads are always daemon threads.
            Thread.ofVirtual().name("tcp-" + conn.getRemoteSocketAddress()).start(() -> handle(conn, hub));
        }
    }

    public static void main(String[] args) throws IOException {
        boolean udp = false;
        boolean webSocket = false;
        int ai = 0;
        if (args.length > 0 && (args[0].equals("-u") || args[0].equals("--udp"))) {
            udp = true;
            ai = 1;
        } else if (args.length > 0 && (args[0].equals("-w") || args[0].equals("--websocket"))) {
            webSocket = true;
            ai = 1;
        }
        int port = DEFAULT_PORT;
        if (args.length > ai) {
            try {
                port = Integer.parseInt(args[ai]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: java ChatServer [-u|-w] [port]");
                System.exit(1);
            }
        }
        Hub hub = new Hub();
        if (udp) {
            runUdp(port, hub);
        } else if (webSocket) {
            ChatWebSocketServer.run(port, hub);
        } else {
            runTcp(port, hub);
        }
    }
}

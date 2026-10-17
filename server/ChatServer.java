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

    private static final ConcurrentHashMap<Socket, Client> clients = new ConcurrentHashMap<>();

    private static final class Client {
        final String name;
        final BufferedWriter writer;

        Client(String name, BufferedWriter writer) {
            this.name = name;
            this.writer = writer;
        }
    }

    private static final class UdpClient {
        String name;
        long lastSeen;
    }

    private static void broadcast(String text) {
        for (Map.Entry<Socket, Client> e : new ArrayList<>(clients.entrySet())) {
            try {
                synchronized (e.getValue().writer) {
                    e.getValue().writer.write(text + "\n");
                    e.getValue().writer.flush();
                }
            } catch (IOException ex) {
                clients.remove(e.getKey());
            }
        }
    }

    private static void handle(Socket conn) {
        String name = "anon";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String candidate = line.strip();
                if (!candidate.isEmpty()) {
                    name = candidate.length() > 32 ? candidate.substring(0, 32) : candidate;
                    break;
                }
            }
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8));
            clients.put(conn, new Client(name, writer));
            System.out.println("[server] " + name + " connected");
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    broadcast(name + ": " + trimmed);
                }
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(conn);
            try {
                conn.close();
            } catch (IOException ignored) {
            }
            System.out.println("[server] " + name + " disconnected");
        }
    }

    private static void runUdp(int port) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(HOST, port));
        socket.setReceiveBufferSize(UDP_SOCKET_BUFFER_BYTES);
        ConcurrentHashMap<SocketAddress, UdpClient> clients = new ConcurrentHashMap<>();
        System.out.println("[server] listening on " + HOST + ":" + port + " (udp)");
        Thread pruner = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(UDP_PRUNE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (Map.Entry<SocketAddress, UdpClient> e : new ArrayList<>(clients.entrySet())) {
                    if (now - e.getValue().lastSeen > UDP_IDLE_TIMEOUT_MS) {
                        System.out.println("[server] " + e.getValue().name + " disconnected");
                        clients.remove(e.getKey());
                    }
                }
            }
        });
        pruner.setDaemon(true);
        pruner.start();
        byte[] buf = new byte[UDP_BUFFER_SIZE];
        while (true) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                continue;
            }
            SocketAddress addr = packet.getSocketAddress();
            UdpClient c = clients.get(addr);
            if (c == null) {
                String name = text.length() > 32 ? text.substring(0, 32) : text;
                UdpClient uc = new UdpClient();
                uc.name = name;
                uc.lastSeen = System.currentTimeMillis();
                clients.put(addr, uc);
                System.out.println("[server] " + name + " connected");
            } else {
                c.lastSeen = System.currentTimeMillis();
                byte[] payload = (c.name + ": " + text).getBytes(StandardCharsets.UTF_8);
                for (Map.Entry<SocketAddress, UdpClient> e : new ArrayList<>(clients.entrySet())) {
                    try {
                        socket.send(new DatagramPacket(payload, payload.length, e.getKey()));
                    } catch (IOException ex) {
                        clients.remove(e.getKey());
                    }
                }
            }
        }
    }

    private static void runTcp(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(HOST, port), 16);
        System.out.println("[server] listening on " + HOST + ":" + port);
        while (true) {
            Socket conn = server.accept();
            Thread t = new Thread(() -> handle(conn));
            t.setDaemon(true);
            t.start();
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
        if (udp) {
            runUdp(port);
        } else {
            runTcp(port);
        }
    }
}

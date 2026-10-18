import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

public class ChatClient {
    private static final String HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private static final int UDP_BUFFER_SIZE = 2048;

    private static void runUdp(int port) throws IOException {
        DatagramSocket sock = new DatagramSocket();
        SocketAddress server = new InetSocketAddress(HOST, port);
        System.out.print("Name: ");
        System.out.flush();
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String name = stdin.readLine();
        if (name == null) {
            name = "";
        }
        byte[] payload = name.getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(payload, payload.length, server));
        Thread.ofVirtual().name("udp-receiver").start(() -> {
            try {
                while (true) {
                    byte[] buf = new byte[UDP_BUFFER_SIZE];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    System.out.println(new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        });
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                byte[] msg = line.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(msg, msg.length, server));
            }
        } finally {
            sock.close();
        }
    }

    private static void runTcp(int port) throws IOException {
        Socket sock;
        try {
            sock = new Socket(HOST, port);
        } catch (IOException e) {
            System.out.println("[client] cannot connect to " + HOST + ":" + port + " — is the server running?");
            System.exit(1);
            return;
        }
        try {
            System.out.print("Name: ");
            System.out.flush();
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String name = stdin.readLine();
            if (name == null) {
                name = "";
            }
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            out.write(name + "\n");
            out.flush();
            Thread.ofVirtual().name("tcp-receiver").start(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                    String l;
                    while ((l = in.readLine()) != null) {
                        System.out.println(l);
                    }
                } catch (IOException ignored) {
                }
            });
            String line;
            while ((line = stdin.readLine()) != null) {
                out.write(line + "\n");
                out.flush();
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
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
                System.err.println("Usage: java ChatClient [-u] [port]");
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

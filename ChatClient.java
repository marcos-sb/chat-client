import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class ChatClient {
    private static final String HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 5000;

    private static final int UDP_BUFFER_SIZE = 2048;

    /**
     * Marks a control line from the server — the server talking to this client
     * rather than relaying chat. Mirrors {@code Hub.CONTROL}; the two must
     * agree, and the client deliberately does not depend on the server classes.
     */
    private static final String CONTROL = "\u0001";

    /** Whether this run can hold a listener and act on control lines. */
    private static boolean p2p;

    /** Guards stdout so lines from the server and from direct links do not interleave. */
    private static final Object OUT = new Object();

    private static void say(String line) {
        synchronized (OUT) {
            System.out.println(line);
        }
    }

    /**
     * Established direct links to other clients, keyed by their nick.
     *
     * <p>Only used with {@code --p2p} over TCP: the server still carries presence
     * and rooms, but {@code /msg} to a linked peer goes straight to that peer
     * instead of through the server.
     */
    private static final ConcurrentHashMap<String, DirectLink> links = new ConcurrentHashMap<>();

    /** One open peer-to-peer connection and the writer for it. */
    private static final class DirectLink {
        private final String nick;
        private final Socket socket;
        private final BufferedWriter writer;

        DirectLink(String nick, Socket socket, BufferedWriter writer) {
            this.nick = nick;
            this.socket = socket;
            this.writer = writer;
        }

        String nick() {
            return nick;
        }

        void send(String text) throws IOException {
            synchronized (writer) {
                writer.write(text + "\n");
                writer.flush();
            }
        }
    }

    /** Registers a link, closing any link it replaces. */
    private static void putLink(String nick, DirectLink link) {
        DirectLink old = links.put(nick, link);
        if (old != null && old != link) {
            try {
                old.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Closes every direct link; called when the client is done. */
    private static void closeLinks() {
        for (DirectLink link : links.values()) {
            try {
                link.socket.close();
            } catch (IOException ignored) {
            }
        }
        links.clear();
    }

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
                // One buffer and one packet for the life of the connection:
                // a datagram burst should not allocate per packet.
                byte[] buf = new byte[UDP_BUFFER_SIZE];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (true) {
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
        // Chat lines are small and sent one at a time, so Nagle's algorithm
        // would hold them back waiting for an ack. Turning it off makes a
        // keystroke's worth of text leave immediately.
        sock.setTcpNoDelay(true);

        // A listener is the peer's half of a direct link; the server supplies the
        // other half by telling a peer where to reach us.
        ServerSocket listener = p2p ? new ServerSocket(0, 16, InetAddress.getByName(HOST)) : null;
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

            if (listener != null) {
                out.write("/listen " + HOST + ":" + listener.getLocalPort() + "\n");
                out.flush();
                acceptLinks(listener);
            }

            Thread.ofVirtual().name("tcp-receiver").start(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                    String l;
                    while ((l = in.readLine()) != null) {
                        handleServerLine(l);
                    }
                } catch (IOException ignored) {
                }
            });

            String line;
            while ((line = stdin.readLine()) != null) {
                if (!sendDirect(line)) {
                    out.write(line + "\n");
                    out.flush();
                }
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException ignored) {
                }
            }
            closeLinks();
        }
    }

    // ---- peer-to-peer links ---------------------------------------------

    /** Accepts inbound direct links in the background, one virtual thread each. */
    private static void acceptLinks(ServerSocket listener) {
        Thread.ofVirtual().name("p2p-listener").start(() -> {
            while (true) {
                Socket peer;
                try {
                    peer = listener.accept();
                } catch (IOException e) {
                    return;
                }
                Thread.ofVirtual().name("p2p-accept").start(() -> accept(peer));
            }
        });
    }

    /** An inbound link: the dialer sends its nick as the first line. */
    private static void accept(Socket peer) {
        try {
            peer.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
            String nick = in.readLine();
            if (nick == null) {
                peer.close();
                return;
            }
            nick = nick.strip();
            DirectLink link = new DirectLink(nick, peer, out);
            putLink(nick, link);
            say("* direct link from " + nick + " established (messages now bypass the server)");
            readLink(link, in);
        } catch (IOException ignored) {
            try {
                peer.close();
            } catch (IOException ignored2) {
            }
        }
    }

    /** An outbound link: announce our nick, then read. */
    private static void dial(String me, String them, String hostPort) {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            say("* could not open a direct link to " + them + ": bad address " + hostPort);
            return;
        }
        try {
            Socket peer = new Socket();
            // Set the option before connecting, so the handshake is not delayed
            // either.
            peer.setTcpNoDelay(true);
            peer.connect(new InetSocketAddress(hostPort.substring(0, colon),
                    Integer.parseInt(hostPort.substring(colon + 1))));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            out.write(me + "\n");
            out.flush();
            DirectLink link = new DirectLink(them, peer, out);
            putLink(them, link);
            say("* direct link to " + them + " established (messages now bypass the server)");
            readLink(link, in);
        } catch (IOException | RuntimeException e) {
            say("* could not open a direct link to " + them + ": " + e.getMessage());
        }
    }

    /**
     * Reads a link until it closes, then takes that link down. The link is
     * tracked by identity, so a stale connection ending never tears down a
     * newer link to the same nick.
     */
    private static void readLink(DirectLink link, BufferedReader in) {
        String nick = link.nick();
        try {
            String line;
            while ((line = in.readLine()) != null) {
                say("[dm] " + nick + ": " + line);
            }
        } catch (IOException ignored) {
        } finally {
            boolean current = links.remove(nick, link);
            try {
                link.socket.close();
            } catch (IOException ignored) {
            }
            if (current) {
                say("* direct link with " + nick + " closed");
            }
        }
    }

    /** Server lines: control instructions are acted on, the rest are shown. */
    private static void handleServerLine(String line) {
        if (line.startsWith(CONTROL)) {
            handleControl(line.substring(CONTROL.length()));
            return;
        }
        say(line);
    }

    /**
     * A control line from the server: {@code link} asks us to dial a peer,
     * {@code link-incoming} announces that one is about to dial us.
     */
    private static void handleControl(String line) {
        if (!p2p) {
            // This client cannot hold a listener, so there is nothing to act on.
            return;
        }
        if (line.startsWith("link ")) {
            String[] parts = line.split(" ", 4);
            if (parts.length == 4) {
                String me = parts[1];
                String them = parts[2];
                String address = parts[3];
                Thread.ofVirtual().name("p2p-dial").start(() -> dial(me, them, address));
            }
            return;
        }
        if (line.startsWith("link-incoming ")) {
            String[] parts = line.split(" ", 3);
            if (parts.length >= 2) {
                say("* " + parts[1] + " is opening a direct link to you");
            }
        }
    }

    /**
     * Finds a link by nick, ignoring case the way the server resolves names.
     */
    private static DirectLink link(String nick) {
        DirectLink exact = links.get(nick);
        if (exact != null) {
            return exact;
        }
        for (DirectLink link : links.values()) {
            if (link.nick().equalsIgnoreCase(nick)) {
                return link;
            }
        }
        return null;
    }

    /**
     * Sends {@code /msg <name> ...} over a direct link when one exists, and says
     * whether it did. Returning false lets the caller fall back to the server.
     */
    private static boolean sendDirect(String line) {
        // Ordinary chat is the common case, so look for a command before
        // spending anything on the text: a leading slash is the only way in.
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        if (start >= line.length() || line.charAt(start) != '/') {
            return false;
        }
        String trimmed = line.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int cut;
        if (lower.startsWith("/msg ")) {
            cut = 5;
        } else if (lower.startsWith("/dm ")) {
            cut = 4;
        } else if (lower.startsWith("/whisper ")) {
            cut = 9;
        } else {
            return false;
        }
        String rest = trimmed.substring(cut);
        int space = rest.indexOf(' ');
        if (space < 0) {
            return false;
        }
        String to = rest.substring(0, space);
        String body = rest.substring(space + 1).strip();
        if (body.isEmpty()) {
            return false;
        }
        DirectLink link = link(to);
        if (link == null) {
            return false;
        }
        try {
            link.send(body);
            say("[dm] to " + link.nick() + ": " + body);
        } catch (IOException e) {
            // The link is broken: drop it and let the message go via the server
            // rather than lose it.
            links.remove(link.nick(), link);
            say("* direct link to " + link.nick() + " failed (" + e.getMessage()
                    + ") — sending via the server");
            return false;
        }
        return true;
    }

    public static void main(String[] args) throws IOException {
        boolean udp = false;
        int port = DEFAULT_PORT;
        for (String arg : args) {
            if (arg.equals("-u") || arg.equals("--udp")) {
                udp = true;
            } else if (arg.equals("-p") || arg.equals("--p2p")) {
                p2p = true;
            } else {
                try {
                    port = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    System.err.println("Usage: java ChatClient [-u] [-p|--p2p] [port]");
                    System.exit(1);
                }
            }
        }
        if (udp && p2p) {
            System.err.println("[client] --p2p is only available over TCP");
            System.exit(1);
        }
        if (udp) {
            runUdp(port);
        } else {
            runTcp(port);
        }
    }
}

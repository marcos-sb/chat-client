import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // ---- how lines are printed -------------------------------------------

    private static final String RESET = "\u001b[0m";
    private static final String DIM = "\u001b[2m";
    private static final String BOLD = "\u001b[1m";
    private static final String MAGENTA = "\u001b[35m";
    private static final String CYAN = "\u001b[36m";
    private static final String BELL = "\u0007";

    /** How long a private message may wait before the bell rings. */
    private static final long IDLE_MS = 5_000;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static boolean useColour;
    private static boolean useTimestamps;
    private static boolean useBell = true;

    /** Raw mode: plain lines on stdout, nothing else in the way. */
    private static boolean raw;

    /** When the user last typed something; a quiet client is probably away. */
    private static volatile long lastInputAt = System.currentTimeMillis();

    /** The name this client asked for; the server may have changed it. */
    private static String myName = "";

    /**
     * A secret this client presents to a peer, and expects back. It is what
     * tells a genuine introduction apart from a stranger that found the
     * listener's address.
     */
    private static final String TOKEN = randomToken();

    /**
     * Introductions this client is expecting, keyed by the secret the peer
     * must present, with the peer's name as the value.
     */
    private static final ConcurrentHashMap<String, String> expected = new ConcurrentHashMap<>();

    private static String randomToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Guards stdout so lines from the server and from direct links do not interleave. */
    private static final Object OUT = new Object();

    /**
     * Where the client's output goes. It is buffered, so a burst of lines
     * costs one write instead of one per line; a thread flushes it every
     * sixteenth of a second, and before anything the client waits for.
     */
    private static final BufferedOutputStream OUT_BUFFER =
            new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192);

    private static void say(String line) {
        StringBuilder out = new StringBuilder();
        if (useTimestamps) {
            out.append(LocalTime.now().format(CLOCK)).append(' ');
        }
        boolean private_ = line.startsWith("[dm] ");
        if (private_ && useBell && idle()) {
            out.append(BELL);
        }
        if (useColour) {
            if (private_) {
                out.append(MAGENTA).append(line).append(RESET);
            } else if (line.startsWith("* ")) {
                out.append(DIM).append(line).append(RESET);
            } else if (line.startsWith("[")) {
                out.append(CYAN).append(line).append(RESET);
            } else {
                out.append(line);
            }
        } else {
            out.append(line);
        }
        byte[] bytes = (out + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (OUT) {
            try {
                OUT_BUFFER.write(bytes);
            } catch (IOException ignored) {
            }
        }
    }

    /** Pushes whatever is buffered out to the terminal. */
    private static void flushOutput() {
        synchronized (OUT) {
            try {
                OUT_BUFFER.flush();
            } catch (IOException ignored) {
            }
        }
    }

    /** Keeps the output moving, so a line never sits in the buffer for long. */
    private static void startFlusher() {
        Thread.ofVirtual().name("out-flusher").start(() -> {
            while (true) {
                try {
                    Thread.sleep(16);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                flushOutput();
            }
        });
    }

    /** Whether the user has been quiet for a while, and so may be away. */
    private static boolean idle() {
        return System.currentTimeMillis() - lastInputAt > IDLE_MS;
    }

    /**
     * Asks the user something. In raw mode the question goes to stderr, so
     * that stdout holds nothing but the chat.
     */
    private static void prompt(String question) {
        if (raw) {
            System.err.print(question);
            System.err.flush();
        } else {
            synchronized (OUT) {
                try {
                    OUT_BUFFER.write(question.getBytes(StandardCharsets.UTF_8));
                    OUT_BUFFER.flush();
                } catch (IOException ignored) {
                }
            }
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

    private static void runUdp(int port, String name, String room) throws IOException {
        DatagramSocket sock = new DatagramSocket();
        SocketAddress server = new InetSocketAddress(HOST, port);
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String chosen = name;
        if (chosen == null) {
            prompt("Name: ");
            chosen = stdin.readLine();
            if (chosen == null) {
                chosen = "";
            }
        }
        myName = chosen;
        byte[] payload = chosen.getBytes(StandardCharsets.UTF_8);
        sock.send(new DatagramPacket(payload, payload.length, server));
        if (room != null) {
            byte[] join = ("/join " + room).getBytes(StandardCharsets.UTF_8);
            sock.send(new DatagramPacket(join, join.length, server));
        }
        Thread.ofVirtual().name("udp-receiver").start(() -> {
            try {
                // One buffer and one packet for the life of the connection:
                // a datagram burst should not allocate per packet.
                byte[] buf = new byte[UDP_BUFFER_SIZE];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (true) {
                    sock.receive(p);
                    say(new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        });
        try {
            String line;
            while ((line = stdin.readLine()) != null) {
                lastInputAt = System.currentTimeMillis();
                byte[] msg = line.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(msg, msg.length, server));
            }
        } finally {
            flushOutput();
            sock.close();
        }
    }

    private static void runTcp(int port, String name, String room) throws IOException {
        // A listener is the peer's half of a direct link; the server supplies the
        // other half by telling a peer where to reach us.
        ServerSocket listener = p2p ? new ServerSocket(0, 16, InetAddress.getByName(HOST)) : null;
        ServerLink link = null;
        try {
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String chosen = name;
            if (chosen == null) {
                prompt("Name: ");
                chosen = stdin.readLine();
                if (chosen == null) {
                    chosen = "";
                }
            }
            myName = chosen;
            if (listener != null) {
                acceptLinks(listener);
            }
            link = new ServerLink(port, chosen, room, listener);
            if (!link.open()) {
                System.out.println("[client] cannot connect to " + HOST + ":" + port + " — is the server running?");
                System.exit(1);
                return;
            }
            link.supervise();

            String line;
            while ((line = stdin.readLine()) != null) {
                lastInputAt = System.currentTimeMillis();
                if (!handleLocal(line)) {
                    link.send(line);
                }
            }
        } finally {
            flushOutput();
            if (link != null) {
                link.stop();
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

    /** One connection to the server, and whether it is still good. */
    private static final class Connection {
        private final Socket socket;
        private final BufferedWriter out;
        private volatile boolean alive = true;

        Connection(Socket socket, BufferedWriter out) {
            this.socket = socket;
            this.out = out;
        }

        Socket socket() {
            return socket;
        }

        boolean alive() {
            return alive;
        }

        void dead() {
            alive = false;
        }

        void send(String line) throws IOException {
            synchronized (out) {
                out.write(line + "\n");
                out.flush();
            }
        }

        void close() {
            alive = false;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * The client's connection to the server, and the machinery that brings it
     * back when the server goes away.
     *
     * <p>The listener for direct links outlives a reconnect: it stays open, and
     * only the registration with the server is repeated, so the links a peer
     * already has keep working.
     */
    private static final class ServerLink {
        private final int port;
        private final String name;
        private final String room;
        private final ServerSocket listener;
        private Connection connection;
        private boolean stopped;

        ServerLink(int port, String name, String room, ServerSocket listener) {
            this.port = port;
            this.name = name;
            this.room = room;
            this.listener = listener;
        }

        /** Connects and registers; false if the server is not there. */
        boolean open() {
            try {
                set(open0());
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        /** Connects, registers, and starts reading from the new connection. */
        private Connection open0() throws IOException {
            Socket sock = new Socket(HOST, port);
            // Chat lines are small and sent one at a time, so Nagle's algorithm
            // would hold them back waiting for an ack.
            sock.setTcpNoDelay(true);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
            out.write(name + "\n");
            out.flush();
            if (listener != null) {
                out.write("/listen " + HOST + ":" + listener.getLocalPort() + " " + TOKEN + "\n");
                out.flush();
            }
            if (room != null) {
                out.write("/join " + room + "\n");
                out.flush();
            }
            Connection conn = new Connection(sock, out);
            Thread.ofVirtual().name("tcp-receiver").start(() -> read(conn));
            return conn;
        }

        private synchronized void set(Connection conn) {
            connection = conn;
            notifyAll();
        }

        /** Reads from the connection until it ends, then marks it dead. */
        private void read(Connection conn) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.socket().getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    handleServerLine(line);
                }
            } catch (IOException ignored) {
            }
            conn.dead();
            synchronized (this) {
                notifyAll();
            }
            if (!stopped) {
                say("* the server connection was lost — reconnecting");
            }
        }

        /** Keeps the connection up, reconnecting with a backoff when it drops. */
        void supervise() {
            Thread.ofVirtual().name("tcp-supervisor").start(() -> {
                long backoff = 250;
                while (true) {
                    synchronized (ServerLink.this) {
                        while (!stopped && connection != null && connection.alive()) {
                            try {
                                ServerLink.this.wait(500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (stopped) {
                            return;
                        }
                    }
                    try {
                        set(open0());
                        say("* reconnected to the server");
                        backoff = 250;
                    } catch (IOException e) {
                        synchronized (ServerLink.this) {
                            try {
                                ServerLink.this.wait(backoff);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        backoff = Math.min(backoff * 2, 5000);
                    }
                }
            });
        }

        /** Sends a line, waiting for the connection to come back if it dropped. */
        void send(String line) throws IOException {
            if (!alive()) {
                say("* the server is away — this message waits for it");
            }
            while (true) {
                Connection conn = await();
                try {
                    conn.send(line);
                    return;
                } catch (IOException e) {
                    // The connection dropped between the check and the write:
                    // mark it dead and try again on the next one.
                    conn.dead();
                    synchronized (this) {
                        notifyAll();
                    }
                }
            }
        }

        private synchronized boolean alive() {
            return connection != null && connection.alive();
        }

        /** The live connection, waiting for the supervisor to restore it. */
        private synchronized Connection await() throws IOException {
            while (true) {
                if (stopped) {
                    throw new IOException("the client is stopping");
                }
                if (connection != null && connection.alive()) {
                    return connection;
                }
                try {
                    wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
        }

        /** Closes the connection and stops reconnecting. */
        synchronized void stop() {
            stopped = true;
            if (connection != null) {
                connection.close();
            }
            notifyAll();
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

    /**
     * An inbound link. The peer opens with its secret and its name; the secret
     * has to be one the server told us to expect, or the connection is
     * refused. A peer that brings no secret is an older client, and is taken
     * at its word.
     */
    private static void accept(Socket peer) {
        try {
            peer.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(peer.getOutputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null) {
                peer.close();
                return;
            }
            first = first.strip();
            int space = first.indexOf(' ');
            String secret = space < 0 ? "" : first.substring(0, space);
            String claimed = space < 0 ? first : first.substring(space + 1).strip();
            if (secret.isEmpty()) {
                // No secret: the peer only gave its name.
                DirectLink link = new DirectLink(claimed, peer, out);
                putLink(claimed, link);
                say("* direct link from " + claimed + " established (messages now bypass the server)");
                readLink(link, in);
                return;
            }
            String nick = awaitIntroduction(secret);
            if (nick == null) {
                say("* refused a direct link: nobody introduced that peer");
                peer.close();
                return;
            }
            // Answer with our own secret, so both sides know who they are talking to.
            out.write(TOKEN + " " + myName + "\n");
            out.flush();
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

    /**
     * Waits for the server to introduce a peer presenting the given secret,
     * and returns the name it gave. The connection can arrive a moment before
     * the introduction does, so this waits a little.
     */
    private static String awaitIntroduction(String secret) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            String nick = expected.remove(secret);
            if (nick != null) {
                return nick;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * An outbound link. The peer is told our secret first; it answers with
     * its own, which has to be the one the server said to expect.
     */
    private static void dial(String me, String them, String hostPort, String theirSecret) {
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
            if (theirSecret.isEmpty()) {
                // The peer does not authenticate: the old, plain handshake.
                out.write(me + "\n");
                out.flush();
            } else {
                out.write(TOKEN + " " + me + "\n");
                out.flush();
                String answer = in.readLine();
                if (answer == null) {
                    say("* could not open a direct link to " + them + ": the peer hung up");
                    peer.close();
                    return;
                }
                String[] parts = answer.strip().split(" ", 2);
                if (parts.length < 2 || !parts[0].equals(theirSecret)) {
                    say("* could not open a direct link to " + them + ": the peer did not identify itself");
                    peer.close();
                    return;
                }
            }
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
            String[] parts = line.split(" ", 5);
            if (parts.length >= 4) {
                String me = parts[1];
                String them = parts[2];
                String address = parts[3];
                String secret = parts.length == 5 ? parts[4] : "";
                Thread.ofVirtual().name("p2p-dial").start(() -> dial(me, them, address, secret));
            }
            return;
        }
        if (line.startsWith("link-incoming ")) {
            String[] parts = line.split(" ", 4);
            if (parts.length >= 3) {
                // Remember the secret this peer has to present, so a stranger
                // cannot walk in with a name of its own choosing.
                if (parts.length == 4 && !parts[3].isEmpty()) {
                    expected.put(parts[3], parts[1]);
                }
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
     * Handles the lines the client answers itself, without the server:
     * the link commands, and private messages that go over a direct link.
     * Returns true when the line was consumed and must not reach the server.
     */
    private static boolean handleLocal(String line) {
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
        if (lower.equals("/links")) {
            showLinks();
            return true;
        }
        if (lower.startsWith("/unlink")) {
            unlink(trimmed.substring("/unlink".length()).strip());
            return true;
        }
        if (lower.equals("/help")) {
            // The client has commands of its own, so it lists them before the
            // server's list arrives.
            say("* client commands:");
            say("*   /links            list the direct links you hold");
            say("*   /unlink <name>    close a direct link");
            return false; // the server's own help still follows
        }
        return sendDirect(trimmed, lower);
    }

    /** Lists the direct links this client holds. */
    private static void showLinks() {
        if (links.isEmpty()) {
            say("* no direct links");
            return;
        }
        List<String> nicks = new ArrayList<>();
        for (DirectLink link : links.values()) {
            nicks.add(link.nick());
        }
        Collections.sort(nicks);
        for (String nick : nicks) {
            say("* direct link to " + nick + " (open)");
        }
    }

    /** Drops the direct link to a peer, if there is one. */
    private static void unlink(String nick) {
        if (nick.isEmpty()) {
            say("* usage: /unlink <name>");
            return;
        }
        DirectLink link = link(nick);
        if (link == null) {
            say("* no direct link to \"" + nick + "\"");
            return;
        }
        links.remove(link.nick(), link);
        try {
            link.socket.close();
        } catch (IOException ignored) {
        }
        say("* direct link to " + link.nick() + " closed");
    }

    /**
     * Sends {@code /msg <name> ...} over a direct link when one exists, and says
     * whether it did. Returning false lets the caller fall back to the server.
     */
    private static boolean sendDirect(String trimmed, String lower) {
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
        String name = null;
        String room = null;
        String colour = "auto";
        startFlusher();
        // Whatever is still buffered has to reach the terminal when the client
        // is stopped, so it goes out on the way down.
        Runtime.getRuntime().addShutdownHook(new Thread(ChatClient::flushOutput));
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-u") || arg.equals("--udp")) {
                udp = true;
            } else if (arg.equals("-p") || arg.equals("--p2p")) {
                p2p = true;
            } else if (arg.equals("-n") || arg.equals("--name")) {
                i++;
                name = value(args, i, "--name");
            } else if (arg.startsWith("--name=")) {
                name = arg.substring("--name=".length());
            } else if (arg.equals("-r") || arg.equals("--room")) {
                i++;
                room = value(args, i, "--room");
            } else if (arg.startsWith("--room=")) {
                room = arg.substring("--room=".length());
            } else if (arg.equals("-c") || arg.equals("--color")) {
                i++;
                colour = value(args, i, "--color");
            } else if (arg.startsWith("--color=")) {
                colour = arg.substring("--color=".length());
            } else if (arg.equals("-t") || arg.equals("--timestamps")) {
                useTimestamps = true;
            } else if (arg.equals("--no-bell")) {
                useBell = false;
            } else if (arg.equals("--raw")) {
                raw = true;
            } else {
                try {
                    port = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    usage("cannot read \"" + arg + "\" as a port");
                }
            }
        }
        if (udp && p2p) {
            usage("--p2p is only available over TCP");
        }
        if (colour.equals("auto")) {
            useColour = System.console() != null;
        } else if (colour.equals("always")) {
            useColour = true;
        } else if (colour.equals("never")) {
            useColour = false;
        } else {
            usage("--color takes auto, always or never");
        }
        if (raw) {
            // Raw mode is for piping the chat somewhere else, so stdout holds
            // nothing but the lines themselves.
            useColour = false;
            useTimestamps = false;
            useBell = false;
        }
        if (udp) {
            runUdp(port, name, room);
        } else {
            runTcp(port, name, room);
        }
    }

    /** The value that follows a flag, or an error if it is missing. */
    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            usage(flag + " needs a value");
            return null; // usage() exits
        }
        return args[index];
    }

    private static void usage(String message) {
        System.err.println("[client] " + message);
        System.err.println("Usage: java ChatClient [-u] [-p|--p2p] [-n|--name <name>]"
                + " [-r|--room <room>] [-c|--color auto|always|never] [-t|--timestamps]"
                + " [--no-bell] [--raw] [port]");
        System.exit(1);
    }
}

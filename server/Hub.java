import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns every room and session, and turns the lines users type into room traffic.
 *
 * <p>All three transports — TCP, UDP, and WebSocket — funnel through here, so the
 * command protocol and the room rules exist once. A transport reads a first line
 * for the requested name and calls {@link #connect}; every later line goes to
 * {@link #input}; teardown calls {@link #disconnect}.
 *
 * <p>A line beginning with {@code /} is a command ({@code /help}, {@code /join},
 * {@code /nick}, …). Anything else is broadcast to the sender's room as
 * {@code nick: message}.
 */
final class Hub {
    static final String DEFAULT_ROOM = "lobby";
    private static final int MAX_NICK_LENGTH = 32;
    private static final int MAX_ROOM_NAME_LENGTH = 32;
    /** Longer than any chat line should be; protects the broadcast path. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    /**
     * Marks a line the server sends for a client's own benefit rather than as
     * chat traffic.
     *
     * <p>Relayed chat always starts with the sender's nick, and nicks are
     * cleaned down to letters, digits and {@code . _ -}, so no message a user
     * types can look like a control line.
     */
    static final String CONTROL = "\u0001";

    /** Letters, digits, and {@code . _ -}, the only characters names may use. */
    private static final Pattern VALID_NAME = Pattern.compile("[\\p{L}\\p{N}_.\\-]+");
    private static final Pattern NAME_CHARS = Pattern.compile("[\\p{L}\\p{N}_.\\-]");

    /** Thrown to unwind a transport loop when a session asks to quit. */
    static final class Quit extends RuntimeException {
        Quit() {
            super(null, null, false, false);
        }
    }

    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();
    /** Sessions by current nick; guarded by {@link #nickLock} for changes. */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object nickLock = new Object();

    /**
     * Registers a newly connected peer, giving it a unique nick and dropping it
     * into the default room. Returns the session the transport should use.
     */
    Session connect(Peer peer, String requestedName) {
        Room lobby = room(DEFAULT_ROOM);
        Session session;
        String assigned;
        synchronized (nickLock) {
            assigned = uniqueNickLocked(requestedName);
            session = new Session(peer, assigned, lobby);
            sessions.put(assigned, session);
            lobby.add(session);
        }
        System.out.println("[server] " + assigned + " connected to #" + lobby.name());
        if (!assigned.equals(requestedName.strip())) {
            reply(session, "* you are known as \"" + assigned + "\"");
        }
        reply(session, "* welcome to #" + lobby.name() + " — " + memberCount(lobby)
                + ". Type /help for commands.");
        broadcastExcept(lobby, "* " + assigned + " joined #" + lobby.name(), session);
        return session;
    }

    /** Handles one line from a session: a command, or a message for its room. */
    void input(Session session, String line) {
        Room room = session.room();
        if (room == null) {
            // Already torn down by a failed write; ignore any straggler line.
            return;
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.charAt(0) == '/') {
            command(session, text);
            return;
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            text = text.substring(0, MAX_MESSAGE_LENGTH);
        }
        broadcast(room, session.nick() + ": " + text);
    }

    /** Removes a session from its room once; later calls are no-ops. */
    void disconnect(Session session) {
        if (!session.markGone()) {
            return;
        }
        Room room = session.room();
        String nick = session.nick();
        session.room(null);
        room.remove(session);
        sessions.remove(nick, session);
        System.out.println("[server] " + nick + " left #" + room.name());
        broadcastExcept(room, "* " + nick + " left #" + room.name(), session);
        // Empty non-default rooms are garbage; the default room persists.
        if (!room.name().equals(DEFAULT_ROOM) && room.size() == 0) {
            rooms.remove(room.name(), room);
        }
    }

    private void command(Session session, String text) {
        int space = text.indexOf(' ');
        String name = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : text.substring(space + 1).strip();
        switch (name) {
            case "/help", "/?" -> help(session);
            case "/nick", "/name" -> nick(session, rest);
            case "/join" -> join(session, rest);
            case "/leave", "/part" -> leave(session);
            case "/rooms", "/list" -> rooms(session);
            case "/who", "/names" -> who(session);
            case "/msg", "/dm", "/whisper" -> direct(session, rest);
            case "/me" -> emote(session, rest);
            case "/listen" -> listen(session, rest);
            case "/link" -> link(session, rest);
            case "/quit", "/exit" -> throw new Quit();
            default -> reply(session, "* unknown command \"" + name + "\" — try /help");
        }
    }

    private void help(Session session) {
        reply(session, "* commands:");
        reply(session, "*   /nick <name>      change your name");
        reply(session, "*   /join <room>      join or create a room");
        reply(session, "*   /leave            return to #" + DEFAULT_ROOM);
        reply(session, "*   /rooms            list rooms");
        reply(session, "*   /who              list who is in your room");
        reply(session, "*   /msg <name> <x>   private message");
        reply(session, "*   /link <name>      open a direct (p2p) link (TCP clients only)");
        reply(session, "*   /me <action>      emote");
        reply(session, "*   /quit             disconnect");
        reply(session, "* anything else is sent to your room.");
    }

    private void nick(Session session, String rest) {
        if (rest.isEmpty()) {
            reply(session, "* usage: /nick <name>");
            return;
        }
        if (!validName(rest, MAX_NICK_LENGTH)) {
            reply(session, "* \"" + rest + "\" is not a valid name (letters, digits, . _ -, max "
                    + MAX_NICK_LENGTH + ")");
            return;
        }
        String old = null;
        String error = null;
        synchronized (nickLock) {
            if (rest.equals(session.nick())) {
                error = "you are already \"" + rest + "\"";
            } else if (sessions.containsKey(rest)) {
                error = "\"" + rest + "\" is already taken";
            } else {
                old = session.nick();
                sessions.remove(old);
                sessions.put(rest, session);
                session.nick(rest);
            }
        }
        if (error != null) {
            reply(session, "* " + error);
            return;
        }
        reply(session, "* you are now known as \"" + rest + "\"");
        broadcastExcept(session.room(), "* " + old + " is now known as " + rest, session);
    }

    private void join(Session session, String rest) {
        String name = cleanRoomName(rest);
        if (name == null) {
            reply(session, "* usage: /join <room> (letters, digits, . _ -, max "
                    + MAX_ROOM_NAME_LENGTH + ")");
            return;
        }
        Room target = room(name);
        Room current = session.room();
        if (target == current) {
            reply(session, "* you are already in #" + name);
            return;
        }
        current.remove(session);
        target.add(session);
        session.room(target);
        reply(session, "* joined #" + target.name() + " — " + memberCount(target));
        broadcastExcept(target, "* " + session.nick() + " joined #" + target.name(), session);
        broadcastExcept(current, "* " + session.nick() + " left #" + current.name(), session);
        if (!current.name().equals(DEFAULT_ROOM) && current.size() == 0) {
            rooms.remove(current.name(), current);
        }
    }

    private void leave(Session session) {
        Room current = session.room();
        if (current.name().equals(DEFAULT_ROOM)) {
            reply(session, "* you are already in #" + DEFAULT_ROOM);
            return;
        }
        join(session, DEFAULT_ROOM);
    }

    private void rooms(Session session) {
        List<Room> all = new ArrayList<>(rooms.values());
        all.sort(Comparator.comparing(Room::name));
        reply(session, "* rooms:");
        for (Room room : all) {
            String marker = room == session.room() ? " (here)" : "";
            reply(session, "*   #" + room.name() + " — " + memberCount(room) + marker);
        }
    }

    private void who(Session session) {
        Room room = session.room();
        reply(session, "* #" + room.name() + " — " + memberCount(room) + ":");
        for (Session member : room.members()) {
            String marker = member == session ? " (you)" : "";
            reply(session, "*   " + member.nick() + marker);
        }
    }

    private void direct(Session session, String rest) {
        int space = rest.indexOf(' ');
        if (space < 0) {
            reply(session, "* usage: /msg <name> <message>");
            return;
        }
        String to = rest.substring(0, space);
        String body = rest.substring(space + 1).strip();
        if (body.isEmpty()) {
            reply(session, "* usage: /msg <name> <message>");
            return;
        }
        Session target = find(to);
        if (target == null) {
            reply(session, "* no one named \"" + to + "\" is here");
            return;
        }
        if (body.length() > MAX_MESSAGE_LENGTH) {
            body = body.substring(0, MAX_MESSAGE_LENGTH);
        }
        reply(target, "[dm] " + session.nick() + ": " + body);
        if (target != session) {
            reply(session, "[dm] to " + target.nick() + ": " + body);
        }
    }

    private void emote(Session session, String rest) {
        if (rest.isEmpty()) {
            reply(session, "* usage: /me <action>");
            return;
        }
        broadcast(session.room(), "* " + session.nick() + " " + rest);
    }

    /**
     * Records where this client accepts direct connections, and the secret it
     * expects a peer to present. Sent automatically by a {@code --p2p} client;
     * the server only relays the details, it never dials.
     */
    private void listen(Session session, String rest) {
        if (!session.peer().supportsControl()) {
            reply(session, "* direct links need the TCP client (run it with --p2p)");
            return;
        }
        int space = rest.indexOf(' ');
        String address = cleanAddress(space < 0 ? rest : rest.substring(0, space));
        String token = space < 0 ? "" : rest.substring(space + 1).strip();
        if (address == null || (!token.isEmpty() && !validToken(token))) {
            reply(session, "* usage: /listen <host:port> [token]");
            return;
        }
        session.linkAddress(address);
        session.linkToken(token);
        reply(session, "* direct links enabled at " + address);
    }

    /**
     * Rendezvous: hands each side the other's address so they can connect
     * directly. The server stays out of the resulting conversation.
     */
    private void link(Session session, String rest) {
        if (rest.isEmpty()) {
            reply(session, "* usage: /link <name>");
            return;
        }
        Session target = find(rest);
        if (target == null) {
            reply(session, "* no one named \"" + rest + "\" is here");
            return;
        }
        if (target == session) {
            reply(session, "* you cannot link to yourself");
            return;
        }
        if (!session.peer().supportsControl() || !target.peer().supportsControl()) {
            reply(session, "* direct links need the TCP client on both sides");
            return;
        }
        String mine = session.linkAddress();
        String theirs = target.linkAddress();
        if (mine == null) {
            reply(session, "* your client has not enabled direct links (run it with --p2p)");
            return;
        }
        if (theirs == null) {
            reply(session, "* " + target.nick() + " has not enabled direct links");
            return;
        }
        // Both sides can only authenticate each other if both brought a token.
        // If either did not, the introduction falls back to the plain one.
        boolean mutual = !session.linkToken().isEmpty() && !target.linkToken().isEmpty();
        String theirToken = mutual ? target.linkToken() : "";
        String myToken = mutual ? session.linkToken() : "";
        // The dialer gets who it is, who to reach, where, and the secret the
        // peer must present; the target is told who is about to connect and the
        // secret it must expect. Control lines are prefixed so that a relayed
        // chat line can never be mistaken for one.
        reply(session, CONTROL + "link " + session.nick() + " " + target.nick() + " " + theirs + " " + theirToken);
        reply(target, CONTROL + "link-incoming " + session.nick() + " " + mine + " " + myToken);
    }

    // ---- delivery -------------------------------------------------------

    private void reply(Session session, String line) {
        try {
            session.send(line);
        } catch (IOException e) {
            disconnect(session);
        }
    }

    private void broadcast(Room room, String line) {
        broadcastExcept(room, line, null);
    }

    private void broadcastExcept(Room room, String line, Session except) {
        for (Session session : room.members()) {
            if (session == except) {
                continue;
            }
            try {
                session.send(line);
            } catch (IOException e) {
                disconnect(session);
            }
        }
    }

    // ---- lookup and naming ---------------------------------------------

    private Room room(String name) {
        return rooms.computeIfAbsent(name, Room::new);
    }

    private Session find(String nick) {
        Session exact = sessions.get(nick);
        if (exact != null) {
            return exact;
        }
        for (Session session : sessions.values()) {
            if (session.nick().equalsIgnoreCase(nick)) {
                return session;
            }
        }
        return null;
    }

    /** Reserves a nick for a new session; appends a digit if the base is taken. */
    private String uniqueNickLocked(String requested) {
        String base = cleanNick(requested);
        if (base.isEmpty()) {
            base = "anon";
        }
        if (!sessions.containsKey(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String suffix = Integer.toString(i);
            String head = base.length() + suffix.length() > MAX_NICK_LENGTH
                    ? base.substring(0, MAX_NICK_LENGTH - suffix.length())
                    : base;
            String candidate = head + suffix;
            if (!sessions.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    /** Keeps only name-legal characters, then truncates. Used for the first line. */
    private static String cleanNick(String raw) {
        Matcher matcher = NAME_CHARS.matcher(raw.strip());
        StringBuilder built = new StringBuilder();
        while (matcher.find()) {
            built.append(matcher.group());
        }
        return built.length() > MAX_NICK_LENGTH ? built.substring(0, MAX_NICK_LENGTH) : built.toString();
    }

    private static boolean validName(String raw, int maxLength) {
        return raw.length() <= maxLength && VALID_NAME.matcher(raw).matches();
    }

    private static String cleanRoomName(String raw) {
        String name = raw.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("#")) {
            name = name.substring(1);
        }
        if (name.isEmpty() || name.length() > MAX_ROOM_NAME_LENGTH) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return null;
            }
        }
        return name;
    }

    /** Accepts {@code host:port}; returns a normalized form, or null if malformed. */
    private static String cleanAddress(String raw) {
        String address = raw.strip();
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return address.substring(0, colon) + ":" + port;
    }

    /** A secret is hex, and short enough to be a line's worth. */
    private static boolean validToken(String token) {
        if (token.isEmpty() || token.length() > 64) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static String memberCount(Room room) {
        int n = room.size();
        return n + (n == 1 ? " member" : " members");
    }
}

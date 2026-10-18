import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal RFC 6455 WebSocket transport for the chat room, and the browser page
 * served to plain HTTP requests on the same port.
 *
 * <p>Deliberately hand-rolled: no dependencies, and nothing that the GraalVM
 * native image needs reflection configuration for. The protocol comes down to
 * an HTTP upgrade handshake followed by length-prefixed frames.
 */
final class ChatWebSocketServer {
    private static final String HOST = "127.0.0.1";

    /** RFC 6455 section 1.3: the value hashed with the client's key. */
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int MAX_HEADER_BYTES = 8 * 1024;
    /** Chat lines are tiny; anything larger is a client bug or an attack. */
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private static final int OPCODE_CONTINUATION = 0x0;
    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_BINARY = 0x2;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;
    private static final int OPCODE_PONG = 0xA;

    private static final int MAX_NAME_LENGTH = 32;

    /** The page a browser gets when it opens the port as plain HTTP. */
    private static final String PAGE = """
            <!doctype html>
            <meta charset="utf-8">
            <title>chat</title>
            <style>
              body { font: 15px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; margin: 1rem; }
              #log { border: 1px solid #ddd; border-radius: 4px; padding: .5rem; height: 60vh; overflow-y: auto; }
              form { margin-top: .5rem; }
              input { width: 100%; box-sizing: border-box; padding: .4rem; font: inherit; }
            </style>
            <h1>chat</h1>
            <div id="log"></div>
            <form id="form">
              <input id="text" autocomplete="off" placeholder="type a name, then messages" autofocus>
            </form>
            <script>
              const log = document.getElementById("log");
              const input = document.getElementById("text");
              function line(text) {
                const div = document.createElement("div");
                div.textContent = text;
                log.append(div);
                log.scrollTop = log.scrollHeight;
              }
              const proto = location.protocol === "https:" ? "wss:" : "ws:";
              const ws = new WebSocket(proto + "//" + location.host);
              ws.onopen = function () { line("(connected - type a name and press enter)"); };
              ws.onmessage = function (event) { line(event.data); };
              ws.onclose = function () { line("(disconnected - reload to reconnect)"); };
              document.getElementById("form").addEventListener("submit", function (event) {
                event.preventDefault();
                if (!input.value.trim()) return;
                ws.send(input.value);
                input.value = "";
              });
            </script>
            """;

    static void run(int port, Room room) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(HOST, port), 16);
        System.out.println("[server] listening on " + HOST + ":" + port + " (websocket)");
        System.out.println("[server] open http://" + HOST + ":" + port + " in a browser");
        while (true) {
            Socket conn = server.accept();
            // Virtual threads are always daemon threads.
            Thread.ofVirtual().name("ws-" + conn.getRemoteSocketAddress()).start(() -> handle(conn, room));
        }
    }

    private static void handle(Socket conn, Room room) {
        try {
            byte[] block = readHeaderBlock(conn.getInputStream());
            if (block == null) {
                conn.close();
                return;
            }
            HttpRequest request = parseRequest(block);
            if (!"GET".equals(request.method)) {
                writeAll(conn.getOutputStream(), "HTTP/1.1 405 Method Not Allowed\r\n"
                        + "Allow: GET\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n\r\n");
                conn.close();
                return;
            }
            String key = request.headers.get("sec-websocket-key");
            String upgrade = request.headers.getOrDefault("upgrade", "");
            if (key != null && !key.isBlank() && upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
                upgrade(conn, room, key, request.headers.get("sec-websocket-version"));
            } else {
                servePage(conn);
            }
        } catch (IOException ignored) {
        }
    }

    /** Reads up to and including the CRLFCRLF that ends the request headers. */
    private static byte[] readHeaderBlock(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            buf.write(b);
            state = nextHeaderState(state, b);
            if (state == 4) {
                return buf.toByteArray();
            }
        }
        return null;
    }

    /** Tracks progress through the terminating {@code \r\n\r\n}. */
    private static int nextHeaderState(int state, int b) {
        return switch (state) {
            case 0 -> b == '\r' ? 1 : 0;
            case 1 -> b == '\n' ? 2 : (b == '\r' ? 1 : 0);
            case 2 -> b == '\r' ? 3 : 0;
            case 3 -> b == '\n' ? 4 : (b == '\r' ? 1 : 0);
            default -> state;
        };
    }

    private static HttpRequest parseRequest(byte[] block) {
        HttpRequest request = new HttpRequest();
        String[] lines = new String(block, StandardCharsets.US_ASCII).split("\r\n");
        String[] start = lines[0].split(" ");
        request.method = start.length > 0 ? start[0] : "";
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                request.headers.put(
                        lines[i].substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).strip());
            }
        }
        return request;
    }

    private static void upgrade(Socket conn, Room room, String key, String version) throws IOException {
        OutputStream out = conn.getOutputStream();
        if (!"13".equals(version)) {
            writeAll(out, "HTTP/1.1 426 Upgrade Required\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n");
            conn.close();
            return;
        }
        String accept = Base64.getEncoder().encodeToString(sha1(key + WEBSOCKET_GUID));
        writeAll(out, "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
        converse(conn, room, out);
    }

    /** The frame loop: one virtual thread per connection, like the TCP transport. */
    private static void converse(Socket conn, Room room, OutputStream out) {
        String name = "anon";
        WsPeer peer = null;
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOpcode = 0;
        try {
            InputStream in = conn.getInputStream();
            while (true) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                if (frame.opcode == OPCODE_CLOSE) {
                    sendControl(out, OPCODE_CLOSE, frame.payload);
                    return;
                }
                if (frame.opcode == OPCODE_PING) {
                    sendControl(out, OPCODE_PONG, frame.payload);
                    continue;
                }
                if (frame.opcode == OPCODE_PONG) {
                    continue;
                }
                if (frame.opcode == OPCODE_TEXT || frame.opcode == OPCODE_BINARY) {
                    if (messageOpcode != 0) {
                        throw new WebSocketException(1002, "message started before the previous finished");
                    }
                    messageOpcode = frame.opcode;
                } else if (frame.opcode == OPCODE_CONTINUATION) {
                    if (messageOpcode == 0) {
                        throw new WebSocketException(1002, "continuation without a message");
                    }
                } else {
                    throw new WebSocketException(1002, "unsupported opcode " + frame.opcode);
                }

                if (message.size() + frame.payload.length > MAX_MESSAGE_BYTES) {
                    throw new WebSocketException(1009, "message too big");
                }
                message.write(frame.payload);
                if (!frame.fin) {
                    continue;
                }

                int opcode = messageOpcode;
                messageOpcode = 0;
                byte[] payload = message.toByteArray();
                message.reset();
                if (opcode != OPCODE_TEXT) {
                    continue; // binary messages are consumed, then ignored: the room is text-only
                }
                String text = cleanText(payload);
                if (text.isEmpty()) {
                    continue;
                }
                if (peer == null) {
                    // The first message is the display name, just like TCP/UDP.
                    name = text.length() > MAX_NAME_LENGTH ? text.substring(0, MAX_NAME_LENGTH) : text;
                    peer = new WsPeer(name, out);
                    room.join(peer);
                } else {
                    room.broadcast(name + ": " + text);
                }
            }
        } catch (WebSocketException e) {
            // Best effort: tell the client why before the socket closes.
            try {
                sendControl(out, OPCODE_CLOSE, closePayload(e.statusCode, e.getMessage()));
            } catch (IOException ignored) {
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

    private static Frame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first == -1) {
            return null;
        }
        int second = readByte(in);
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        if ((first & 0x70) != 0) {
            throw new WebSocketException(1002, "reserved bits set");
        }
        if ((second & 0x80) == 0) {
            throw new WebSocketException(1002, "client frames must be masked");
        }
        long length = second & 0x7F;
        if (length == 126) {
            length = readUnsigned(in, 2);
        } else if (length == 127) {
            length = readUnsigned(in, 8);
        }
        if (opcode >= OPCODE_CLOSE && (length > 125 || !fin)) {
            throw new WebSocketException(1002, "malformed control frame");
        }
        if (length > MAX_MESSAGE_BYTES) {
            throw new WebSocketException(1009, "message too big");
        }
        byte[] mask = new byte[4];
        readFully(in, mask, 0, mask.length);
        byte[] payload = new byte[(int) length];
        readFully(in, payload, 0, payload.length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, opcode, payload);
    }

    /** Server frames are never masked (RFC 6455 section 5.1). */
    private static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(0x80 | opcode);
        long length = payload.length;
        if (length < 126) {
            out.write((int) length);
        } else if (length < 0x10000) {
            out.write(126);
            out.write((int) (length >>> 8) & 0xFF);
            out.write((int) length & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (length >>> shift) & 0xFF);
            }
        }
        out.write(payload);
    }

    private static void sendControl(OutputStream out, int opcode, byte[] payload) throws IOException {
        synchronized (out) {
            writeFrame(out, opcode, payload);
            out.flush();
        }
    }

    private static byte[] closePayload(int statusCode, String reason) {
        byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + Math.min(text.length, 123)];
        payload[0] = (byte) (statusCode >>> 8);
        payload[1] = (byte) statusCode;
        System.arraycopy(text, 0, payload, 2, payload.length - 2);
        return payload;
    }

    private static String cleanText(byte[] payload) throws WebSocketException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketException(1007, "invalid utf-8");
        }
        // Keep the one-message-one-line invariant the other transports rely on.
        return text.strip().replace('\n', ' ').replace('\r', ' ');
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new WebSocketException(1002, "truncated frame");
        }
        return b;
    }

    private static long readUnsigned(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            value = (value << 8) | readByte(in);
        }
        return value;
    }

    private static void readFully(InputStream in, byte[] buf, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buf, offset + read, length - read);
            if (n == -1) {
                throw new WebSocketException(1002, "truncated frame");
            }
            read += n;
        }
    }

    private static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by RFC 6455 but unavailable", e);
        }
    }

    private static void servePage(Socket conn) {
        try {
            byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
            OutputStream out = conn.getOutputStream();
            writeAll(out, "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n");
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeAll(OutputStream out, String text) throws IOException {
        synchronized (out) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    /** A peer backed by a WebSocket connection. */
    private static final class WsPeer implements Peer {
        private final String name;
        private final OutputStream out;

        WsPeer(String name, OutputStream out) {
            this.name = name;
            this.out = out;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void send(String line) throws IOException {
            synchronized (out) {
                writeFrame(out, OPCODE_TEXT, line.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private static final class HttpRequest {
        String method = "";
        final Map<String, String> headers = new HashMap<>();
    }

    /** Carries the close status code RFC 6455 asks us to report. */
    private static final class WebSocketException extends IOException {
        final int statusCode;

        WebSocketException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}

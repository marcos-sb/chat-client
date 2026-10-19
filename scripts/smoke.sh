#!/bin/sh
# End-to-end checks for the chat client and server.
#
# Usage: scripts/smoke.sh
#
# Starts a server on a spare port, drives the real client through it, and
# checks what each side printed. The client is a black box here: every check
# asserts on what it printed.
#
# Set SMOKE_PORT to move the ports (the default 5200 is used plus 0..3).
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/_java.sh"
ROOT=$(dirname "$HERE")
BUILD="$ROOT/build"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/chat-smoke.XXXXXX")
BASE=${SMOKE_PORT:-5200}

PIDS=""
cleanup() {
    for pid in $PIDS; do
        kill "$pid" 2>/dev/null
    done
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    printf '  ok    %s\n' "$1"
}

bad() {
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s\n' "$1"
}

dump() {
    sed 's/^/          | /' "$1"
}

# wait_for <file> <needle> <tenths of a second>
wait_for() {
    i=0
    while [ "$i" -lt "$3" ]; do
        if grep -qF -- "$2" "$1" 2>/dev/null; then
            return 0
        fi
        sleep 0.1
        i=$((i + 1))
    done
    return 1
}

# check <description> <needle> <file> <tenths to wait>: waits, then asserts
check() {
    if ! wait_for "$3" "$2" "$4"; then
        bad "$1 -- never saw \"$2\""
        dump "$3"
        return
    fi
    ok "$1"
}

# refute <description> <needle> <file>: asserts the needle is absent
refute() {
    if grep -qF -- "$2" "$3" 2>/dev/null; then
        bad "$1 -- did not expect \"$2\""
        dump "$3"
    else
        ok "$1"
    fi
}

# start_server <port> <logfile> [transport flags]: prints the pid
start_server() {
    port=$1
    log=$2
    shift 2
    java -cp "$BUILD" ChatServer "$@" "$port" > "$log" 2>&1 &
    echo $!
}

printf 'building...\n'
"$HERE/build.sh" >/dev/null 2>&1 || {
    echo "build failed"
    exit 1
}

# ---------------------------------------------------------------- TCP -----
printf '\nTCP\n'
SERVER=$(start_server "$BASE" "$WORK/server.log")
PIDS="$PIDS $SERVER"
if ! wait_for "$WORK/server.log" "listening on" 100; then
    echo "server did not start"
    cat "$WORK/server.log"
    exit 1
fi

# alice speaks first, bob joins after her.
(
    printf 'alice\n'
    sleep 1
    printf 'hello room\n'
    sleep 1
    printf '/who\n'
    sleep 1
    printf '/rooms\n'
    sleep 1
    printf '/nick zoe\n'
    sleep 1
    printf '/msg bob a private word\n'
    sleep 2
) | java -cp "$BUILD" ChatClient "$BASE" > "$WORK/alice.log" 2>&1 &
(
    printf 'bob\n'
    sleep 8
) | java -cp "$BUILD" ChatClient "$BASE" > "$WORK/bob.log" 2>&1 &

check "client is welcomed" "* welcome to #lobby" "$WORK/alice.log" 100
check "room traffic reaches the other client" "alice: hello room" "$WORK/bob.log" 100
check "joins are announced" "* alice joined #lobby" "$WORK/bob.log" 100
check "/who lists the room" "*   bob" "$WORK/alice.log" 100
check "/rooms lists the lobby" "#lobby" "$WORK/alice.log" 100
check "/nick renames" "you are now known as \"zoe\"" "$WORK/alice.log" 100
check "/msg reaches the peer" "[dm] zoe: a private word" "$WORK/bob.log" 100
check "the server relays the private message" "[dm] to bob: a private word" "$WORK/alice.log" 100

# ---------------------------------------------------------------- flags ----
printf '\nFlags\n'
(
    printf 'a message in the named room\n'
    sleep 4
) | java -cp "$BUILD" ChatClient --name scripted --room dev "$BASE" > "$WORK/scripted.log" 2>&1 &
(
    sleep 1
    printf 'hello from the other side\n'
    sleep 4
) | java -cp "$BUILD" ChatClient --name other --room dev "$BASE" > "$WORK/other.log" 2>&1 &

check "the client joins the room it was given" "* joined #dev" "$WORK/scripted.log" 100
refute "the client does not prompt for a name" "Name: " "$WORK/scripted.log"
check "the named client is reachable in its room" "scripted: a message in the named room" "$WORK/other.log" 100
check "the other client is reachable in its room" "other: hello from the other side" "$WORK/scripted.log" 100

# ---------------------------------------------------------------- UDP -----
printf '\nUDP\n'
UDP=$(start_server $((BASE + 1)) "$WORK/udp-server.log" -u)
PIDS="$PIDS $UDP"
wait_for "$WORK/udp-server.log" "listening on" 100 || true

(
    printf 'carol\n'
    sleep 1
    printf 'hello over udp\n'
    sleep 3
) | java -cp "$BUILD" ChatClient -u $((BASE + 1)) > "$WORK/carol.log" 2>&1 &
(
    printf 'dave\n'
    sleep 6
) | java -cp "$BUILD" ChatClient -u $((BASE + 1)) > "$WORK/dave.log" 2>&1 &

check "udp client is welcomed" "* welcome to #lobby" "$WORK/carol.log" 100
check "udp traffic reaches the other client" "carol: hello over udp" "$WORK/dave.log" 100

# ---------------------------------------------------------- WebSocket -----
printf '\nWebSocket\n'
WS=$(start_server $((BASE + 2)) "$WORK/ws-server.log" -w)
PIDS="$PIDS $WS"
wait_for "$WORK/ws-server.log" "listening on" 100 || true

cat > "$WORK/WsCheck.java" <<'JAVA'
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Checks the browser page and the WebSocket handshake against the server. */
public class WsCheck {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);

        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String page = readAll(s.getInputStream());
            System.out.println(page.startsWith("HTTP/1.1 200") ? "PAGE-OK" : "PAGE-BAD");
            System.out.println(page.contains("<title>chat</title>") ? "TITLE-OK" : "TITLE-BAD");
        }

        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String key = Base64.getEncoder().encodeToString(keyBytes);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            System.out.println(head.toString().startsWith("HTTP/1.1 101") ? "WS-OK" : "WS-BAD");

            byte[] payload = "wsuser".getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            frame.write(0x80 | payload.length);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            out.write(frame.toByteArray());
            out.flush();

            int b1 = in.read();
            int b2 = in.read();
            int len = b2 & 0x7F;
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(data, off, len - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            String line = new String(data, 0, off, StandardCharsets.UTF_8);
            System.out.println(line.contains("welcome to #lobby") ? "WELCOME-OK" : "WELCOME-BAD " + line);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
JAVA

if javac -d "$WORK" "$WORK/WsCheck.java" 2>"$WORK/ws-compile.log"; then
    java -cp "$WORK" WsCheck $((BASE + 2)) > "$WORK/ws-check.log" 2>&1
    check "the browser page is served" "PAGE-OK" "$WORK/ws-check.log" 100
    check "the browser page is the chat page" "TITLE-OK" "$WORK/ws-check.log" 100
    check "the websocket handshake succeeds" "WS-OK" "$WORK/ws-check.log" 100
    check "the websocket client is welcomed" "WELCOME-OK" "$WORK/ws-check.log" 100
    check "the server sees the websocket client" "wsuser connected" "$WORK/ws-server.log" 100
else
    bad "the websocket check helper compiles"
    dump "$WORK/ws-compile.log"
fi

# -------------------------------------------------------------- p2p -------
printf '\nDirect (peer-to-peer) links\n'
P2P=$(start_server $((BASE + 3)) "$WORK/p2p-server.log")
PIDS="$PIDS $P2P"
wait_for "$WORK/p2p-server.log" "listening on" 100 || true

(
    printf 'pete\n'
    sleep 1
    printf '/link quinn\n'
    sleep 2
    printf '/msg quinn direct-hello\n'
    sleep 3
) | java -cp "$BUILD" ChatClient --p2p $((BASE + 3)) > "$WORK/pete.log" 2>&1 &
(
    printf 'quinn\n'
    sleep 8
) | java -cp "$BUILD" ChatClient --p2p $((BASE + 3)) > "$WORK/quinn.log" 2>&1 &

check "the client advertises its listener" "* direct links enabled at" "$WORK/pete.log" 100
check "the link is established" "* direct link to quinn established" "$WORK/pete.log" 100
check "the server brokers the introduction" "* pete is opening a direct link to you" "$WORK/quinn.log" 100
check "the peer receives the direct message" "[dm] pete: direct-hello" "$WORK/quinn.log" 100
refute "the server never saw the direct message" "direct-hello" "$WORK/p2p-server.log"

# ---------------------------------------------------------------- done ----
printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]

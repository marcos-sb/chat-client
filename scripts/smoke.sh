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
set +m # no job notifications when a background client finishes

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/_java.sh"
ROOT=$(dirname "$HERE")
BUILD="$ROOT/build"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/chat-smoke.XXXXXX")
BASE=${SMOKE_PORT:-5200}

PIDS=""
cleanup() {
    # The clients reconnect when their server goes, so they have to be stopped
    # too: one left over would join the next run's server.
    for pid in $PIDS; do
        kill "$pid" 2>/dev/null
    done
    sleep 0.5
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

# check_re <description> <pattern> <file>: waits for the pattern, then asserts
check_re() {
    if ! wait_for_re "$3" "$2" "$4"; then
        bad "$1 -- never saw /$2/"
        dump "$3"
        return
    fi
    ok "$1"
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

# wait_for_re <file> <pattern> <tenths of a second>
wait_for_re() {
    i=0
    while [ "$i" -lt "$3" ]; do
        if grep -qE -- "$2" "$1" 2>/dev/null; then
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

# Two helpers, compiled here and used below: one checks the browser page and
# the WebSocket handshake, the other tries to walk into a direct link without
# having been introduced.
mkdir -p "$WORK/helpers"
cat > "$WORK/helpers/WsCheck.java" <<'JAVA'
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

cat > "$WORK/helpers/StrangerCheck.java" <<'JAVA'
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Walks into a direct-link listener without having been introduced, and
 * reports whether it was let in.
 */
public class StrangerCheck {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            OutputStream out = s.getOutputStream();
            out.write("deadbeefdeadbeefdeadbeefdeadbeef mallory\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n < 0) {
                System.out.println("STRANGER-REFUSED");
            } else {
                System.out.println("STRANGER-ACCEPTED " + new String(buf, 0, n, StandardCharsets.UTF_8).trim());
            }
        } catch (SocketTimeoutException e) {
            System.out.println("STRANGER-HUNG");
        } catch (Exception e) {
            System.out.println("STRANGER-REFUSED");
        }
    }
}
JAVA

cat > "$WORK/helpers/QuietClient.java" <<'JAVA'
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Connects, gives a name, and then never reads: the server's worst client,
 * and the one that used to be able to hold the whole room up.
 */
public class QuietClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String name = args[1];
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setTcpNoDelay(true);
            OutputStream out = s.getOutputStream();
            out.write((name + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(60000);
        }
    }
}
JAVA

if javac -d "$WORK/helpers" "$WORK/helpers/WsCheck.java" "$WORK/helpers/StrangerCheck.java" \
        "$WORK/helpers/QuietClient.java" 2>"$WORK/compile.log"; then
    HELPERS=1
else
    HELPERS=0
    bad "the check helpers compile"
    dump "$WORK/compile.log"
fi

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
    sleep 1
    printf '/help\n'
    sleep 2
) | java -cp "$BUILD" ChatClient "$BASE" > "$WORK/alice.log" 2>&1 &
PIDS="$PIDS $!"
(
    printf 'bob\n'
    sleep 8
) | java -cp "$BUILD" ChatClient "$BASE" > "$WORK/bob.log" 2>&1 &
PIDS="$PIDS $!"

check "client is welcomed" "* welcome to #lobby" "$WORK/alice.log" 100
check "room traffic reaches the other client" "alice: hello room" "$WORK/bob.log" 100
check "joins are announced" "* alice joined #lobby" "$WORK/bob.log" 100
check "/who lists the room" "*   bob" "$WORK/alice.log" 100
check "/rooms lists the lobby" "#lobby" "$WORK/alice.log" 100
check "/nick renames" "you are now known as \"zoe\"" "$WORK/alice.log" 100
check "/msg reaches the peer" "[dm] zoe: a private word" "$WORK/bob.log" 100
check "the server relays the private message" "[dm] to bob: a private word" "$WORK/alice.log" 100
check "/help lists the client commands" "* client commands:" "$WORK/alice.log" 100
check "/help still lists the server commands" "*   /join <room>      join or create a room" "$WORK/alice.log" 100

# ---------------------------------------------------------------- flags ----
printf '\nFlags\n'
(
    printf 'a message in the named room\n'
    sleep 4
) | java -cp "$BUILD" ChatClient --name scripted --room dev "$BASE" > "$WORK/scripted.log" 2>&1 &
PIDS="$PIDS $!"
(
    sleep 1
    printf 'hello from the other side\n'
    sleep 4
) | java -cp "$BUILD" ChatClient --name other --room dev "$BASE" > "$WORK/other.log" 2>&1 &
PIDS="$PIDS $!"

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
PIDS="$PIDS $!"
(
    printf 'dave\n'
    sleep 6
) | java -cp "$BUILD" ChatClient -u $((BASE + 1)) > "$WORK/dave.log" 2>&1 &
PIDS="$PIDS $!"

check "udp client is welcomed" "* welcome to #lobby" "$WORK/carol.log" 100
check "udp traffic reaches the other client" "carol: hello over udp" "$WORK/dave.log" 100

# ---------------------------------------------------------- WebSocket -----
printf '\nWebSocket\n'
WS=$(start_server $((BASE + 2)) "$WORK/ws-server.log" -w)
PIDS="$PIDS $WS"
wait_for "$WORK/ws-server.log" "listening on" 100 || true

if [ "$HELPERS" = 1 ]; then
    java -cp "$WORK/helpers" WsCheck $((BASE + 2)) > "$WORK/ws-check.log" 2>&1
    check "the browser page is served" "PAGE-OK" "$WORK/ws-check.log" 100
    check "the browser page is the chat page" "TITLE-OK" "$WORK/ws-check.log" 100
    check "the websocket handshake succeeds" "WS-OK" "$WORK/ws-check.log" 100
    check "the websocket client is welcomed" "WELCOME-OK" "$WORK/ws-check.log" 100
    check "the server sees the websocket client" "wsuser connected" "$WORK/ws-server.log" 100
fi

# -------------------------------------------------------------- p2p -------
printf '\nDirect (peer-to-peer) links\n'
P2P=$(start_server $((BASE + 3)) "$WORK/p2p-server.log")
PIDS="$PIDS $P2P"
wait_for "$WORK/p2p-server.log" "listening on" 100 || true

(
    printf 'pete\n'
    sleep 1
    printf '/links\n'
    sleep 1
    printf '/link quinn\n'
    sleep 2
    printf '/links\n'
    sleep 1
    printf '/msg quinn direct-hello\n'
    # The rest waits for the check below to take the server away.
    while [ ! -f "$WORK/server-dead" ]; do
        sleep 0.2
    done
    printf '/msg quinn after-the-server-died\n'
    sleep 1
    printf '/unlink quinn\n'
    sleep 1
    printf '/links\n'
    sleep 1
    printf '/msg quinn after-the-unlink\n'
    sleep 3
) | java -cp "$BUILD" ChatClient --p2p $((BASE + 3)) > "$WORK/pete.log" 2>&1 &
PIDS="$PIDS $!"
(
    printf 'quinn\n'
    sleep 20
) | java -cp "$BUILD" ChatClient --p2p $((BASE + 3)) > "$WORK/quinn.log" 2>&1 &
PIDS="$PIDS $!"

check "the client advertises its listener" "* direct links enabled at" "$WORK/pete.log" 100
check "there are no links to begin with" "* no direct links" "$WORK/pete.log" 100
check "the link is established" "* direct link to quinn established" "$WORK/pete.log" 100
check "/links shows the open link" "* direct link to quinn (open)" "$WORK/pete.log" 100
check "the server brokers the introduction" "* pete is opening a direct link to you" "$WORK/quinn.log" 100
check "the peer receives the direct message" "[dm] pete: direct-hello" "$WORK/quinn.log" 100

# A process that finds the listener's address must not be able to walk in and
# claim a name: it has no secret the server ever announced. This runs while
# the client is still up and connected.
if [ "$HELPERS" = 1 ]; then
    LISTEN_PORT=$(sed -n 's/.*direct links enabled at 127.0.0.1:\([0-9]*\).*/\1/p' "$WORK/pete.log" | head -1)
    if [ -n "$LISTEN_PORT" ]; then
        java -cp "$WORK/helpers" StrangerCheck "$LISTEN_PORT" > "$WORK/stranger.log" 2>&1
        check "a stranger without an introduction is refused" "STRANGER-REFUSED" "$WORK/stranger.log" 100
        check "the client says it refused" "* refused a direct link" "$WORK/pete.log" 100
    else
        bad "the listener address is known"
    fi
fi

# Kill the server. A direct link must not need it, and a message with no link
# left cannot get through: together those prove the traffic really bypasses
# the server rather than being relayed behind the client's back.
kill "$P2P" 2>/dev/null
touch "$WORK/server-dead"
check "the direct message still arrives without the server" "[dm] pete: after-the-server-died" "$WORK/quinn.log" 100
check "/unlink closes the link" "* direct link to quinn closed" "$WORK/pete.log" 100
check "the peer sees the link go away" "* direct link with pete closed" "$WORK/quinn.log" 100
refute "a message with no link and no server does not arrive" "after-the-unlink" "$WORK/quinn.log"


# ------------------------------------------------------- reconnecting -------
printf '\nReconnecting\n'
RE=$(start_server $((BASE + 4)) "$WORK/re-server.log")
PIDS="$PIDS $RE"
wait_for "$WORK/re-server.log" "listening on" 100 || true

(
    sleep 1
    printf 'before the restart\n'
    # The check below takes the server away; this message has to wait for it.
    while [ ! -f "$WORK/server-away" ]; do
        sleep 0.2
    done
    printf 'while the server was away\n'
    sleep 6
) | java -cp "$BUILD" ChatClient --name resilient "$((BASE + 4))" > "$WORK/resilient.log" 2>&1 &
PIDS="$PIDS $!"

check "the client is welcomed" "* welcome to #lobby" "$WORK/resilient.log" 100
check "the first message is echoed back" "resilient: before the restart" "$WORK/resilient.log" 100

# Take the server away and put it back on the same port.
kill "$RE" 2>/dev/null
while kill -0 "$RE" 2>/dev/null; do
    sleep 0.1
done
touch "$WORK/server-away"
check "the client notices the server is gone" "* the server connection was lost" "$WORK/resilient.log" 100
check "a message sent while it is gone waits" "* the server is away" "$WORK/resilient.log" 100
sleep 1
RE=$(start_server $((BASE + 4)) "$WORK/re-server2.log")
PIDS="$PIDS $RE"
if ! wait_for "$WORK/re-server2.log" "listening on" 100; then
    bad "the server comes back on the same port"
    dump "$WORK/re-server2.log"
fi
check "the client comes back" "* reconnected to the server" "$WORK/resilient.log" 100
check "the waiting message goes out" "resilient: while the server was away" "$WORK/resilient.log" 100

# ------------------------------------------------------- slow clients ------
printf '\nSlow clients\n'
SLOW=$(start_server $((BASE + 5)) "$WORK/slow-server.log")
PIDS="$PIDS $SLOW"
wait_for "$WORK/slow-server.log" "listening on" 100 || true

# A client that never reads: the server used to block the whole room on it.
if [ "$HELPERS" = 1 ]; then
    java -cp "$WORK/helpers" QuietClient $((BASE + 5)) quiet > "$WORK/quiet.log" 2>&1 &
    PIDS="$PIDS $!"
fi
(
    printf 'noisy\n'
    sleep 1
    # Long lines, so the backlog builds up quickly.
    yes "$(awk 'BEGIN { s = ""; for (i = 0; i < 1000; i++) s = s "x"; print s }')" | head -5000
    touch "$WORK/flooded"
    sleep 3
) | java -cp "$BUILD" ChatClient "$((BASE + 5))" > "$WORK/noisy.log" 2>&1 &
PIDS="$PIDS $!"
(
    while [ ! -f "$WORK/flooded" ]; do
        sleep 0.2
    done
    printf 'still here\n'
    sleep 3
) | java -cp "$BUILD" ChatClient --name other "$((BASE + 5))" > "$WORK/other2.log" 2>&1 &
PIDS="$PIDS $!"

check "the room keeps going while a client does not read" "other: still here" "$WORK/other2.log" 100
check "a client that cannot keep up is dropped" "quiet left #lobby" "$WORK/slow-server.log" 100

# ---------------------------------------------------------------- style ----
printf '\nStyle\n'
STYLE=$(start_server $((BASE + 6)) "$WORK/style-server.log")
PIDS="$PIDS $STYLE"
wait_for "$WORK/style-server.log" "listening on" 100 || true

(
    printf 'hi\n'
    sleep 12 # then stay quiet, so a private message finds us idle
) | java -cp "$BUILD" ChatClient --name styled --color always --timestamps "$((BASE + 6))" > "$WORK/styled.log" 2>&1 &
PIDS="$PIDS $!"
(
    sleep 8 # long enough that the other client counts as away
    printf '/msg styled a private word\n'
    sleep 6
) | java -cp "$BUILD" ChatClient --name sender --color never "$((BASE + 6))" > "$WORK/sender.log" 2>&1 &
PIDS="$PIDS $!"

ESC=$(printf '\033')
BEL=$(printf '\007')
check "private messages are coloured" "$ESC[35m" "$WORK/styled.log" 100
check "the bell rings for a private message" "$BEL" "$WORK/styled.log" 100
check_re "lines are stamped with the time" "^[0-9][0-9]:[0-9][0-9]:[0-9][0-9] " "$WORK/styled.log" 100
refute "no colour when it is turned off" "$ESC" "$WORK/sender.log"

(
    printf 'a plain line\n'
    sleep 6
) | java -cp "$BUILD" ChatClient --name rawclient --raw "$((BASE + 6))" > "$WORK/raw.log" 2>&1 &
PIDS="$PIDS $!"
refute "raw mode keeps stdout plain" "$ESC" "$WORK/raw.log"
refute "raw mode does not ask for a name on stdout" "Name: " "$WORK/raw.log"
check "raw mode still shows the chat" "rawclient: a plain line" "$WORK/raw.log" 100

# ---------------------------------------------------------------- done ----
printf '\n%s passed, %s failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]

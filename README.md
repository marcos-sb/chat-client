# chat-client

The Java client for a small chat protocol. The first line sent is the display
name; every later line typed is broadcast to the sender's room as
`name: message`, unless it starts with `/`, in which case it is a command
(`/join`, `/nick`, `/msg`, …). Supports both TCP and UDP.

The matching Java server now lives alongside it in `server/` (moved here from
the `test` repository). The benchmark harness and design docs stay in `test`.

## Build & run

```sh
# compile everything into build/
scripts/build.sh

# server — TCP on port 5000 by default
scripts/server.sh
scripts/server.sh 5100     # custom port
scripts/server.sh -u       # UDP
scripts/server.sh -w       # WebSocket, plus a browser page on the same port

# client — run one per person, in separate terminals
scripts/client.sh
scripts/client.sh 5100
scripts/client.sh -u
scripts/client.sh --p2p     # opens a listener for direct (peer-to-peer) links
scripts/client.sh --name alice --room dev   # no prompt, straight into #dev
```

The run scripts take the same arguments as the classes and recompile
automatically if a source file is newer than its `.class`. They find a JDK via
`JAVA_HOME`, then `PATH`, then a local SDKMAN install.

`scripts/smoke.sh` runs the whole thing end to end: it starts a server on a
spare port, drives the real client through it over TCP, UDP and WebSocket, and
checks what each side printed. It exits non-zero if anything fails.

```sh
scripts/smoke.sh              # 41 checks, about a minute
SMOKE_PORT=5300 scripts/smoke.sh
```

Prefer the raw commands? They're equivalent:

```sh
javac -d build ChatClient.java server/ChatServer.java
java -cp build ChatServer
java -cp build ChatClient
```

Usage: `java ChatClient [-u] [-p|--p2p] [-n|--name <name>] [-r|--room <room>] [port]`.
On start it prompts `Name: `, sends the name, then prints incoming lines while
forwarding your input to the room. With `--name` the prompt is skipped and that
name is used instead, and with `--room` the client joins that room as soon as
it connects, so the client can be driven from a script. If the server isn't
running it exits with:

```
[client] cannot connect to 127.0.0.1:<port> — is the server running?
```

If the server goes away *after* the client has connected, the client stays up
and reconnects on its own, with a backoff that grows to five seconds. It says
so when the connection drops and again when it is back, and it registers
itself again each time — the name, the room, and the address it accepts direct
links on. The listener for direct links is not reopened, so links a peer
already has keep working across a restart of the server. A line typed while
the server is away waits for it and goes out once it is back.

The server speaks the same protocol, supports TCP and UDP
(`java ChatServer [-u] [port]`), and caps names at 32 characters (default
`anon`). Each connection is served by a virtual thread, so the server holds many
thousands of idle clients without one OS thread per client (requires JDK 21+;
developed on JDK 24). Names are cleaned of anything but letters, digits, and
`. _ -`, and made unique: asking for a taken name gets you a numeric suffix.

Lines going out to a client are queued and written by a thread of their own,
so one client that stops reading cannot hold up the room: the room carries on
and the backlog sits in that client's queue. A client with more than two
megabytes of backlog is dropped, since it is never going to catch up. The cost
is about six microseconds of extra delay on a single message; the trade buys
roughly a quarter more throughput and a room that cannot be held hostage.

### WebSocket

`scripts/server.sh -w` speaks RFC 6455 WebSocket and serves a small browser page
on the same port, so opening `http://127.0.0.1:5000` gives you a chat client
with no extra tooling. The first message is the display name, as with TCP/UDP.
The server runs one transport at a time; the WebSocket layer is hand-rolled, so
there are no dependencies and the GraalVM native build needs no configuration.

## Commands and rooms

Everyone starts in `#lobby`. A line beginning with `/` is a command:

| Command | Effect |
| --- | --- |
| `/help` | list the commands |
| `/nick <name>` | change your name |
| `/join <room>` | join a room, creating it if needed (a leading `#` is optional) |
| `/leave` | return to `#lobby` |
| `/rooms` | list rooms and their member counts |
| `/who` | list who is in your current room |
| `/msg <name> <text>` | private message (aliases `/dm`, `/whisper`) |
| `/link <name>` | ask the server to introduce you for a direct link (see below, TCP only) |
| `/links` | list the direct links you hold (client-side) |
| `/unlink <name>` | close a direct link (client-side) |
| `/me <action>` | emote (`* alice waves`) |
| `/quit` | disconnect |

Anything else is sent to your room. Room names are lowercased and limited to
letters, digits, and `. _ -`. Joins, parts, and nick changes are announced to the
affected rooms (`* alice joined #dev`), and empty non-default rooms are dropped.
All three transports share this behavior because they all route through
`server/Hub.java`.

`/links` and `/unlink` are answered by the client itself, not the server, so
they work even when the server is out of reach; `/help` lists them above the
server's own list.

## Direct (peer-to-peer) messages

Run a TCP client with `--p2p` and it opens a local listener, then advertises it
to the server with `/listen`. When two such clients are on the same server,
either can run `/link <name>`: the server hands each side the other's address,
the requester dials directly, and from then on `/msg` to that peer goes over the
direct connection instead of through the server. `/msg` to anyone else still
uses the server relay, so it degrades gracefully.

```sh
scripts/client.sh --p2p     # run one per person, in separate terminals
```

Once a link is up, `/links` lists the links you hold and `/unlink <name>`
closes one, after which messages to that peer go through the server again.
Both are answered by the client, so they work even if the server has gone
away.

The two ends speak the same line protocol they use with the server — the first
line is the sender's nick, every later line is a message — so a direct link is
a plain TCP connection between the two clients.

The server only brokers the introduction; it never sees the direct traffic. It
also never dials anything itself: it hands over the address each side
advertised and lets the two clients do it.

Introductions travel as *control lines*, which the server prefixes with a
control character. Relayed chat always starts with the sender's nick, and nicks
are cleaned down to letters, digits and `. _ -`, so no message a user types can
look like a control line and make another client dial somewhere. The browser
page and the UDP client never get control lines at all, and a client that was
not started with `--p2p` ignores them.

Each client also makes up a random secret when it starts and registers it with
`/listen`. The server passes it to the peer as part of the introduction, and
the two ends use it to identify each other: the peer will not let in a
connection that cannot present the secret it was told to expect, and it will
not talk to a peer that answers with the wrong one. So a process that finds the
listener's address cannot walk in and claim somebody's name.

This is a loopback demonstration, not a secure design: the advertised address is
trusted as-is, there is no NAT traversal, the secret is only as good as the
server that hands it out, and the direct channel carries the text in the
clear, so a local process can still read the traffic. The UDP client and the
browser page cannot hold a listener, so p2p applies to the TCP client only.

## Native build (optional, GraalVM)

```sh
native-image -cp build ChatClient build/chatclient
native-image -cp build ChatServer build/chatserver
```

The scripts always run the JVM classes; run `build/chatclient` and
`build/chatserver` directly if you want the native binaries' faster startup.

## Layout

```
ChatClient.java                  the client
server/ChatServer.java           the server CLI + TCP and UDP transports
server/ChatWebSocketServer.java  hand-rolled RFC 6455 transport + browser page
server/Hub.java                  the rooms, the nicks, and the commands
server/Session.java              one participant: transport + nick + current room
server/Peer.java                 a transport sink: where a line is delivered
server/Room.java                 a named room: the set of its members
scripts/                         build.sh, server.sh, client.sh
build/                           compiled classes + native binaries (gitignored)
```

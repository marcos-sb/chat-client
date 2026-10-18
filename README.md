# chat-client

The Java client for a small single-room chat protocol. The first line sent is
the display name; every later line typed is broadcast to the room as
`name: message`. Supports both TCP and UDP.

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
```

The run scripts take the same `[-u] [port]` arguments as the classes and
recompile automatically if a source file is newer than its `.class`. They find
a JDK via `JAVA_HOME`, then `PATH`, then a local SDKMAN install.

Prefer the raw commands? They're equivalent:

```sh
javac -d build ChatClient.java server/ChatServer.java
java -cp build ChatServer
java -cp build ChatClient
```

Usage: `java ChatClient [-u] [port]`. On start it prompts `Name: `, sends the
name, then prints incoming lines while forwarding your input to the room. If
the server isn't running it exits with:

```
[client] cannot connect to 127.0.0.1:<port> — is the server running?
```

The server speaks the same protocol, caps names at 32 chars (default `anon`),
and supports TCP and UDP (`java ChatServer [-u] [port]`). Each connection is
served by a virtual thread, so the server holds many thousands of idle clients
without one OS thread per client (requires JDK 21+; developed on JDK 24).

### WebSocket

`scripts/server.sh -w` speaks RFC 6455 WebSocket and serves a small browser page
on the same port, so opening `http://127.0.0.1:5000` gives you a chat client
with no extra tooling. The first message is the display name, as with TCP/UDP.
The server runs one transport at a time; the WebSocket layer is hand-rolled, so
there are no dependencies and the GraalVM native build needs no configuration.

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
server/Peer.java                 one participant, whatever the transport
server/Room.java                 the shared room: peers and broadcast
scripts/                         build.sh, server.sh, client.sh
build/                           compiled classes + native binaries (gitignored)
```

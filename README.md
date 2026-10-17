# chat-client

The Java client for a small single-room chat protocol. The first line sent is
the display name; every later line typed is broadcast to the room as
`name: message`. Supports both TCP and UDP.

The matching Java server now lives alongside it in `server/` (moved here from
the `test` repository). The benchmark harness and design docs stay in `test`.

## Build & run

```sh
# client
javac -d build ChatClient.java

# server
javac -d build server/ChatServer.java

# client: TCP (default port 5000) — pass a port to override
java -cp build ChatClient
java -cp build ChatClient 5100

# client: UDP
java -cp build ChatClient -u
java -cp build ChatClient -u 5100

# server: TCP (default port 5000), then UDP
java -cp build ChatServer
java -cp build ChatServer -u
```

Usage: `java ChatClient [-u] [port]`. On start it prompts `Name: `, sends the
name, then prints incoming lines while forwarding your input to the room. If
the server isn't running it exits with:

```
[client] cannot connect to 127.0.0.1:<port> — is the server running?
```

The server speaks the same protocol, caps names at 32 chars (default `anon`),
and supports TCP and UDP (`java ChatServer [-u] [port]`).

## Native build (optional, GraalVM)

```sh
native-image -cp build ChatClient build/chatclient
native-image -cp build ChatServer build/chatserver
```

## Layout

```
ChatClient.java       the client
server/ChatServer.java the server (TCP + UDP)
build/                compiled classes + native binaries (gitignored)
```

# chat-client

The Java client for a small single-room chat protocol. The first line sent is
the display name; every later line typed is broadcast to the room as
`name: message`. Supports both TCP and UDP.

The matching server and the rest of the project live in a separate repository.

## Build & run

```sh
javac -d build ChatClient.java

# TCP (default port 5000) — pass a port to override
java -cp build ChatClient
java -cp build ChatClient 5100

# UDP
java -cp build ChatClient -u
java -cp build ChatClient -u 5100
```

Usage: `java ChatClient [-u] [port]`. On start it prompts `Name: `, sends the
name, then prints incoming lines while forwarding your input to the room. If
the server isn't running it exits with:

```
[client] cannot connect to 127.0.0.1:<port> — is the server running?
```

## Native build (optional, GraalVM)

```sh
native-image -cp build ChatClient build/chatclient
```

## Layout

```
ChatClient.java   the client
build/            compiled classes + native binary (gitignored)
```

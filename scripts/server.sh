#!/bin/sh
# Run the chat server, compiling first if needed.
# Usage: scripts/server.sh [-u] [port]      (default: TCP on port 5000)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/_java.sh"
ROOT=$(dirname "$HERE")
BUILD="$ROOT/build"

if [ ! -f "$BUILD/ChatServer.class" ] || [ "$ROOT/server/ChatServer.java" -nt "$BUILD/ChatServer.class" ]; then
    "$HERE/build.sh"
fi

exec java -cp "$BUILD" ChatServer "$@"

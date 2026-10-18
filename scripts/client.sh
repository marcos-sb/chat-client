#!/bin/sh
# Run the chat client, compiling first if needed.
# Usage: scripts/client.sh [-u] [port]      (default: TCP to port 5000)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/_java.sh"
ROOT=$(dirname "$HERE")
BUILD="$ROOT/build"

if [ ! -f "$BUILD/ChatClient.class" ] || [ "$ROOT/ChatClient.java" -nt "$BUILD/ChatClient.class" ]; then
    "$HERE/build.sh"
fi

exec java -cp "$BUILD" ChatClient "$@"

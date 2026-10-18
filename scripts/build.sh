#!/bin/sh
# Compile the chat client and server into build/.
# Usage: scripts/build.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/_java.sh"
ROOT=$(dirname "$HERE")
BUILD="$ROOT/build"

mkdir -p "$BUILD"
javac -d "$BUILD" "$ROOT/ChatClient.java" "$ROOT/server/ChatServer.java"
echo "compiled client + server into build/" >&2

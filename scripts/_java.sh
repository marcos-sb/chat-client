# Resolve a working java/javac onto PATH. Sourced by the other scripts.
#
# Order: JAVA_HOME, then whatever is already on PATH, then a common SDKMAN
# install. Set JAVA_HOME (or put java on PATH) to override.
#
# Note: on macOS /usr/bin/java is a stub that exists but fails when run, so a
# bare `command -v java` is not enough — we actually execute it.
java_works() {
    "$1" -version >/dev/null 2>&1
}

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_works "$JAVA_HOME/bin/java"; then
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi

if ! (command -v java >/dev/null 2>&1 && java_works java) \
   && [ -x "$HOME/.sdkman/candidates/java/current/bin/java" ] \
   && java_works "$HOME/.sdkman/candidates/java/current/bin/java"; then
    PATH="$HOME/.sdkman/candidates/java/current/bin:$PATH"
    export PATH
fi

if ! (command -v java >/dev/null 2>&1 && java_works java); then
    echo "error: no working 'java' found — install a JDK or set JAVA_HOME" >&2
    exit 1
fi

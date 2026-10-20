#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# jpackage has no BSD backend; a JVM distribution uses BSD's own JDK instead.
exec sh "$SCRIPT_DIR/install-linux.sh" --portable "$@"

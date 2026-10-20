#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
. "$SCRIPT_DIR/common.sh"

launcher_rebuild=0
if [ "${1:-}" = '--rebuild' ]; then launcher_rebuild=1; shift; fi
launcher_native=$LAUNCHER_ROOT/build/native/Blockbox\ Launcher/bin/Blockbox\ Launcher
if [ "$(uname -s)" = Darwin ]; then launcher_native=$LAUNCHER_ROOT/build/native/Blockbox\ Launcher.app/Contents/MacOS/Blockbox\ Launcher; fi
if [ -x "$launcher_native" ]; then
  if [ "$launcher_rebuild" -eq 1 ]; then launcher_build standaloneJar packageApp; fi
  exec "$launcher_native" "$@"
fi
if [ "$launcher_rebuild" -eq 1 ] || [ ! -f "$LAUNCHER_ROOT/build/standalone/blockbox-launcher.jar" ]; then
  launcher_build standaloneJar
fi
launcher_java
# Launches after the first build never invoke Gradle.
exec "$JAVA_COMMAND" -Xms32m -Xmx256m -jar "$LAUNCHER_ROOT/build/standalone/blockbox-launcher.jar" "$@"

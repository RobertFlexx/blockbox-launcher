#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
. "$SCRIPT_DIR/common.sh"
[ "$(uname -s)" = Darwin ] || { printf '%s\n' 'Run this installer on macOS.' >&2; exit 1; }
launcher_rebuild=0
if [ "${1:-}" = --rebuild ]; then launcher_rebuild=1; shift; fi
[ "$#" -eq 0 ] || { printf '%s\n' 'Usage: install-macos.sh [--rebuild]' >&2; exit 1; }
if [ "$launcher_rebuild" -eq 1 ] || [ ! -x "$LAUNCHER_ROOT/build/native/Blockbox Launcher.app/Contents/MacOS/Blockbox Launcher" ]; then launcher_build packageApp; fi
APP_DIR=${BLOCKBOX_APPLICATIONS_DIR:-$HOME/Applications}
mkdir -p "$APP_DIR"
launcher_stage=$(mktemp -d "$APP_DIR/.blockbox-install.XXXXXX")
trap 'if [ -d "$launcher_stage" ]; then rm -rf -- "$launcher_stage"; fi' EXIT HUP INT TERM
ditto "$LAUNCHER_ROOT/build/native/Blockbox Launcher.app" "$launcher_stage/Blockbox Launcher.app"
if [ -e "$APP_DIR/Blockbox Launcher.app.previous" ]; then printf '%s\n' 'An earlier installation backup exists. Move it aside before retrying.' >&2; exit 1; fi
if [ -e "$APP_DIR/Blockbox Launcher.app" ]; then mv "$APP_DIR/Blockbox Launcher.app" "$APP_DIR/Blockbox Launcher.app.previous"; fi
if ! mv "$launcher_stage/Blockbox Launcher.app" "$APP_DIR/Blockbox Launcher.app"; then
  if [ -e "$APP_DIR/Blockbox Launcher.app.previous" ]; then mv "$APP_DIR/Blockbox Launcher.app.previous" "$APP_DIR/Blockbox Launcher.app"; fi
  exit 1
fi
if [ -d "$APP_DIR/Blockbox Launcher.app.previous" ]; then rm -rf -- "$APP_DIR/Blockbox Launcher.app.previous"; fi
printf 'Installed: %s/Blockbox Launcher.app\nOpen the app from Finder. Java and Gradle are not needed at startup.\n' "$APP_DIR"

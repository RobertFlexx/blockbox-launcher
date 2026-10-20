#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
. "$SCRIPT_DIR/common.sh"

INSTALL_PREFIX=${BLOCKBOX_INSTALL_PREFIX:-$HOME/.local}
launcher_portable=0
launcher_rebuild=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --portable) launcher_portable=1; shift ;;
    --rebuild) launcher_rebuild=1; shift ;;
    --prefix) [ "$#" -ge 2 ] || { printf '%s\n' '--prefix requires a directory' >&2; exit 1; }; INSTALL_PREFIX=$2; shift 2 ;;
    *) printf '%s\n' 'Usage: install-linux.sh [--portable] [--rebuild] [--prefix DIRECTORY]' >&2; exit 1 ;;
  esac
done
case "$INSTALL_PREFIX" in /*) ;; *) printf '%s\n' 'Installation prefix must be an absolute path.' >&2; exit 1;; esac

APP_ROOT=$INSTALL_PREFIX/share/blockbox-launcher-app
BIN_DIR=$INSTALL_PREFIX/bin
DESKTOP_DIR=$INSTALL_PREFIX/share/applications
mkdir -p "$BIN_DIR" "$DESKTOP_DIR" "$INSTALL_PREFIX/share"

if [ "$launcher_portable" -eq 0 ]; then
  launcher_source=$LAUNCHER_ROOT/build/native/Blockbox\ Launcher
  if [ "$launcher_rebuild" -eq 1 ] || [ ! -x "$launcher_source/lib/runtime/bin/java" ] || [ ! -f "$launcher_source/lib/app/blockbox-launcher.jar" ]; then launcher_build packageApp; fi
  launcher_exec=lib/runtime/bin/java
else
  launcher_source=$LAUNCHER_ROOT/build/install/blockbox-launcher
  if [ "$launcher_rebuild" -eq 1 ] || [ ! -f "$launcher_source/bin/blockbox-launcher" ]; then launcher_build installDist; fi
  launcher_java
  launcher_exec=bin/blockbox-launcher
fi

# Stage the complete update first. User data lives elsewhere and is never moved.
launcher_stage=$(mktemp -d "$INSTALL_PREFIX/share/.blockbox-install.XXXXXX")
trap 'if [ -n "${launcher_stage:-}" ] && [ -d "$launcher_stage" ]; then rm -rf -- "$launcher_stage"; fi' EXIT HUP INT TERM
cp -R "$launcher_source/." "$launcher_stage/"
if [ -e "$APP_ROOT.previous" ]; then printf '%s\n' "An earlier install backup exists at $APP_ROOT.previous. Move it aside before retrying." >&2; exit 1; fi
if [ -e "$APP_ROOT" ]; then mv "$APP_ROOT" "$APP_ROOT.previous"; fi
if ! mv "$launcher_stage" "$APP_ROOT"; then
  if [ -e "$APP_ROOT.previous" ]; then mv "$APP_ROOT.previous" "$APP_ROOT"; fi
  exit 1
fi
launcher_stage=

# Build wrapper strings with printf and shell quoting, so spaces and apostrophes
# in installation paths remain data. Never embed unquoted project paths in code.
launcher_quote() { printf "'"; printf '%s' "$1" | sed "s/'/'\\\\''/g"; printf "'"; }
{
  printf '#!/usr/bin/env sh\nset -eu\n'
  if [ "$launcher_portable" -eq 1 ]; then
    # Portable installations use the selected system runtime, never Gradle.
    printf 'if [ -n "${BLOCKBOX_LAUNCHER_JAVA_HOME:-}" ]; then JAVA_HOME=$BLOCKBOX_LAUNCHER_JAVA_HOME; export JAVA_HOME; fi\n'
    if [ -n "${JAVA_HOME:-}" ]; then
      printf 'if [ -z "${JAVA_HOME:-}" ]; then JAVA_HOME='; launcher_quote "$JAVA_HOME"; printf '; export JAVA_HOME; fi\n'
    fi
  fi
  printf 'exec '; launcher_quote "$APP_ROOT/$launcher_exec"
  if [ "$launcher_portable" -eq 0 ]; then printf ' -Xms32m -Xmx256m -jar '; launcher_quote "$APP_ROOT/lib/app/blockbox-launcher.jar"; fi
  printf ' "$@"\n'
} > "$BIN_DIR/blockbox-launcher.new"
chmod +x "$BIN_DIR/blockbox-launcher.new"
mv "$BIN_DIR/blockbox-launcher.new" "$BIN_DIR/blockbox-launcher"

# Desktop Exec has its own quoting rules; it is not interpreted by a shell.
launcher_desktop_exec=$(printf '%s' "$BIN_DIR/blockbox-launcher" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g; s/%/%%/g')
cat > "$DESKTOP_DIR/blockbox-launcher.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Blockbox Launcher
Comment=Manage and play Blockbox instances
Exec="$launcher_desktop_exec"
Icon=applications-games
Terminal=false
Categories=Game;
StartupWMClass=blockbox-launcher-MainKt
StartupNotify=true
EOF
if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$DESKTOP_DIR" >/dev/null 2>&1 || true; fi
if [ -d "$APP_ROOT.previous" ]; then rm -rf -- "$APP_ROOT.previous"; fi
printf 'Installed: %s\nLaunch from your application menu or run blockbox-launcher. Gradle is not used at startup.\n' "$BIN_DIR/blockbox-launcher"

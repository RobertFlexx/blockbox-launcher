#!/usr/bin/env sh
set -eu

launcher_fail() { printf '%s\n' "$*" >&2; exit 1; }
launcher_usage() {
  printf '%s\n' 'Usage: install-macos.sh [--from-source | --rebuild]' \
    'By default, download and install the latest macOS release (no build tools needed).' \
    '  --from-source  Build/copy the app from a complete source checkout.' \
    '  --rebuild      Force a fresh source build.' \
    'Set BLOCKBOX_APPLICATIONS_DIR to change the destination (default: ~/Applications).'
}
launcher_source_install=0
launcher_rebuild=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --from-source) launcher_source_install=1 ;;
    --rebuild) launcher_source_install=1; launcher_rebuild=1 ;;
    -h|--help) launcher_usage; exit 0 ;;
    *) launcher_usage >&2; exit 1 ;;
  esac
  shift
done
[ "$(uname -s)" = Darwin ] || launcher_fail 'Run this installer on macOS.'
APP_DIR=${BLOCKBOX_APPLICATIONS_DIR:-$HOME/Applications}
case "$APP_DIR" in /*) ;; *) launcher_fail 'BLOCKBOX_APPLICATIONS_DIR must be an absolute path.';; esac
launcher_target=$APP_DIR/Blockbox\ Launcher.app
if [ -e "$launcher_target.previous" ] || [ -L "$launcher_target.previous" ]; then
  launcher_fail "An earlier installation backup exists at $launcher_target.previous. Move it aside before retrying."
fi

launcher_work=
launcher_stage=
launcher_mount=
launcher_cleanup() {
  # Keep the download directory if detaching fails; never remove a mounted tree.
  if [ -n "$launcher_mount" ]; then
    if hdiutil detach "$launcher_mount" > /dev/null; then launcher_mount=; else
      printf 'Could not eject %s. Eject it before removing %s.\n' "$launcher_mount" "$launcher_work" >&2
    fi
  fi
  if [ -n "$launcher_stage" ]; then rm -rf -- "$launcher_stage"; fi
  if [ -n "$launcher_work" ] && [ -z "$launcher_mount" ]; then rm -rf -- "$launcher_work"; fi
}
trap launcher_cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

launcher_complete_app() {
  [ -x "$1/Contents/MacOS/Blockbox Launcher" ] &&
    [ -x "$1/Contents/runtime/Contents/Home/bin/java" ] &&
    [ -f "$1/Contents/app/blockbox-launcher.jar" ] &&
    [ -f "$1/Contents/app/game/blockbox-game.properties" ]
}

if [ "$launcher_source_install" -eq 1 ]; then
  [ -f "$0" ] || launcher_fail 'Source installation requires a checkout. Run ./scripts/install-macos.sh --from-source there.'
  SCRIPT_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
  LAUNCHER_ROOT=$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)
  [ -f "$SCRIPT_DIR/common.sh" ] && [ -f "$LAUNCHER_ROOT/build.gradle.kts" ] || launcher_fail 'Source installation requires a complete launcher checkout.'
  # shellcheck source=common.sh
  . "$SCRIPT_DIR/common.sh"
  launcher_source=$LAUNCHER_ROOT/build/native/Blockbox\ Launcher.app
  if [ "$launcher_rebuild" -eq 1 ] || ! launcher_complete_app "$launcher_source"; then
    printf '%s\n' 'Building from source requires JDK 21, Scala CLI, and a Blockbox game checkout.'
    launcher_build packageApp
  fi
else
  case "$(uname -m)" in
    arm64|aarch64) launcher_arch=arm64 ;;
    x86_64)
      # Use the native Apple Silicon build even when Terminal runs under Rosetta.
      if [ "$(sysctl -n hw.optional.arm64 2>/dev/null || true)" = 1 ]; then launcher_arch=arm64; else launcher_arch=x64; fi ;;
    *) launcher_fail 'Only Apple Silicon and Intel Macs are supported.' ;;
  esac
  launcher_asset=blockbox-macos-$launcher_arch.dmg
  launcher_releases=https://github.com/RobertFlexx/blockbox-launcher/releases
  # Resolve latest once so the DMG and checksum always come from the same release.
  launcher_release=$(curl -fsSL --retry 3 --connect-timeout 15 --output /dev/null --write-out '%{url_effective}' "$launcher_releases/latest") ||
    launcher_fail 'Could not find the latest Blockbox release. Check your connection and retry.'
  case "$launcher_release" in
    "$launcher_releases/tag/"?*) launcher_tag=${launcher_release#"$launcher_releases/tag/"} ;;
    *) launcher_fail "Unexpected release URL: $launcher_release" ;;
  esac
  launcher_download=$launcher_releases/download/$launcher_tag
  launcher_work=$(mktemp -d "${TMPDIR:-/tmp}/blockbox-download.XXXXXX")
  printf 'Downloading %s (%s)…\n' "$launcher_asset" "$launcher_tag"
  curl -fL --show-error --retry 3 --connect-timeout 15 --output "$launcher_work/$launcher_asset" "$launcher_download/$launcher_asset" ||
    launcher_fail "Could not download $launcher_asset."
  curl -fsSL --retry 3 --connect-timeout 15 --output "$launcher_work/SHA256SUMS.txt" "$launcher_download/SHA256SUMS.txt" ||
    launcher_fail 'Could not download the release checksums.'
  awk -v asset="$launcher_asset" '$2 == asset { print; found = 1; exit } END { if (!found) exit 1 }' \
    "$launcher_work/SHA256SUMS.txt" > "$launcher_work/selected-checksum.txt" ||
    launcher_fail "No release checksum found for $launcher_asset."
  (cd -- "$launcher_work" && shasum -a 256 -c selected-checksum.txt) ||
    launcher_fail 'DMG checksum verification failed. The existing app has not been changed.'

  # Clear only quarantine, first on the DMG and then on the copied app/runtime.
  xattr -drs com.apple.quarantine "$launcher_work/$launcher_asset"
  mkdir "$launcher_work/mount"
  # jpackage embeds the project's MIT license as a DMG prompt. Supply its answer
  # explicitly, so mounting also works when this script arrives through curl | sh.
  printf 'yes\n' | hdiutil attach "$launcher_work/$launcher_asset" -readonly -nobrowse -mountpoint "$launcher_work/mount" > /dev/null
  launcher_mount=$launcher_work/mount
  launcher_source=$launcher_mount/Blockbox\ Launcher.app
fi

launcher_complete_app "$launcher_source" || launcher_fail 'The app is incomplete: launcher, bundled Java, or game files are missing.'
mkdir -p "$APP_DIR"
launcher_stage=$(mktemp -d "$APP_DIR/.blockbox-install.XXXXXX")
ditto "$launcher_source" "$launcher_stage/Blockbox Launcher.app"
xattr -drs com.apple.quarantine "$launcher_stage/Blockbox Launcher.app"
if [ -n "$launcher_mount" ]; then
  hdiutil detach "$launcher_mount" > /dev/null
  launcher_mount=
fi
if [ -e "$launcher_target.previous" ] || [ -L "$launcher_target.previous" ]; then
  launcher_fail "An earlier installation backup exists at $launcher_target.previous. Move it aside before retrying."
fi
if [ -e "$launcher_target" ] || [ -L "$launcher_target" ]; then mv "$launcher_target" "$launcher_target.previous"; fi
if ! mv "$launcher_stage/Blockbox Launcher.app" "$launcher_target"; then
  if [ -e "$launcher_target.previous" ] || [ -L "$launcher_target.previous" ]; then mv "$launcher_target.previous" "$launcher_target"; fi
  exit 1
fi
if [ -e "$launcher_target.previous" ] || [ -L "$launcher_target.previous" ]; then rm -rf -- "$launcher_target.previous"; fi
printf 'Installed: %s\nBlockbox and Java are included. Open the app from Finder; no developer tools are needed.\n' "$launcher_target"

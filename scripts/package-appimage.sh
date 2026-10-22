#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
IMAGE=$LAUNCHER_ROOT/build/native/Blockbox\ Launcher
TOOL=${APPIMAGETOOL:-}
if [ -z "$TOOL" ]; then TOOL=$(command -v appimagetool || true); fi
[ -n "$TOOL" ] && [ -x "$TOOL" ] || { printf '%s\n' 'Set APPIMAGETOOL to the official appimagetool executable. CI installs this build tool automatically.' >&2; exit 1; }
[ -f "$IMAGE/lib/app/game/blockbox-game.properties" ] || { printf '%s\n' 'Build the full application first: ./gradlew packageApp' >&2; exit 1; }
case "$(uname -m)" in
  x86_64|amd64) APPIMAGE_ARCH=x86_64; RELEASE_ARCH=x64 ;;
  aarch64|arm64) APPIMAGE_ARCH=aarch64; RELEASE_ARCH=arm64 ;;
  *) printf '%s\n' 'Unsupported AppImage architecture.' >&2; exit 1 ;;
esac
OUTPUT=$LAUNCHER_ROOT/build/installers/blockbox-linux-$RELEASE_ARCH.AppImage
mkdir -p "$LAUNCHER_ROOT/build/installers"
STAGE=$(mktemp -d "$LAUNCHER_ROOT/build/.appimage.XXXXXX")
trap 'rm -rf -- "$STAGE"' EXIT HUP INT TERM
APP_DIR=$STAGE/Blockbox.AppDir
mkdir -p "$APP_DIR/usr"
cp -R "$IMAGE/." "$APP_DIR/usr/"
cat > "$APP_DIR/AppRun" <<'EOF'
#!/usr/bin/env sh
set -eu
APP_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$APP_ROOT/usr/lib/runtime/bin/java" -Xms32m -Xmx256m -jar "$APP_ROOT/usr/lib/app/blockbox-launcher.jar" "$@"
EOF
chmod +x "$APP_DIR/AppRun"
ln -s AppRun "$APP_DIR/blockbox-launcher"
cp "$IMAGE/lib/Blockbox Launcher.png" "$APP_DIR/blockbox-launcher.png"
cat > "$APP_DIR/blockbox-launcher.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Blockbox
Comment=Play Blockbox and manage instances
Exec=blockbox-launcher
Icon=blockbox-launcher
Terminal=false
Categories=Game;
EOF
mkdir -p "$APP_DIR/usr/share/metainfo" "$APP_DIR/usr/share/applications"
cp "$APP_DIR/blockbox-launcher.desktop" "$APP_DIR/usr/share/applications/"
cat > "$APP_DIR/usr/share/metainfo/io.github.robertflexx.blockbox.metainfo.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>io.github.robertflexx.blockbox</id>
  <metadata_license>CC0-1.0</metadata_license>
  <project_license>MIT</project_license>
  <name>Blockbox</name>
  <developer id="io.github.robertflexx"><name>Blockbox contributors</name></developer>
  <summary>Play Blockbox and manage separate worlds and mods</summary>
  <description><p>Blockbox game and classic desktop launcher, with everything needed to start playing.</p></description>
  <launchable type="desktop-id">blockbox-launcher.desktop</launchable>
  <url type="homepage">https://github.com/RobertFlexx/blockbox-launcher</url>
  <provides><binary>blockbox-launcher</binary></provides>
  <content_rating type="oars-1.1"><content_attribute id="violence-cartoon">mild</content_attribute></content_rating>
</component>
EOF
if [ -n "${APPIMAGETOOL_RUNTIME_FILE:-}" ]; then
  set -- --runtime-file "$APPIMAGETOOL_RUNTIME_FILE"
else
  set --
fi
# AppImage's optional AppStream check makes network requests and depends on the
# host's validator version. Keep packaging usable offline with a supplied runtime.
ARCH=$APPIMAGE_ARCH APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$@" "$APP_DIR" "$OUTPUT"
chmod +x "$OUTPUT"
printf 'Ready: %s\n' "$OUTPUT"

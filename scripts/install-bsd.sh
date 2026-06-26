#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAUNCHER_ROOT=$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)
BIN_DIR="${HOME}/.local/bin"
APP_DIR="${HOME}/.local/share/applications"
WRAPPER="${BIN_DIR}/blockbox-launcher"
DESKTOP="${APP_DIR}/blockbox-launcher.desktop"

mkdir -p "${BIN_DIR}" "${APP_DIR}"

cat > "${WRAPPER}" <<EOF
#!/usr/bin/env sh
set -eu
if [ -z "\${JAVA_HOME:-}" ]; then
  for candidate in /usr/local/openjdk21 /usr/local/openjdk-21 /usr/local/jdk-21 /usr/lib/jvm/openjdk-21; do
    if [ -x "\${candidate}/bin/java" ]; then
      export JAVA_HOME="\${candidate}"
      export PATH="\${JAVA_HOME}/bin:\${PATH}"
      break
    fi
  done
fi
cd "${LAUNCHER_ROOT}"
exec gradle run
EOF
chmod +x "${WRAPPER}"

cat > "${DESKTOP}" <<EOF
[Desktop Entry]
Type=Application
Name=Blockbox Launcher
Comment=Launch and manage Blockbox instances
Exec=${WRAPPER}
Terminal=false
Categories=Game;
StartupNotify=true
EOF

printf 'Blockbox Launcher installed. Run it from your app menu if your desktop reads .desktop files, or with: %s\n' "${WRAPPER}"

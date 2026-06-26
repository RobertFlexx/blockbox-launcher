#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHER_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
BIN_DIR="${HOME}/.local/bin"
APP_DIR="${HOME}/.local/share/applications"
WRAPPER="${BIN_DIR}/blockbox-launcher"
DESKTOP="${APP_DIR}/blockbox-launcher.desktop"

mkdir -p "${BIN_DIR}" "${APP_DIR}"

cat > "${WRAPPER}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
if [[ -z "\${JAVA_HOME:-}" ]]; then
  for candidate in /opt/openjdk-bin-21 /usr/lib/jvm/openjdk-21 /usr/lib/jvm/openjdk-bin-21 /usr/lib/jvm/java-21-openjdk; do
    if [[ -x "\${candidate}/bin/java" ]]; then
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

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "${APP_DIR}" >/dev/null 2>&1 || true
fi

printf 'Blockbox Launcher installed. Run it from your app menu or with: %s\n' "${WRAPPER}"

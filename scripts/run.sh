#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LAUNCHER_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

if [[ -n "${BLOCKBOX_LAUNCHER_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="${BLOCKBOX_LAUNCHER_JAVA_HOME}"
else
  for candidate in /opt/openjdk-bin-21 /usr/lib/jvm/openjdk-21 /usr/lib/jvm/openjdk-bin-21 /usr/lib/jvm/java-21-openjdk; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
  GRADLE_JAVA_OPT="-Dorg.gradle.java.home=${JAVA_HOME}"
else
  GRADLE_JAVA_OPT=""
fi

cd "${LAUNCHER_ROOT}"
exec gradle --no-daemon ${GRADLE_JAVA_OPT} run

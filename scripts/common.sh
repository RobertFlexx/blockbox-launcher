#!/usr/bin/env sh
# Sourced by the POSIX launch/build/install scripts.

launcher_java() {
  if [ -n "${BLOCKBOX_LAUNCHER_JAVA_HOME:-}" ]; then
    JAVA_HOME=$BLOCKBOX_LAUNCHER_JAVA_HOME
    [ -x "$JAVA_HOME/bin/java" ] || { printf '%s\n' "No Java executable in BLOCKBOX_LAUNCHER_JAVA_HOME: $JAVA_HOME" >&2; return 1; }
  elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    :
  else
    for launcher_candidate in /opt/openjdk-bin-21* /usr/lib/jvm/*21* /usr/lib64/jvm/*21* /usr/local/openjdk21 /usr/local/openjdk-21 /usr/local/jdk-21 /Library/Java/JavaVirtualMachines/*21*/Contents/Home; do
      if [ -x "$launcher_candidate/bin/java" ]; then JAVA_HOME=$launcher_candidate; break; fi
    done
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    JAVA_COMMAND=$JAVA_HOME/bin/java
    PATH=$JAVA_HOME/bin:$PATH
    export PATH
  else
    JAVA_COMMAND=$(command -v java || true)
  fi
  [ -n "$JAVA_COMMAND" ] || { printf '%s\n' 'Java 21+ is required for the portable launcher. Install a JDK or use a bundled application build.' >&2; return 1; }
  launcher_version=$("$JAVA_COMMAND" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
  case "$launcher_version" in ''|*[!0-9]*) printf '%s\n' 'Could not determine Java version.' >&2; return 1;; esac
  [ "$launcher_version" -ge 21 ] || { printf '%s\n' "Java 21+ is required; found Java $launcher_version." >&2; return 1; }
}

launcher_build() {
  launcher_java
  if [ "$launcher_version" -gt 23 ]; then
    printf '%s\n' 'Building with this Gradle wrapper requires JDK 21–23. Set BLOCKBOX_LAUNCHER_JAVA_HOME to a JDK 21 installation.' >&2
    return 1
  fi
  if [ -f "$LAUNCHER_ROOT/gradle/wrapper/gradle-wrapper.jar" ]; then
    "$LAUNCHER_ROOT/gradlew" --no-daemon -p "$LAUNCHER_ROOT" "$@"
  elif command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon -p "$LAUNCHER_ROOT" "$@"
  else
    printf '%s\n' 'Build tools missing. Use a complete source checkout (including gradlew) or a packaged release.' >&2
    return 1
  fi
}

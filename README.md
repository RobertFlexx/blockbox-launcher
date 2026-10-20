# Blockbox Launcher

A classic desktop launcher for [Blockbox](https://github.com/RobertFlexx/blockbox), written in Kotlin. The interface uses Swing and Java2D, with resizable panes, keyboard navigation, and standard window decorations. It does not depend on Skia, OpenGL, Metal, or Direct3D to draw the launcher.

## Run

**Packaged application:** open **Blockbox Launcher** from your application menu, Start Menu, or Finder. The native application includes its own Java runtime. Gradle and a system Java installation are not needed to start it.

**From source:** install **JDK 21**, then run:

```sh
./scripts/run.sh
```

The first run uses the included, checksum-pinned Gradle wrapper to build the application. Later runs launch the built JAR directly, without invoking Gradle. If a native bundle has already been built, the run script uses it without requiring system Java. After editing source, rebuild explicitly:

```sh
./scripts/run.sh --rebuild
```

On Windows:

```powershell
.\scripts\run-windows.ps1
.\scripts\run-windows.ps1 -Rebuild
```

`scripts/run-macos.sh` and `scripts/run-bsd.sh` use the same build-once flow. Use `BLOCKBOX_LAUNCHER_JAVA_HOME` to select a JDK. Source builds use JDK 21–23 with the pinned Gradle 8.10.2 wrapper; JDK 21 is recommended. The game can use a separate JDK selected in each instance's Settings.

## Install from source

These installers reuse an existing application build, or build it once when missing, and copy it into your user directory. They do not require administrator access or run Gradle when the installed launcher starts. Updating replaces application files while keeping instances and worlds in their separate data directory. After editing source, pass `--rebuild` (`-Rebuild` in PowerShell) to build a fresh application before installing.

| System | Command | Installed application |
| --- | --- | --- |
| Linux | `./scripts/install-linux.sh` | `~/.local/share/blockbox-launcher-app`, application menu, `~/.local/bin/blockbox-launcher` |
| macOS | `./scripts/install-macos.sh` | `~/Applications/Blockbox Launcher.app` |
| Windows | `.\scripts\install-windows.ps1` | `%LOCALAPPDATA%\Programs\Blockbox Launcher`, Start Menu shortcut |
| BSD | `./scripts/install-bsd.sh` | `~/.local/share/blockbox-launcher-app`, application menu, `~/.local/bin/blockbox-launcher` |

Linux, macOS, and Windows installers create a native application image with a private runtime using JDK `jpackage`. Build each application on its target OS and architecture. No additional DEB/RPM/MSI packaging tools are required for the app image.

BSD uses the portable JVM distribution because `jpackage` has no BSD backend. A local Java 21+ runtime is required there. Linux can also use `./scripts/install-linux.sh --portable` to install this smaller distribution without a bundled runtime.

Options:

```sh
./scripts/install-linux.sh --prefix /absolute/path
./scripts/install-linux.sh --portable --prefix /absolute/path
BLOCKBOX_APPLICATIONS_DIR=/path/to/Applications ./scripts/install-macos.sh
```

```powershell
.\scripts\install-windows.ps1 -InstallDir 'D:\Games\Blockbox Launcher'
```

The source folder can be moved after installation. Do not delete it if your existing instances are stored there; the launcher preserves legacy data locations rather than moving worlds automatically.

## Game setup

The launcher detects a sibling `blockbox` checkout or `~/blockbox`. Use **Launcher → Game location…** to choose another checkout; the choice is saved. `BLOCKBOX_GAME_ROOT` takes precedence over the saved location.

To run the game, install **Scala CLI** and a **JDK 21+**. The launcher runtime alone does not include a compiler. You can choose a Java executable per instance, set `BLOCKBOX_JAVA_HOME`, or use automatic JDK discovery. Set `BLOCKBOX_SCALA_CLI` when Scala CLI is outside PATH. On Windows select the native `scala-cli.exe`; batch shims are not supported because they cannot safely preserve arbitrary arguments without shell evaluation.

The launcher compiles the game's Java helpers with the selected JDK, then runs Scala CLI with its Scala sources and LWJGL native jars for the current OS/CPU. Helpers and native jars are cached in launcher data; Scala CLI resolves the remaining dependencies on first launch. Build files stay in the instance's `.build/` directory. This avoids game scripts that change the working directory, hardcode Linux libraries, or install tools unexpectedly.

Supported game native selections include Linux x86_64/ARM64, Windows x86_64/ARM64, and macOS Intel/Apple Silicon, plus the additional architectures listed in `Platform.kt`. Availability also depends on the game's LWJGL version. On BSD or other systems without published native libraries, set `BLOCKBOX_LWJGL_NATIVE_DIR` to compatible locally built LWJGL libraries. Running the launcher on a platform does not make unsupported game native libraries available.

## Instances and data

Each instance has its own `mods/`, `worlds/`, `config/`, `logs/`, and launch settings. The game runs with that instance as its working directory. Duplicating an instance copies worlds, mods, and configuration; it starts with fresh logs and play history.

The game currently reads its own global graphics settings from `blockbox-settings.properties` in the game checkout. That game-side setting remains shared. Launcher memory, backend, arguments, and mod configuration are per instance.

Existing `instances/` directories in the launcher checkout are reused. New installations default to:

| System | Data directory |
| --- | --- |
| Linux / BSD | `${XDG_DATA_HOME:-~/.local/share}/blockbox-launcher` |
| Windows | `%LOCALAPPDATA%\BlockboxLauncher` |
| macOS | `~/Library/Application Support/BlockboxLauncher` |

Set `BLOCKBOX_LAUNCHER_HOME`, or pass `--data-dir /path`, to select a data directory explicitly. Open the current location through **Launcher → Open launcher data**. Application updates do not delete this directory.

Settings are validated and saved atomically. **Save settings** commits the whole form; switching instances or launching asks how to handle unsaved edits. Corrupt instance files are skipped with a Console message rather than preventing the launcher from opening.

## Graphics

The launcher's Java2D GPU pipelines are disabled by default to avoid driver-dependent rendering failures. Standard JVM system properties can override this when needed.

Game graphics are separate, under **Settings → Game graphics**:

* **Automatic:** preserves the system's graphics environment and lets GLFW choose.
* **X11:** selects X11 without forcing an NVIDIA driver.
* **Wayland:** selects Wayland/EGL and disables GLFW libdecor by default.
* **NVIDIA X11 / GLX:** explicitly selects X11 with NVIDIA GLX.
* **Software (Mesa):** requests llvmpipe. Requires Mesa and can be slow.

These selectors are enabled on Linux/BSD. macOS and Windows use their native window systems. Per-instance environment overrides take precedence. GameMode is Linux-only, optional, and disabled by default.

The launcher never kills a game merely because it uses software rendering, never changes a saved backend automatically, and never silently retries a failed launch. Use **Console** for the full error and **Diagnostics** for runtime, source, display, and native-library information. **Stop game** terminates the game and its captured child processes. Closing the launcher offers to keep the game running or stop it first.

## Mods and packs

Use **Mods → Import…** for a `.jar`, `.groovy` file, or mod directory. Enable/disable renames the entry using `.disabled`; a name collision produces an error instead of overwriting another mod. Import, delete, and enable/disable are disabled for the running instance.

`.bbpack` archives contain metadata, mods, and mod configuration. **Include worlds** adds saved worlds for backups. Import restores the pack name and description and validates archive paths and format. Failed imports are rolled back. Exported packs do not contain private Java arguments or environment variables.

## Shortcuts

Use Ctrl on Windows/Linux/BSD and Command on macOS:

* **Ctrl/Command + N:** new instance.
* **Ctrl/Command + L:** launch selected instance.
* **Ctrl/Command + S:** save settings.
* **Ctrl/Command + 4:** console.

## Build and verify

```sh
./gradlew check                 # regression tests
./gradlew standaloneJar         # build/standalone/blockbox-launcher.jar
./gradlew installDist           # build/install/blockbox-launcher/
./gradlew packageApp            # build/native/Blockbox Launcher[.app]
java -jar build/standalone/blockbox-launcher.jar --version
```

The GitHub Actions workflow tests and packages Linux, Windows, and macOS applications. Each native bundle includes the runtime for its build host. BSD uses a locally built portable distribution. Other OS and graphics-driver combinations still need testing on those machines.

Packaging references: [JDK application images](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html), [Gradle application distributions](https://docs.gradle.org/current/userguide/application_plugin.html).

Kotlin/Native was evaluated: this application's UI, file, archive, and process APIs use the JVM, and [Kotlin/Native's supported targets](https://kotlinlang.org/docs/native-target-support.html) do not include BSD. A native app image with a private JVM runtime provides standalone startup while retaining one desktop implementation across these systems.

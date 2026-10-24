# Blockbox Launcher

Classic desktop launcher for [Blockbox](https://github.com/RobertFlexx/blockbox). It keeps instances, worlds, and mods separate, then starts the game.

## Install

Get the download for your computer from [Releases](https://github.com/RobertFlexx/blockbox-launcher/releases). Full installers include **the launcher, game, assets, sounds, and Java**. No checkout, Gradle, Scala CLI, or Java installation needed.

| Computer | Download | Install |
| --- | --- | --- |
| Windows 64-bit | `blockbox-windows-x64-setup.exe` | Open the installer, then use the Start Menu. |
| Mac, Apple Silicon | `blockbox-macos-arm64.dmg` | Open it and drag the app to Applications. |
| Mac, Intel | `blockbox-macos-x64.dmg` | Open it and drag the app to Applications. |
| Linux | `blockbox-linux-x64.AppImage` | Allow execution in Properties, then open it. |
| Debian / Ubuntu | `blockbox-linux-x64.deb` | Open with your software installer. |
| Fedora / openSUSE | `blockbox-linux-x64.rpm` | Open with your software installer. |

Open Blockbox Launcher and press **Launch game**. The game is already compiled. Your instances and worlds live in your user account, separately from the application, and survive application updates.

Installers are currently unsigned, so Windows/macOS may ask for approval. If Linux cannot mount an AppImage, start it with `--appimage-extract-and-run` to run without FUSE.

### macOS terminal install

Run this in Terminal to install the latest release into `~/Applications`:

```sh
curl -fsSL https://raw.githubusercontent.com/RobertFlexx/blockbox-launcher/main/scripts/install-macos.sh | sh
```

The [installer script](scripts/install-macos.sh) selects Apple Silicon or Intel, downloads the DMG, verifies its published SHA-256 checksum, installs under the project's [MIT license](LICENSE), and removes `com.apple.quarantine` from the DMG and app (including bundled Java). No Java installation, Scala CLI, source checkout, or `sudo` is required. Use this command only if you trust this project's unsigned releases. It removes quarantine for these files only.

From a launcher checkout, `./scripts/install-macos.sh` does the same thing. Set `BLOCKBOX_APPLICATIONS_DIR` to an absolute path to choose another destination:

```sh
curl -fsSL https://raw.githubusercontent.com/RobertFlexx/blockbox-launcher/main/scripts/install-macos.sh | BLOCKBOX_APPLICATIONS_DIR="$HOME/Games" sh
```

If you already downloaded a DMG in your browser, clear its quarantine before opening it (use `x64` in the filename for an Intel Mac):

```sh
xattr -dr com.apple.quarantine "$HOME/Downloads/blockbox-macos-arm64.dmg"
open "$HOME/Downloads/blockbox-macos-arm64.dmg"
```

Drag **Blockbox Launcher.app** to Applications. If macOS still blocks the copied app, clear quarantine on that copy, including its bundled Java:

```sh
xattr -dr com.apple.quarantine "/Applications/Blockbox Launcher.app"
```

Use `"$HOME/Applications/Blockbox Launcher.app"` instead if you copied it to your user Applications folder. Apple's other option is **System Settings → Privacy & Security → Open Anyway** after the first blocked launch; see [Apple's instructions](https://support.apple.com/102445).

## From source

Building requires **JDK 21** and **Scala CLI**. Keep a game checkout at `../blockbox`, or choose **Launcher → Game location…**. `BLOCKBOX_GAME_ROOT` overrides the game location; `BLOCKBOX_JAVA_HOME` / `BLOCKBOX_SCALA_CLI` select game build tools. On Windows, use the native `scala-cli.exe`.

```sh
./scripts/run.sh
./scripts/run.sh --rebuild  # after editing the launcher
```

First run builds the launcher; later runs start its jar without Gradle. Windows: `.\scripts\run-windows.ps1` (`-Rebuild` after edits). macOS/BSD: `scripts/run-macos.sh` / `scripts/run-bsd.sh`. `BLOCKBOX_LAUNCHER_JAVA_HOME` selects the launcher's JDK.

The first source-game launch builds a runnable jar in `cache/game/`. Instances share it; later launches skip Scala CLI and compilation. Source, dependencies, bundled resources, JDK, or platform changes rebuild it. Memory, graphics, mods, and game arguments don't.

Launch checks the checkout's git remote first. If that remote has new commits and the checkout is clean, it fast-forwards. If you are already up to date, offline, ahead, or have local changes, it leaves the checkout alone and starts the game you have.

`scripts/install-linux.sh` and `scripts/install-windows.ps1` build/copy a full app into your user folder. Linux supports `--prefix DIRECTORY`; Windows supports `-InstallDir DIRECTORY`. Add `--rebuild` / `-Rebuild` for a fresh build. BSD uses `scripts/install-bsd.sh` with its own JDK and compatible native libraries.

On macOS, use `./scripts/install-macos.sh --from-source` to build/copy a local app, or `--rebuild` to force a fresh build; without either flag it downloads a release. Building needs JDK 21, Scala CLI, and the game checkout described above. The macOS Java executable at `/usr/bin/java` is only a system stub when no JDK is installed.

Don't delete an old checkout while your instances still live in it; worlds aren't moved automatically.

## Shortcuts

Ctrl on Windows/Linux/BSD, Command on macOS.

* **N** new instance
* **L** launch
* **S** save settings
* **4** console

## Build

```sh
./gradlew check
./gradlew standaloneJar
./gradlew packageApp -PgameRoot=../blockbox
./gradlew packageInstaller -PgameRoot=../blockbox
```

`packageApp` includes the compiled game, runtime assets, and private Java runtime. `packageInstaller` makes an EXE, DMG, or DEB on its host OS (`-PinstallerType=rpm` for RPM). Linux AppImage: `APPIMAGETOOL=/path/to/appimagetool scripts/package-appimage.sh` after `packageApp`.

[The build workflow](.github/workflows/build.yml) builds and tests Windows, Linux, and both Mac architectures. Set the exact game commit in `game-release.properties`. A version tag matching `build.gradle.kts` (currently `v1.2.0`) publishes all installers with checksums. Ordinary builds attach them as workflow artifacts.

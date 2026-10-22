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

## From source

Building requires **JDK 21** and **Scala CLI**. Keep a game checkout at `../blockbox`, or choose **Launcher → Game location…**. `BLOCKBOX_GAME_ROOT` overrides the game location; `BLOCKBOX_JAVA_HOME` / `BLOCKBOX_SCALA_CLI` select game build tools. On Windows, use the native `scala-cli.exe`.

```sh
./scripts/run.sh
./scripts/run.sh --rebuild  # after editing the launcher
```

First run builds the launcher; later runs start its jar without Gradle. Windows: `.\scripts\run-windows.ps1` (`-Rebuild` after edits). macOS/BSD: `scripts/run-macos.sh` / `scripts/run-bsd.sh`. `BLOCKBOX_LAUNCHER_JAVA_HOME` selects the launcher's JDK.

The first source-game launch builds a runnable jar in `cache/game/`. Instances share it; later launches skip Scala CLI and compilation. Source, dependencies, bundled resources, JDK, or platform changes rebuild it. Memory, graphics, mods, and game arguments don't.

`scripts/install-linux.sh`, `scripts/install-macos.sh`, and `scripts/install-windows.ps1` build/copy a full app into your user folder. Linux supports `--prefix DIRECTORY`; Windows supports `-InstallDir DIRECTORY`. Add `--rebuild` / `-Rebuild` for a fresh build. BSD uses `scripts/install-bsd.sh` with its own JDK and compatible native libraries. Don't delete an old checkout while your instances still live in it; worlds aren't moved automatically.

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

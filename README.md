# Blockbox Launcher

Desktop launcher for [Blockbox](https://github.com/RobertFlexx/blockbox). Swing, nothing fancy. It keeps instances, worlds, and mods separate, then starts the game.

## Run

If you already installed it, just open Blockbox Launcher.

From source, with JDK 21:

```sh
./scripts/run.sh
```

First run builds it. Later runs start the jar. After you change the source:

```sh
./scripts/run.sh --rebuild
```

Windows: `.\scripts\run-windows.ps1` (add `-Rebuild` after edits). macOS and BSD have `scripts/run-macos.sh` and `scripts/run-bsd.sh`. `BLOCKBOX_LAUNCHER_JAVA_HOME` picks the JDK if you have more than one.

## Install

These copy the app into your user folder. No admin needed.

| System | Command | Where it goes |
| --- | --- | --- |
| Linux | `./scripts/install-linux.sh` | `~/.local/bin/blockbox-launcher` |
| macOS | `./scripts/install-macos.sh` | `~/Applications/Blockbox Launcher.app` |
| Windows | `.\scripts\install-windows.ps1` | `%LOCALAPPDATA%\Programs\Blockbox Launcher` |
| BSD | `./scripts/install-bsd.sh` | `~/.local/bin/blockbox-launcher` |

Linux can also do `./scripts/install-linux.sh --portable` if you don't want a bundled runtime. Pass `--prefix` or `-InstallDir` to put it somewhere else. `--rebuild` builds a fresh app first.

Don't delete the source checkout if your instances still live in it. The launcher won't move your worlds for you.

## Game

It looks for a `blockbox` folder next to this repo, or `~/blockbox`. **Launcher → Game location…** if it's somewhere else. `BLOCKBOX_GAME_ROOT` overrides that.

The game itself needs **Scala CLI** and a **JDK 21+**. Pick the Java path in an instance's settings, or set `BLOCKBOX_JAVA_HOME` / `BLOCKBOX_SCALA_CLI`. On Windows, point that at `scala-cli.exe`, not the `.bat`.

Each instance is its own folder: `mods/`, `worlds/`, `config/`, `logs/`. The game runs from there. Sound effects still come from the game checkout's `assets/audio`. If you really want a different folder, set `-Dblockbox.audioRoot=...` in that instance's JVM arguments.

Graphics settings for the game are under **Settings**. On Linux, Automatic is fine. Software (Mesa) works but it's slow. If launch fails, the Console has the error.

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
./gradlew packageApp
```

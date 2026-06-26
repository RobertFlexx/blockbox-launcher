# blockbox launcher

[blockbox](https://github.com/RobertFlexx/blockbox) launcher is the desktop launcher for [blockbox](https://github.com/RobertFlexx/blockbox).

it is made in kotlin with compose desktop. it is meant to feel more like a real instance launcher instead of just a run button.

the launcher is kept as its own standalone gradle project so it can be moved into a separate repo cleanly when needed.

## what it does right now

right now it has:

* separate instances
* per-instance mods
* per-instance worlds
* per-instance config
* per-instance logs
* custom java command
* memory settings
* jvm arguments
* game arguments
* environment variables
* optional linux gamemode launch
* force x11 toggle for lwjgl/glfw
* mod importing
* mod enable and disable
* blockbox pack import and export
* optional world export inside packs
* live logs with ansi junk stripped out
* buttons to open instance, mods, worlds, and logs folders

it is still early, but it is already useful.

## running it from source

from the main blockbox folder:

```bash
./scripts/run-launcher.sh
```

or from this launcher folder:

```bash
gradle run
```

if gradle or kotlin complains about java 25, use java 21. the root `scripts/run-launcher.sh` tries to pick java 21 automatically on linux.

## installing on linux

from this launcher folder:

```bash
chmod +x scripts/install-linux.sh
./scripts/install-linux.sh
```

this creates:

```text
~/.local/bin/blockbox-launcher
~/.local/share/applications/blockbox-launcher.desktop
```

after that, your desktop menu should show blockbox launcher if your desktop environment reads local desktop entries.

## installing on bsd

from this launcher folder:

```sh
chmod +x scripts/install-bsd.sh
./scripts/install-bsd.sh
```

this creates the same kind of local launcher files as linux. desktop menu support depends on the desktop environment you use.

## installing on windows

from powershell inside this launcher folder:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\install-windows.ps1
```

this creates a start menu shortcut for blockbox launcher.

## instances

instances live in:

```text
launcher/instances/
```

each instance has its own folders:

```text
mods/
worlds/
config/
logs/
```

this means you can have a vanilla instance, a modded instance, a testing instance, and a multiplayer instance without them stepping on each other.

## blockbox packs

the launcher uses `.bbpack` files for blockbox launcher packs.

a `.bbpack` is just a zip archive with blockbox launcher metadata and folders like:

```text
blockbox-pack.properties
mods/
config/
worlds/        optional
```

worlds are not included unless you turn on the include worlds checkbox when exporting.

## standalone repo notes

this folder is structured so it can be split out later.

the launcher already has its own:

* `settings.gradle.kts`
* `build.gradle.kts`
* `.gitignore`
* `README.md`
* install scripts
* source tree

if it becomes a real separate git repo, the launcher only needs to know where the blockbox game checkout is.

you can point it at a game checkout with:

```bash
BLOCKBOX_GAME_ROOT=/path/to/blockbox gradle run
```

when it is still inside the main blockbox tree, it finds the game root automatically.

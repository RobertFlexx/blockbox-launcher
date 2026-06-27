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
* display backend picker for auto, wayland, x11, x11 nvidia/glx, and software
* quick graphics presets for safe mode, nvidia x11, wayland, and software fallback
* mod importing
* mod enable and disable
* mod delete with confirmation
* blockbox pack import and export
* optional world export inside packs
* live logs with ansi junk stripped out
* live log clearing
* instance duplicate
* instance delete with confirmation
* quick memory presets
* buttons to open instance, mods, worlds, logs, exports, and game folders
* native file picker support when your desktop has one installed

it is still early, but it is already useful.

## running it from source

from the main blockbox folder:

```bash
./scripts/run-launcher.sh
```

or from this launcher folder on linux:

```bash
./scripts/run.sh
```

on bsd:

```sh
./scripts/run-bsd.sh
```

on windows powershell:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-windows.ps1
```

you can also run gradle directly:

```bash
gradle run
```

if gradle or kotlin complains about java 25, use java 21. the root `scripts/run-launcher.sh` tries to pick java 21 automatically on linux.

## requirements

you need:

* java 21 or newer
* gradle 8 or newer, unless you use a packaged build later
* a desktop that can run compose desktop
* a valid blockbox game checkout

on linux and bsd, install the same graphics/runtime dependencies the game needs too, because the launcher starts the game from your local checkout.

useful optional desktop tools:

* `kdialog` for kde file pickers
* `zenity` for gtk/gnome file pickers
* `yad`, `qarma`, or `matedialog` as alternate file pickers
* `xdg-open` for opening folders from buttons
* `gamemoderun` if you want the gamemode toggle

if none of the native file picker tools are installed, the launcher uses java swing's file chooser.

## file picker notes

the browse buttons try to use a desktop-native picker first.

on linux and bsd the launcher looks for these tools:

* `kdialog`
* `zenity`
* `yad`
* `qarma`
* `matedialog`

if none of those are installed, it falls back to java swing's file chooser. this avoids the broken black awt file dialog that can happen on some wayland setups.

if you want the picker to match your desktop better, install the picker for your desktop environment. for example, kde users usually want `kdialog`, and gtk desktop users usually want `zenity`.

## installing on linux

> install gradle 8+ and java 21 or later!

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

instances can be duplicated or deleted from the overview tab.

delete has a confirmation dialog because it removes that whole instance folder, including mods, worlds, config, and logs. export a pack first if you want a backup.

## graphics options

each instance has its own display backend setting.

available backends:

* `auto`
* `wayland`
* `x11`
* `x11-nvidia`
* `software`

`software` uses llvmpipe and is meant as a fallback, not the fast path.

there are quick preset buttons for common driver problems:

* safe graphics settings
* nvidia x11 preset
* software preset
* fix nvidia x11
* fix wayland
* software fallback

on linux, the launcher passes these settings to the game script instead of trying to do all the opengl setup itself.

known graphics notes:

* wayland can still be buggy on some nvidia systems
* libdecor can fail and make the game window invisible on some desktops
* x11 through xwayland may fail depending on driver setup
* `software` uses llvmpipe and is expected to be slow
* if game launch fails, try `x11-nvidia`, then `x11`, then `wayland`, then `software`

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

mods can be imported, enabled, disabled, opened in the file manager, refreshed, or deleted from the mods tab.

## current problems

the launcher is useful, but still early.

known issues:

* native file pickers depend on what your desktop has installed
* browse falls back to swing if `kdialog`, `zenity`, `yad`, `qarma`, or `matedialog` are missing
* the launcher does not fully diagnose every gpu driver problem yet
* launching with gamemode can make some driver/window problems worse
* deleting an instance is permanent after the confirmation dialog
* pack import/export is simple and does not try to solve dependency conflicts yet
* multiplayer/modpack mismatch handling still depends on the game-side mod hash

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

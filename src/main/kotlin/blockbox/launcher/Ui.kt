package blockbox.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.io.path.absolutePathString

@Composable
fun LauncherApp(
    store: LauncherStore,
    runner: GameRunner,
    instances: MutableList<InstanceConfig>,
    selected: InstanceConfig?,
    logs: MutableList<String>,
    tab: Int,
    status: String,
    onSelect: (InstanceConfig) -> Unit,
    onTab: (Int) -> Unit,
    onStatus: (String) -> Unit,
    onExitLauncher: () -> Unit,
    onInstancesChanged: () -> Unit,
    onInstanceUpdated: (InstanceConfig) -> Unit,
    onLog: (String) -> Unit,
    onClearLogs: () -> Unit
) {
    Surface(Modifier.fillMaxSize().background(AppBg)) {
        Row(Modifier.fillMaxSize().background(AppBg).padding(14.dp)) {
            Sidebar(store, instances, selected, onSelect, onInstancesChanged, onStatus)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Header(selected, status, runner.process?.isAlive == true, onLaunch = {
                    val config = selected ?: return@Header
                    store.saveInstance(config)
                    runner.launch(config, onLog) { code -> onStatus("Exited with code $code") }
                    onStatus("Running ${config.name}")
                    if (config.closeLauncherOnGameStart) onExitLauncher()
                }, onStop = {
                    runner.stop()
                    onStatus("Stopping game")
                })
                Spacer(Modifier.height(12.dp))
                TabRow(selectedTabIndex = tab, backgroundColor = CardBg, contentColor = Color.White) {
                    listOf("Overview", "Settings", "Mods", "Logs").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { onTab(index) }, text = { Text(title) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PanelBg).border(1.dp, SoftLine, RoundedCornerShape(12.dp)).padding(18.dp)) {
                    if (selected == null) Text("Create an instance to begin.") else when (tab) {
                        0 -> OverviewTab(store, selected, onStatus, onInstancesChanged, onInstanceUpdated, onSelect)
                        1 -> SettingsTab(store, selected, onStatus, onInstanceUpdated)
                        2 -> ModsTab(store, selected, onStatus, onInstancesChanged)
                        else -> LogsTab(logs, onStatus, onClearLogs, selected?.let { store.logsDir(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun Sidebar(store: LauncherStore, instances: List<InstanceConfig>, selected: InstanceConfig?, onSelect: (InstanceConfig) -> Unit, onInstancesChanged: () -> Unit, onStatus: (String) -> Unit) {
    Column(Modifier.width(320.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(CardBg).border(1.dp, SoftLine, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("Blockbox", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("launcher", color = MutedText, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val config = store.createInstance("Instance ${instances.size + 1}", "A fresh profile with isolated mods and worlds")
            onInstancesChanged()
            onSelect(config)
            onStatus("Created ${config.name}")
        }, colors = ButtonDefaults.buttonColors(backgroundColor = AppAccent, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Create Instance", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        var importPath by remember { mutableStateOf("") }
        OutlinedTextField(importPath, { importPath = it }, label = { Text(".bbpack path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseFile("Import Blockbox Pack", PACK_EXTENSION)?.let { importPath = it } }) { Text("Browse") }
            TextButton(enabled = importPath.isNotBlank(), onClick = {
                try {
                    val config = store.importPack(importPath)
                    onInstancesChanged()
                    onSelect(config)
                    onStatus("Imported ${config.name}")
                } catch (e: Exception) {
                    onStatus(e.message ?: "Import failed")
                }
            }) { Text("Import") }
        }
        Divider(color = SoftLine)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(instances, key = { it.id }) { config ->
                InstanceCard(config, selected?.id == config.id) { onSelect(config) }
            }
        }
    }
}

@Composable
fun InstanceCard(config: InstanceConfig, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) Color(0xff1f2937) else PanelBg).border(1.dp, if (active) AppAccent else SoftLine, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(if (active) AppAccent else Color(0xff30363d)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(config.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(config.lastPlayed, color = MutedText, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(config.description, color = Color(0xffc9d1d9), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(config.displayBackend.ifBlank { "auto" })
            Pill("${config.maxMemoryMb / 1024}G")
            if (config.useGameMode) Pill("GameMode")
        }
    }
}

@Composable
fun Pill(text: String) {
    Text(text, color = Color(0xffd1d7e0), fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xff21262d)).border(1.dp, SoftLine, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun Header(selected: InstanceConfig?, status: String, running: Boolean, onLaunch: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).border(1.dp, SoftLine, RoundedCornerShape(12.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(selected?.name ?: "No Instance", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(status, color = MutedText)
        }
        if (running) OutlinedButton(onClick = onStop) { Text("Stop") } else Button(onClick = onLaunch, enabled = selected != null, colors = ButtonDefaults.buttonColors(backgroundColor = AppAccent, contentColor = Color.White)) { Text("Launch", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun OverviewTab(store: LauncherStore, config: InstanceConfig, onStatus: (String) -> Unit, onInstancesChanged: () -> Unit, onInstanceUpdated: (InstanceConfig) -> Unit, onSelect: (InstanceConfig) -> Unit) {
    var includeWorlds by remember(config.id) { mutableStateOf(false) }
    var confirmDelete by remember(config.id) { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${config.name}?") },
            text = { Text("This permanently deletes this instance folder, including its mods, worlds, config, and logs. Export a pack first if you want a backup.") },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(backgroundColor = Danger, contentColor = Color.White), onClick = {
                    try {
                        val deletedName = config.name
                        store.deleteInstance(config)
                        confirmDelete = false
                        onInstancesChanged()
                        onStatus("Deleted $deletedName")
                    } catch (e: Exception) {
                        confirmDelete = false
                        onStatus(e.message ?: "Delete failed")
                    }
                }) { Text("Delete Forever") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Instance", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("A separate Blockbox profile with its own mods, worlds, config, logs, memory settings, and launch options.", color = MutedText)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickStat("Mods", countFiles(store.modsDir(config)).toString())
            QuickStat("Worlds", countFiles(store.worldsDir(config)).toString())
            QuickStat("Logs", countFiles(store.logsDir(config)).toString())
            QuickStat("Memory", "${config.maxMemoryMb / 1024}G")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStatus(openPath(store.instanceDir(config))) }) { Text("Open Instance") }
            OutlinedButton(onClick = { onStatus(openPath(store.modsDir(config))) }) { Text("Open Mods") }
            OutlinedButton(onClick = { onStatus(openPath(store.worldsDir(config))) }) { Text("Open Worlds") }
            OutlinedButton(onClick = { onStatus(openPath(store.logsDir(config))) }) { Text("Open Logs") }
            OutlinedButton(onClick = { onStatus(openPath(store.exportsRoot)) }) { Text("Open Exports") }
            OutlinedButton(onClick = { onStatus(openPath(store.projectRoot)) }) { Text("Open Game") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val copy = store.duplicateInstance(config)
                onInstancesChanged()
                onSelect(copy)
                onStatus("Duplicated ${config.name}")
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Success, contentColor = Color.White)) { Text("Duplicate Instance") }
            OutlinedButton(onClick = {
                try {
                    val out = store.exportPack(config, includeWorlds)
                    onStatus("Exported pack to ${out.absolutePathString()}")
                } catch (e: Exception) {
                    onStatus(e.message ?: "Export failed")
                }
            }) { Text("Export Pack") }
            Checkbox(includeWorlds, { includeWorlds = it })
            Text("include worlds", color = MutedText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                config.useGameMode = false
                config.displayBackend = "auto"
                store.saveInstance(config)
                onInstanceUpdated(config)
                onStatus("Safe graphics settings saved: GameMode off, display backend auto")
            }) { Text("Safe Graphics Settings") }
            OutlinedButton(onClick = {
                config.useGameMode = false
                config.displayBackend = "x11-nvidia"
                store.saveInstance(config)
                onInstanceUpdated(config)
                onStatus("NVIDIA X11 settings saved")
            }) { Text("NVIDIA X11 Preset") }
            OutlinedButton(onClick = {
                config.displayBackend = "software"
                config.useGameMode = false
                store.saveInstance(config)
                onInstanceUpdated(config)
                onStatus("Software fallback settings saved")
            }) { Text("Software Preset") }
            Text("use this if launch fails before the window appears", color = MutedText, fontSize = 12.sp)
        }
        Divider(color = SoftLine)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Delete Instance") }
            Text("permanently removes this profile folder", color = MutedText, fontSize = 12.sp)
        }
        StatGrid(listOf(
            "instance folder" to store.instanceDir(config).absolutePathString(),
            "game root" to store.projectRoot.absolutePathString(),
            "launcher data" to store.launcherRoot.absolutePathString(),
            "mods" to "${countFiles(store.modsDir(config))} installed entries",
            "worlds" to "${countFiles(store.worldsDir(config))} saved worlds",
            "logs" to "${countFiles(store.logsDir(config))} saved logs",
            "memory" to "${config.minMemoryMb}M minimum, ${config.maxMemoryMb}M maximum",
            "java" to config.javaPath,
            "gamemode" to if (config.useGameMode) "enabled when gamemoderun is installed (can break some driver setups)" else "disabled",
            "display backend" to config.displayBackend.ifBlank { "auto" },
            "jvm arguments" to (splitCommandLine(config.jvmArgs).ifEmpty { listOf("no custom arguments") }.joinToString("\n"))
        ))
    }
}

@Composable
fun QuickStat(label: String, value: String) {
    Column(Modifier.width(130.dp).clip(RoundedCornerShape(10.dp)).background(CardBg).border(1.dp, SoftLine, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = MutedText, fontSize = 12.sp)
    }
}

@Composable
fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, value) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardBg).border(1.dp, SoftLine, RoundedCornerShape(8.dp)).padding(14.dp)) {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold)
                Text(value, color = Color(0xffc9d1d9), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun SettingsTab(store: LauncherStore, config: InstanceConfig, onStatus: (String) -> Unit, onInstanceUpdated: (InstanceConfig) -> Unit) {
    var name by remember(config.id) { mutableStateOf(config.name) }
    var description by remember(config.id) { mutableStateOf(config.description) }
    var javaPath by remember(config.id) { mutableStateOf(config.javaPath) }
    var minMemory by remember(config.id) { mutableStateOf(config.minMemoryMb.toString()) }
    var maxMemory by remember(config.id) { mutableStateOf(config.maxMemoryMb.toString()) }
    var jvmArgs by remember(config.id) { mutableStateOf(config.jvmArgs) }
    var gameArgs by remember(config.id) { mutableStateOf(config.gameArgs) }
    var envVars by remember(config.id) { mutableStateOf(config.envVars) }
    var useGameMode by remember(config.id) { mutableStateOf(config.useGameMode) }
    var displayBackend by remember(config.id) { mutableStateOf(config.displayBackend.ifBlank { "auto" }) }
    var closeOnStart by remember(config.id) { mutableStateOf(config.closeLauncherOnGameStart) }

    fun syncConfig() {
        config.name = name
        config.description = description
        config.javaPath = javaPath
        config.minMemoryMb = minMemory.toIntOrNull()?.coerceAtLeast(128) ?: config.minMemoryMb
        config.maxMemoryMb = maxMemory.toIntOrNull()?.coerceAtLeast(config.minMemoryMb) ?: config.maxMemoryMb
        config.jvmArgs = jvmArgs
        config.gameArgs = gameArgs
        config.envVars = envVars
        config.useGameMode = useGameMode
        config.displayBackend = displayBackend
        config.forceX11 = displayBackend == "x11"
        config.closeLauncherOnGameStart = closeOnStart
        onInstanceUpdated(config)
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        EditableText("Name", name) { name = it; config.name = it; onInstanceUpdated(config) }
        EditableText("Description", description) { description = it; config.description = it; onInstanceUpdated(config) }
        Divider(color = SoftLine)
        Text("Java Runtime", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        EditableText("Java command", javaPath) { javaPath = it; config.javaPath = it; onInstanceUpdated(config) }
        Text("Use 'java' to let the game script auto-select the system JDK, or paste a full java path for this instance.", color = MutedText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { EditableText("Minimum memory MB", minMemory) { minMemory = it.filter(Char::isDigit); minMemory.toIntOrNull()?.let { mb -> config.minMemoryMb = mb.coerceAtLeast(128); onInstanceUpdated(config) } } }
            Box(Modifier.weight(1f)) { EditableText("Maximum memory MB", maxMemory) { maxMemory = it.filter(Char::isDigit); maxMemory.toIntOrNull()?.let { mb -> config.maxMemoryMb = mb.coerceAtLeast(128); onInstanceUpdated(config) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2048, 4096, 6144, 8192, 12288).forEach { mb ->
                OutlinedButton(onClick = { maxMemory = mb.toString(); config.maxMemoryMb = mb; onInstanceUpdated(config); onStatus("Set max memory to ${mb}M") }) { Text("${mb / 1024}G") }
            }
            OutlinedButton(onClick = {
                minMemory = "2048"
                maxMemory = "8192"
                config.minMemoryMb = 2048
                config.maxMemoryMb = 8192
                onInstanceUpdated(config)
                onStatus("Applied balanced memory preset")
            }) { Text("Balanced") }
        }
        MultilineText("JVM arguments", jvmArgs, "one or many args. quoted values are supported, for example: -Dblockbox.profile=\"Survival Instance\"", 4) { jvmArgs = it; config.jvmArgs = it; onInstanceUpdated(config) }
        Text("parsed JVM args: ${splitCommandLine(jvmArgs).size} custom + memory args", color = MutedText, fontSize = 12.sp)
        Divider(color = SoftLine)
        Text("Launch", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        MultilineText("Game arguments", gameArgs, "optional game flags. these are forwarded after -- to blockbox.", 3) { gameArgs = it; config.gameArgs = it; onInstanceUpdated(config) }
        MultilineText("Environment variables", envVars, "KEY=value, one per line. example: BLOCKBOX_PLAYER_STYLE=builder", 4) { envVars = it; config.envVars = it; onInstanceUpdated(config) }
        ToggleRow("Launch with gamemoderun when available", useGameMode) { useGameMode = it; config.useGameMode = it; onInstanceUpdated(config) }
        Text("turn this off if the game fails before opening a window", color = MutedText, fontSize = 12.sp)
        Text("Display backend", color = Color.White, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "wayland" to "Wayland", "x11" to "X11", "x11-nvidia" to "X11 NVIDIA/GLX", "software" to "Software").forEach { (value, label) ->
                val selected = displayBackend == value
                if (selected) Button(onClick = {}, colors = ButtonDefaults.buttonColors(backgroundColor = AppAccent, contentColor = Color.White)) { Text(label) }
                else OutlinedButton(onClick = { displayBackend = value; config.displayBackend = value; config.forceX11 = value == "x11"; onInstanceUpdated(config) }) { Text(label) }
            }
        }
        Text("Auto lets GLFW choose. On Wayland desktops, use Wayland. On X11 desktops, use X11. X11 NVIDIA/GLX adds NV driver flags. Software uses llvmpipe (no GPU needed).", color = MutedText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { displayBackend = "x11-nvidia"; config.displayBackend = "x11-nvidia"; useGameMode = false; config.useGameMode = false; onInstanceUpdated(config); onStatus("Selected NVIDIA X11 preset") }) { Text("Fix NVIDIA X11") }
            OutlinedButton(onClick = { displayBackend = "wayland"; config.displayBackend = "wayland"; useGameMode = false; config.useGameMode = false; onInstanceUpdated(config); onStatus("Selected Wayland preset") }) { Text("Fix Wayland") }
            OutlinedButton(onClick = { displayBackend = "software"; config.displayBackend = "software"; useGameMode = false; config.useGameMode = false; onInstanceUpdated(config); onStatus("Selected software fallback") }) { Text("Software Fallback") }
        }
        ToggleRow("Close launcher on game start", closeOnStart) { closeOnStart = it; config.closeLauncherOnGameStart = it; onInstanceUpdated(config) }
        Button(onClick = { syncConfig(); store.saveInstance(config); onStatus("Saved ${config.name}") }, colors = ButtonDefaults.buttonColors(backgroundColor = AppAccent, contentColor = Color.White)) { Text("Save Settings", fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun EditableText(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun MultilineText(label: String, value: String, helper: String, lines: Int, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = lines)
        Text(helper, color = MutedText, fontSize = 12.sp)
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
fun ModsTab(store: LauncherStore, config: InstanceConfig, onStatus: (String) -> Unit, onInstancesChanged: () -> Unit) {
    var importPath by remember(config.id) { mutableStateOf("") }
    var mods by remember(config.id) { mutableStateOf(store.scanMods(config)) }
    var deleteTarget by remember(config.id) { mutableStateOf<BlockboxMod?>(null) }
    deleteTarget?.let { mod ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${mod.name}?") },
            text = { Text("This permanently removes ${mod.path.fileName} from this instance's mods folder.") },
            confirmButton = {
                Button(colors = ButtonDefaults.buttonColors(backgroundColor = Danger, contentColor = Color.White), onClick = {
                    try {
                        onStatus(store.deleteMod(config, mod))
                        mods = store.scanMods(config)
                        onInstancesChanged()
                    } catch (e: Exception) {
                        onStatus(e.message ?: "Delete failed")
                    } finally {
                        deleteTarget = null
                    }
                }) { Text("Delete Mod") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(importPath, { importPath = it }, label = { Text("Mod file or folder path") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { chooseAnyFileOrFolder("Import Blockbox Mod")?.let { importPath = it } }) { Text("Browse") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = importPath.isNotBlank(), onClick = {
                try { onStatus(store.importMod(config, importPath)); mods = store.scanMods(config) } catch (e: Exception) { onStatus(e.message ?: "Import failed") }
            }) { Text("Import") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onStatus(openPath(store.modsDir(config))) }) { Text("Open Mods") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { mods = store.scanMods(config); onStatus("Refreshed mods") }) { Text("Refresh") }
        }
        Spacer(Modifier.height(14.dp))
        AnimatedVisibility(mods.isEmpty()) { Text("No mods installed. Import a .jar, .groovy file, or mod folder.", color = MutedText) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(mods, key = { it.path.absolutePathString() }) { mod ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CardBg).border(1.dp, SoftLine, RoundedCornerShape(8.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(mod.enabled, onCheckedChange = {
                        onStatus(store.toggleMod(mod))
                        mods = store.scanMods(config)
                        onInstancesChanged()
                    })
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mod.name, fontWeight = FontWeight.Bold)
                        Text("${mod.version}  •  ${mod.side}", color = MutedText, fontSize = 12.sp)
                        Text(mod.description, color = Color(0xffc7d2e2), fontSize = 12.sp)
                        Text(mod.path.fileName.toString(), color = MutedText, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onStatus(openPath(mod.path.parent)) }) { Text("Folder") }
                        OutlinedButton(onClick = { deleteTarget = mod }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsTab(logs: List<String>, onStatus: (String) -> Unit, onClearLogs: () -> Unit, logDir: java.nio.file.Path?) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Live log (${logs.size} lines)", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onClearLogs(); onStatus("Cleared live log") }) { Text("Clear") }
            OutlinedButton(enabled = logDir != null, onClick = { logDir?.let { onStatus(openPath(it)) } }) { Text("Open Logs Folder") }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xff06090f)).border(1.dp, SoftLine, RoundedCornerShape(8.dp)).padding(12.dp)) {
                if (logs.isEmpty()) item { Text("No log output yet. Launch an instance to see live output here.", color = MutedText, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                items(logs) { line -> Text(line, color = Color(0xffd1d7e0), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

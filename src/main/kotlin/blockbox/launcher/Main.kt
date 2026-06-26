package blockbox.launcher

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val store = remember { LauncherStore() }
    val runner = remember { GameRunner(store) }
    val instances = remember { mutableStateListOf<InstanceConfig>() }
    val logs = remember { mutableStateListOf<String>() }
    var selected by remember { mutableStateOf<InstanceConfig?>(null) }
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Ready") }

    fun reloadInstances() {
        val selectedId = selected?.id
        instances.clear()
        instances += store.loadInstances()
        selected = instances.firstOrNull { it.id == selectedId } ?: instances.firstOrNull()
    }

    LaunchedEffect(Unit) { reloadInstances() }

    Window(onCloseRequest = ::exitApplication, title = "Blockbox Launcher") {
        MaterialTheme(
            colors = MaterialTheme.colors.copy(
                primary = AppAccent,
                secondary = AppGold,
                background = AppBg,
                surface = CardBg,
                onPrimary = Color.White,
                onSecondary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White
            )
        ) {
            LauncherApp(
                store = store,
                runner = runner,
                instances = instances,
                selected = selected,
                logs = logs,
                tab = tab,
                status = status,
                onSelect = { selected = it },
                onTab = { tab = it },
                onStatus = { status = it },
                onExitLauncher = ::exitApplication,
                onInstancesChanged = ::reloadInstances,
                onLog = { line ->
                    logs += line
                    while (logs.size > 900) logs.removeAt(0)
                }
            )
        }
    }
}

package blockbox.launcher

import java.nio.file.Path
import java.util.UUID

const val PACK_EXTENSION = "bbpack"

data class InstanceConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Instance",
    var description: String = "A clean Blockbox profile",
    var javaPath: String = "java",
    var minMemoryMb: Int = 1024,
    var maxMemoryMb: Int = 4096,
    var jvmArgs: String = "-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions",
    var gameArgs: String = "",
    var envVars: String = "",
    var useGameMode: Boolean = false,
    var forceX11: Boolean = false,
    var displayBackend: String = "auto",
    var closeLauncherOnGameStart: Boolean = false,
    var lastPlayed: String = "Never"
)

data class BlockboxMod(
    val path: Path,
    val enabled: Boolean,
    val name: String,
    val version: String,
    val side: String,
    val description: String
)

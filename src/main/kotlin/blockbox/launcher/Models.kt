package blockbox.launcher

import java.nio.file.Path
import java.util.UUID

const val PACK_EXTENSION = "bbpack"
const val LAUNCHER_VERSION = "1.2.0"
val DISPLAY_BACKENDS = listOf("auto", "x11", "wayland", "x11-nvidia", "software")

data class InstanceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Instance",
    val description: String = "A clean Blockbox profile",
    val javaPath: String = "java",
    val minMemoryMb: Int = 512,
    val maxMemoryMb: Int = 4096,
    val jvmArgs: String = "-XX:+UseG1GC",
    val gameArgs: String = "",
    val envVars: String = "",
    val useGameMode: Boolean = false,
    val displayBackend: String = "auto",
    val closeLauncherOnGameStart: Boolean = false,
    val lastPlayed: String = "Never"
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) && id != "." && id != "..") { "Invalid instance identifier." }
        require(name.isNotBlank() && name.length <= 80) { "Enter an instance name of 1–80 characters." }
        require(javaPath.isNotBlank()) { "Enter a Java executable or use java for automatic selection." }
        require(minMemoryMb in 128..1_048_576) { "Minimum memory must be between 128 and 1048576 MiB." }
        require(maxMemoryMb in minMemoryMb..1_048_576) { "Maximum memory must be at least the minimum memory." }
        splitCommandLine(jvmArgs).forEach {
            require(!it.startsWith("-Xm") && !it.startsWith("-XX:MaxRAM") && !it.startsWith("-XX:InitialRAM")) { "Set memory using the memory fields, rather than JVM arguments." }
        }
        splitCommandLine(gameArgs)
        parseEnv(envVars)
        require(displayBackend in DISPLAY_BACKENDS) { "Unknown graphics backend: $displayBackend" }
    }
}

data class BlockboxMod(val path: Path, val enabled: Boolean, val name: String, val version: String, val side: String, val description: String)
enum class RunState { IDLE, STARTING, RUNNING, STOPPING }

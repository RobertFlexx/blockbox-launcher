package blockbox.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

class GameRunner(private val store: LauncherStore) {
    var process: Process? = null
        private set

    fun launch(config: InstanceConfig, onLog: (String) -> Unit, onExit: (Int) -> Unit) {
        if (process?.isAlive == true) {
            onLog("A game is already running.")
            return
        }
        val instanceDir = store.instanceDir(config)
        val logDir = store.logsDir(config)
        logDir.createDirectories()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now())
        val logFile = logDir.resolve("launch-$stamp.log")
        val linuxScript = store.projectRoot.resolve("scripts/run.sh")
        val windowsScript = store.projectRoot.resolve("scripts/run-blockbox-windows.ps1")
        val command = if (System.getProperty("os.name").lowercase().contains("win")) {
            mutableListOf("powershell", "-ExecutionPolicy", "Bypass", "-File", windowsScript.absolutePathString())
        } else {
            val base = mutableListOf<String>()
            if (config.useGameMode && commandExists("gamemoderun")) base += "gamemoderun"
            base += listOf("bash", linuxScript.absolutePathString())
            base
        }
        val builder = ProcessBuilder(command)
        builder.directory(instanceDir.toFile())
        builder.redirectErrorStream(true)
        val jvmArgFile = instanceDir.resolve(".launcher-jvm.args")
        val gameArgFile = instanceDir.resolve(".launcher-game.args")
        val jvmArgs = listOf("-Xms${config.minMemoryMb}M", "-Xmx${config.maxMemoryMb}M") + splitCommandLine(config.jvmArgs)
        val gameArgs = splitCommandLine(config.gameArgs)
        jvmArgFile.writeText(jvmArgs.joinToString("\n") + "\n")
        gameArgFile.writeText(gameArgs.joinToString("\n") + "\n")
        builder.environment()["BLOCKBOX_INSTANCE"] = config.name
        builder.environment()["BLOCKBOX_JAVA"] = config.javaPath
        builder.environment()["BLOCKBOX_JVM_ARGS_FILE"] = jvmArgFile.absolutePathString()
        builder.environment()["BLOCKBOX_GAME_ARGS_FILE"] = gameArgFile.absolutePathString()
        builder.environment()["BLOCKBOX_JVM_ARGS"] = jvmArgs.joinToString(" ")
        builder.environment()["BLOCKBOX_GAME_ARGS"] = gameArgs.joinToString(" ")
        if (config.forceX11) builder.environment()["GLFW_PLATFORM"] = "x11"
        parseEnv(config.envVars).forEach { (key, value) -> builder.environment()[key] = value }
        onLog("Launching ${config.name}")
        onLog("Working directory: ${instanceDir.absolutePathString()}")
        onLog("Command: ${command.joinToString(" ")}")
        onLog("JVM args: ${jvmArgs.joinToString(" ")}")
        if (gameArgs.isNotEmpty()) onLog("Game args: ${gameArgs.joinToString(" ")}")
        onLog("Log file: ${logFile.absolutePathString()}")
        config.lastPlayed = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        store.saveInstance(config)
        val started = builder.start()
        process = started
        thread(name = "blockbox-log-reader", isDaemon = true) {
            logFile.outputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                    lines.forEach { line ->
                        val clean = stripAnsi(line)
                        writer.appendLine(clean)
                        writer.flush()
                        onLog(clean)
                    }
                }
            }
        }
        thread(name = "blockbox-exit-waiter", isDaemon = true) {
            val code = started.waitFor()
            onLog("Blockbox exited with code $code")
            process = null
            onExit(code)
        }
    }

    fun stop() {
        process?.destroy()
    }
}

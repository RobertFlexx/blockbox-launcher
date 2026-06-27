package blockbox.launcher

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GameRunner(private val store: LauncherStore) {
    var process: Process? = null
        private set
    @Volatile private var emittedOpenGlHint = false
    @Volatile private var autoRelaunchConfig: InstanceConfig? = null

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
        val scriptProblem = validateGameRunner(linuxScript, windowsScript)
        if (scriptProblem != null) {
            onLog(scriptProblem)
            onExit(1)
            return
        }
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
        config.javaPath.trim().takeIf { it.isNotEmpty() && it != "java" }?.let { builder.environment()["BLOCKBOX_JAVA"] = it }
        builder.environment()["BLOCKBOX_JVM_ARGS_FILE"] = jvmArgFile.absolutePathString()
        builder.environment()["BLOCKBOX_GAME_ARGS_FILE"] = gameArgFile.absolutePathString()
        builder.environment()["BLOCKBOX_JVM_ARGS"] = jvmArgs.joinToString(" ")
        builder.environment()["BLOCKBOX_GAME_ARGS"] = gameArgs.joinToString(" ")
        builder.environment()["PATH"] = launcherPath(builder.environment()["PATH"], builder.environment()["HOME"] ?: System.getProperty("user.home"))
        val backend = config.displayBackend.lowercase()
        val isWaylandSession = System.getenv("XDG_SESSION_TYPE")?.lowercase() == "wayland" ||
            System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true
        val hasNvidiaDrm = nvidiaDrmAvailable()
        val x11SocketAvailable = Path.of("/tmp/.X11-unix").takeIf { it.exists() }?.let { dir ->
            try { Files.list(dir).anyMatch { it.fileName.toString().startsWith("X") } } catch (_: Exception) { false }
        } ?: false
        builder.environment()["BLOCKBOX_DISPLAY_BACKEND"] = backend
        when (backend) {
            "x11", "x11-nvidia" -> {
                if (!x11SocketAvailable) {
                    onLog("No X11 socket found at /tmp/.X11-unix/ — falling back to Wayland for this launch. Install XWayland if you need X11.")
                    builder.environment()["GLFW_PLATFORM"] = "wayland"
                    if (hasNvidiaDrm) {
                        setupNvidiaEgl(builder.environment())
                    }
                } else {
                    builder.environment()["GLFW_PLATFORM"] = "x11"
                }
                if (backend == "x11-nvidia") {
                    builder.environment().putIfAbsent("__GLX_VENDOR_LIBRARY_NAME", "nvidia")
                    builder.environment().putIfAbsent("__GL_SYNC_TO_VBLANK", "1")
                }
            }
            "wayland" -> {
                builder.environment()["GLFW_PLATFORM"] = "wayland"
                builder.environment().putIfAbsent("_JAVA_AWT_WM_NONREPARENTING", "1")
                builder.environment().putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0")
                if (nvidiaDrmAvailable()) {
                    setupNvidiaEgl(builder.environment())
                }
            }
            "software" -> {
                builder.environment().remove("GLFW_PLATFORM")
                builder.environment()["LIBGL_ALWAYS_SOFTWARE"] = "1"
                builder.environment()["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
                builder.environment()["GALLIUM_DRIVER"] = "llvmpipe"
            }
            else -> {
                builder.environment().remove("GLFW_PLATFORM")
                if (isWaylandSession && hasNvidiaDrm) {
                    setupNvidiaEgl(builder.environment())
                }
            }
        }
        parseEnv(config.envVars).forEach { (key, value) -> builder.environment()[key] = value }
        emittedOpenGlHint = false
        onLog("Launching ${config.name}")
        onLog("Working directory: ${instanceDir.absolutePathString()}")
        onLog("Command: ${command.joinToString(" ")}")
        onLog("JVM args: ${jvmArgs.joinToString(" ")}")
        onLog("Launch options: GameMode=${config.useGameMode}, Display=${backend}, GLFW_PLATFORM=${builder.environment()["GLFW_PLATFORM"] ?: "auto"}")
        listOf("DISPLAY", "WAYLAND_DISPLAY", "XDG_SESSION_TYPE", "_JAVA_AWT_WM_NONREPARENTING", "GLFW_WAYLAND_LIBDECOR", "LD_PRELOAD", "__EGL_VENDOR_LIBRARY_FILENAMES", "__EGL_EXTERNAL_PLATFORM_CONFIG_DIRS", "__NV_PRIME_RENDER_OFFLOAD", "__GLX_VENDOR_LIBRARY_NAME", "__GL_SYNC_TO_VBLANK", "LIBGL_ALWAYS_SOFTWARE", "MESA_LOADER_DRIVER_OVERRIDE", "GALLIUM_DRIVER").forEach { key ->
            builder.environment()[key]?.takeIf { it.isNotBlank() }?.let { value -> onLog("Env $key=$value") }
        }
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
                        val glFailure = clean.contains("There is no OpenGL context current", ignoreCase = true) || clean.contains("OpenGL context setup failed", ignoreCase = true)
                        if (!emittedOpenGlHint && glFailure) {
                            emittedOpenGlHint = true
                            config.useGameMode = false
                            config.forceX11 = false
                            val onWayland = clean.contains("XDG_SESSION_TYPE=wayland", ignoreCase = true)
                            val forcedX11 = clean.contains("GLFW_PLATFORM=x11", ignoreCase = true)
                            config.displayBackend = if (onWayland && forcedX11) "x11-nvidia" else "auto"
                            store.saveInstance(config)
                            autoRelaunchConfig = config.copy()
                            val hint = buildString {
                                append("Launcher hint: OpenGL context creation failed. GameMode was disabled. Auto-relaunching with corrected display backend.")
                                if (onWayland && forcedX11) {
                                    append(" X11 failed on a Wayland session — keeping X11 NVIDIA/GLX for relaunch instead of switching to Wayland.")
                                    append(" If it still fails, try Auto or Software fallback.")
                                } else {
                                    append(" Display backend set to Auto for relaunch.")
                                    append(" If it still fails, try Wayland, then X11, then X11 NVIDIA/GLX, then Software fallback.")
                                }
                            }
                            writer.appendLine(hint)
                            writer.flush()
                            onLog(hint)
                        }
                    }
                }
            }
        }
        thread(name = "blockbox-exit-waiter", isDaemon = true) {
            val code = started.waitFor()
            onLog("Blockbox exited with code $code")
            process = null
            val relaunchConfig = autoRelaunchConfig
            autoRelaunchConfig = null
            if (relaunchConfig != null) {
                onLog("Auto-relaunching with config: display=${relaunchConfig.displayBackend}, GameMode=${relaunchConfig.useGameMode}")
                emittedOpenGlHint = false
                launch(relaunchConfig, onLog, onExit)
            } else {
                onExit(code)
            }
        }
    }

    fun stop() {
        process?.destroy()
    }

    private fun nvidiaDrmAvailable(): Boolean = try {
        val process = ProcessBuilder("lsmod").start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor() == 0 && text.contains("nvidia_drm")
    } catch (_: Exception) { false }

    private fun setupNvidiaEgl(env: MutableMap<String, String>) {
        env.putIfAbsent("__NV_PRIME_RENDER_OFFLOAD", "1")
        env.putIfAbsent("__GLX_VENDOR_LIBRARY_NAME", "nvidia")
        env.putIfAbsent("__EGL_EXTERNAL_PLATFORM_CONFIG_DIRS", "/usr/share/egl/egl_external_platform.d")
        nvidiaEglVendorOverride()?.let { env.putIfAbsent("__EGL_VENDOR_LIBRARY_FILENAMES", it) }
    }

    // Force GLVND to use the NVIDIA EGL vendor instead of Mesa's, which falls
    // back to llvmpipe on NVIDIA hardware. Points to the NVIDIA EGL vendor JSON.
    private fun nvidiaEglVendorOverride(): String? {
        val systemFile = Path.of("/usr/share/glvnd/egl_vendor.d/10_nvidia.json")
        val userFile = Path.of(System.getProperty("user.home"), ".local/share/egl-vendor-override/10_nvidia.json")
        return when {
            systemFile.exists() -> systemFile.absolutePathString()
            userFile.exists() -> userFile.absolutePathString()
            else -> null
        }
    }

    private fun launcherPath(existing: String?, home: String): String {
        val prefixes = listOf(
            "$home/.linuxbrew/bin",
            "$home/.linuxbrew/sbin",
            "/home/linuxbrew/.linuxbrew/bin",
            "/home/linuxbrew/.linuxbrew/sbin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin",
            "/usr/local/bin",
            "/usr/local/sbin",
            "/usr/bin",
            "/bin"
        )
        val parts = (prefixes + (existing ?: "").split(':')).filter { it.isNotBlank() }.distinct()
        return parts.joinToString(":")
    }

    private fun validateGameRunner(linuxScript: Path, windowsScript: Path): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val script = if (isWindows) windowsScript else linuxScript
        if (!script.exists()) {
            return "Blockbox run script not found: ${script.absolutePathString()}\nSet BLOCKBOX_GAME_ROOT to a valid Blockbox checkout."
        }
        if (!store.projectRoot.resolve("src/main/scala/blockbox/Main.scala").exists()) {
            return "Blockbox source not found under: ${store.projectRoot.absolutePathString()}\nSet BLOCKBOX_GAME_ROOT to the folder containing src/main/scala/blockbox/Main.scala."
        }
        if (!isWindows) {
            val text = script.readText()
            val oldRelativeRunner = text.contains("scala-cli run \"src/main/scala\"") || text.contains("scala-cli run src/main/scala")
            val hasPortableSourceRoot = text.contains("SCALA_SOURCE") || text.contains("PROJECT_ROOT")
            if (oldRelativeRunner && !hasPortableSourceRoot) {
                return "Your Blockbox scripts/run.sh is too old for launcher instances.\nIt runs src/main/scala relative to the instance folder, so scala-cli cannot find the game source.\nUpdate the Blockbox game repo, then run again."
            }
        }
        return null
    }
}

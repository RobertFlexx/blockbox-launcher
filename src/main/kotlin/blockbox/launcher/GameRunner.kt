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
import kotlin.io.path.isExecutable
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GameRunner(private val store: LauncherStore) {
    var process: Process? = null
        private set

    @Volatile private var emittedOpenGlHint = false
    @Volatile private var autoRelaunchConfig: InstanceConfig? = null
    @Volatile private var stopping = false
    @Volatile private var autoRelaunchAttempts = 0

    fun launch(config: InstanceConfig, onLog: (String) -> Unit, onExit: (Int) -> Unit) {
        if (process?.isAlive == true) {
            onLog("A game is already running.")
            return
        }

        stopping = false
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

        val env = builder.environment()
        env["PATH"] = launcherPath(env["PATH"], env["HOME"] ?: System.getProperty("user.home"))
        cleanGraphicsEnv(env)

        val jvmArgFile = instanceDir.resolve(".launcher-jvm.args")
        val gameArgFile = instanceDir.resolve(".launcher-game.args")
        val jvmArgs = listOf("-Xms${config.minMemoryMb}M", "-Xmx${config.maxMemoryMb}M") + splitCommandLine(config.jvmArgs)
        val gameArgs = splitCommandLine(config.gameArgs)

        jvmArgFile.writeText(jvmArgs.joinToString("\n") + "\n")
        gameArgFile.writeText(gameArgs.joinToString("\n") + "\n")

        env["BLOCKBOX_INSTANCE"] = config.name
        config.javaPath.trim().takeIf { it.isNotEmpty() && it != "java" }?.let { javaPath ->
            val p = Path.of(javaPath)
            env["BLOCKBOX_JAVA"] = javaPath
            val binDir = p.parent
            val javaHome = binDir?.parent
            if (p.fileName?.toString() == "java" && binDir?.fileName?.toString() == "bin" && javaHome != null) {
                env["BLOCKBOX_JAVA_HOME"] = javaHome.absolutePathString()
            }
        }

        env["BLOCKBOX_JVM_ARGS_FILE"] = jvmArgFile.absolutePathString()
        env["BLOCKBOX_GAME_ARGS_FILE"] = gameArgFile.absolutePathString()
        env["BLOCKBOX_JVM_ARGS"] = jvmArgs.joinToString(" ")
        env["BLOCKBOX_GAME_ARGS"] = gameArgs.joinToString(" ")

        val requestedBackend = config.displayBackend.trim().lowercase().ifBlank { "auto" }
        val backend = when (requestedBackend) {
            "auto", "x11", "x11-nvidia", "wayland", "software", "legacy", "null", "headless" -> requestedBackend
            else -> "auto"
        }

        val isWaylandSession = env["XDG_SESSION_TYPE"]?.lowercase() == "wayland" ||
            env["WAYLAND_DISPLAY"]?.isNotBlank() == true ||
            System.getenv("XDG_SESSION_TYPE")?.lowercase() == "wayland" ||
            System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true

        val hasNvidiaDrm = nvidiaDrmAvailable()
        val x11SocketAvailable = x11SocketAvailable()

        env["BLOCKBOX_DISPLAY_BACKEND"] = backend

        when (backend) {
            "x11", "x11-nvidia" -> {
                if (x11SocketAvailable) {
                    env["GLFW_PLATFORM"] = "x11"
                    setupNvidiaGlx(env)
                } else {
                    onLog("No X11 socket found at /tmp/.X11-unix/ — falling back to Wayland for this launch.")
                    env["GLFW_PLATFORM"] = "wayland"
                    env.putIfAbsent("_JAVA_AWT_WM_NONREPARENTING", "1")
                    env.putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0")
                    if (hasNvidiaDrm) setupNvidiaGlx(env)
                }
            }

            "wayland" -> {
                env["GLFW_PLATFORM"] = "wayland"
                env.putIfAbsent("_JAVA_AWT_WM_NONREPARENTING", "1")
                env.putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0")
                if (hasNvidiaDrm) setupNvidiaGlx(env)
            }

            "software" -> {
                env.remove("GLFW_PLATFORM")
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
                env["GALLIUM_DRIVER"] = "llvmpipe"
            }

            "null", "headless" -> {
                env["GLFW_PLATFORM"] = "null"
            }

            "legacy" -> {
                if (isWaylandSession) {
                    env["GLFW_PLATFORM"] = "wayland"
                    env.putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0")
                } else if (x11SocketAvailable) {
                    env["GLFW_PLATFORM"] = "x11"
                } else {
                    env.remove("GLFW_PLATFORM")
                }
                if (hasNvidiaDrm) setupNvidiaGlx(env)
            }

            else -> {
                // Auto: do not force GLFW_PLATFORM. Let GLFW choose X11/Wayland.
                env.remove("GLFW_PLATFORM")
                if (isWaylandSession) {
                    env.putIfAbsent("_JAVA_AWT_WM_NONREPARENTING", "1")
                    env.putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0")
                }
                if (hasNvidiaDrm) setupNvidiaGlx(env)
            }
        }

        parseEnv(config.envVars).forEach { (key, value) ->
            // User-specified env vars intentionally win.
            env[key] = value
        }

        emittedOpenGlHint = false
        onLog("Launching ${config.name}")
        onLog("Working directory: ${instanceDir.absolutePathString()}")
        onLog("Command: ${command.joinToString(" ")}")
        onLog("JVM args: ${jvmArgs.joinToString(" ")}")
        onLog("Launch options: GameMode=${config.useGameMode}, Display=$backend, GLFW_PLATFORM=${env["GLFW_PLATFORM"] ?: "auto"}")
        onLog("Graphics selection: WaylandSession=$isWaylandSession, X11Socket=$x11SocketAvailable, NvidiaDRM=$hasNvidiaDrm")

        listOf(
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XDG_SESSION_TYPE",
            "_JAVA_AWT_WM_NONREPARENTING",
            "GLFW_WAYLAND_LIBDECOR",
            "LD_PRELOAD",
            "__EGL_VENDOR_LIBRARY_FILENAMES",
            "__EGL_EXTERNAL_PLATFORM_CONFIG_DIRS",
            "__NV_PRIME_RENDER_OFFLOAD",
            "__GLX_VENDOR_LIBRARY_NAME",
            "__GL_SYNC_TO_VBLANK",
            "LIBGL_ALWAYS_SOFTWARE",
            "MESA_LOADER_DRIVER_OVERRIDE",
            "GALLIUM_DRIVER",
            "JAVA_HOME",
            "BLOCKBOX_JAVA",
            "BLOCKBOX_JAVA_HOME"
        ).forEach { key ->
            env[key]?.takeIf { it.isNotBlank() }?.let { value -> onLog("Env $key=$value") }
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

                        val glFailure =
                            clean.contains("There is no OpenGL context current", ignoreCase = true) ||
                            clean.contains("OpenGL context setup failed", ignoreCase = true) ||
                            clean.contains("GLFW_PLATFORM_ERROR", ignoreCase = true)

                        val softwareRenderer =
                            clean.contains("renderer=llvmpipe", ignoreCase = true) ||
                            clean.contains("Software OpenGL renderer detected", ignoreCase = true)

                        if (!emittedOpenGlHint && (glFailure || softwareRenderer)) {
                            emittedOpenGlHint = true
                            config.useGameMode = false
                            config.forceX11 = false

                            val current = config.displayBackend.trim().lowercase().ifBlank { "auto" }
                            val nextBackend = chooseRecoveryBackend(
                                current = current,
                                glFailure = glFailure,
                                softwareRenderer = softwareRenderer,
                                x11SocketAvailable = x11SocketAvailable,
                                isWaylandSession = isWaylandSession
                            )

                            val hint = buildString {
                                append("Launcher hint: OpenGL issue detected. GameMode disabled.")
                                if (softwareRenderer) {
                                    append(" The game created a software llvmpipe renderer, so this launch is being treated as broken.")
                                }
                                append(" Current backend=$current, next backend=$nextBackend.")
                                append(" EGL vendor and PRIME offload are no longer forced by the launcher.")
                            }

                            writer.appendLine(hint)
                            writer.flush()
                            onLog(hint)

                            if (autoRelaunchAttempts < 2 && nextBackend != current && nextBackend != "software") {
                                autoRelaunchAttempts += 1
                                config.displayBackend = nextBackend
                                store.saveInstance(config)
                                autoRelaunchConfig = config.copy()
                                writer.appendLine("Launcher hint: Auto-relaunching attempt $autoRelaunchAttempts/2.")
                                writer.flush()
                                onLog("Launcher hint: Auto-relaunching attempt $autoRelaunchAttempts/2.")

                                // glFailure usually exits by itself. llvmpipe often keeps running,
                                // so terminate it to retry with a better backend.
                                if (softwareRenderer && started.isAlive) {
                                    started.destroy()
                                }
                            } else {
                                writer.appendLine("Launcher hint: Not auto-relaunching again. Try Display=Auto, Wayland, X11 NVIDIA/GLX, then Software manually.")
                                writer.flush()
                                onLog("Launcher hint: Not auto-relaunching again. Try Display=Auto, Wayland, X11 NVIDIA/GLX, then Software manually.")
                            }
                        }
                    }
                }
            }
        }

        thread(name = "blockbox-exit-waiter", isDaemon = true) {
            val code = started.waitFor()
            onLog("Blockbox exited with code $code")
            process = null

            val wasStopping = stopping
            stopping = false

            val relaunchConfig = if (wasStopping) null else autoRelaunchConfig
            autoRelaunchConfig = null

            if (relaunchConfig != null) {
                onLog("Auto-relaunching with config: display=${relaunchConfig.displayBackend}, GameMode=${relaunchConfig.useGameMode}")
                emittedOpenGlHint = false
                launch(relaunchConfig, onLog, onExit)
            } else {
                autoRelaunchAttempts = 0
                onExit(code)
            }
        }
    }

    fun stop() {
        val running = process ?: return
        stopping = true
        autoRelaunchConfig = null

        thread(name = "blockbox-stop", isDaemon = true) {
            val handles = (running.toHandle().descendants().toList() + running.toHandle())
                .sortedByDescending { it.pid() }

            handles.forEach { handle -> if (handle.isAlive) handle.destroy() }
            Thread.sleep(1500)
            handles.forEach { handle -> if (handle.isAlive) handle.destroyForcibly() }
        }
    }

    private fun chooseRecoveryBackend(
        current: String,
        glFailure: Boolean,
        softwareRenderer: Boolean,
        x11SocketAvailable: Boolean,
        isWaylandSession: Boolean
    ): String {
        return when {
            softwareRenderer && x11SocketAvailable && current != "x11-nvidia" -> "x11-nvidia"
            glFailure && current == "x11" && isWaylandSession -> "auto"
            glFailure && current == "x11-nvidia" && isWaylandSession -> "auto"
            glFailure && current == "wayland" && x11SocketAvailable -> "x11-nvidia"
            glFailure && current == "auto" && x11SocketAvailable -> "x11-nvidia"
            glFailure && current == "auto" && isWaylandSession -> "wayland"
            current != "auto" -> "auto"
            else -> current
        }
    }

    private fun x11SocketAvailable(): Boolean {
        val dir = Path.of("/tmp/.X11-unix")
        if (!dir.exists()) return false
        return try {
            Files.list(dir).use { stream ->
                stream.anyMatch { it.fileName.toString().startsWith("X") && Files.isRegularFile(it).not() }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun nvidiaDrmAvailable(): Boolean = try {
        Files.readString(Path.of("/proc/modules")).contains("nvidia_drm")
    } catch (_: Exception) {
        false
    }

    private fun cleanGraphicsEnv(env: MutableMap<String, String>) {
        if (env["BLOCKBOX_KEEP_SOFTWARE_GL_ENV"] != "1") {
            env.remove("LIBGL_ALWAYS_SOFTWARE")
            env.remove("MESA_LOADER_DRIVER_OVERRIDE")
            env.remove("GALLIUM_DRIVER")
        }

        if (env["BLOCKBOX_KEEP_EGL_OVERRIDES"] != "1") {
            env.remove("__EGL_VENDOR_LIBRARY_FILENAMES")
            env.remove("__EGL_EXTERNAL_PLATFORM_CONFIG_DIRS")
        }

        if (env["BLOCKBOX_NVIDIA_PRIME_OFFLOAD"] != "1") {
            env.remove("__NV_PRIME_RENDER_OFFLOAD")
        } else {
            env["__NV_PRIME_RENDER_OFFLOAD"] = "1"
        }
    }

    private fun setupNvidiaGlx(env: MutableMap<String, String>) {
        // This helps X11/XWayland GLX choose NVIDIA, and does not force EGL vendor JSON.
        env.putIfAbsent("__GLX_VENDOR_LIBRARY_NAME", "nvidia")
        env.putIfAbsent("__GL_SYNC_TO_VBLANK", "1")
    }

    private fun launcherPath(existing: String?, home: String): String {
        val base = (existing ?: "").split(':').filter { it.isNotBlank() }

        // System paths first. Homebrew is useful for scala-cli but must not hijack Java/OpenGL.
        val system = listOf(
            "/opt/openjdk-bin-21.0.11_p10/bin",
            "/opt/openjdk-bin-21/bin",
            "/usr/x86_64-pc-linux-gnu/bin",
            "/usr/local/bin",
            "/usr/local/sbin",
            "/usr/bin",
            "/bin"
        )

        val brew = listOf(
            "$home/.linuxbrew/bin",
            "$home/.linuxbrew/sbin",
            "/home/linuxbrew/.linuxbrew/bin",
            "/home/linuxbrew/.linuxbrew/sbin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin"
        )

        return (system + base + brew)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
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
            val oldRelativeRunner = text.contains("scala-cli run \"src/main/scala\"") ||
                text.contains("scala-cli run src/main/scala")
            val hasPortableSourceRoot = text.contains("SCALA_SOURCE") || text.contains("PROJECT_ROOT")

            if (oldRelativeRunner && !hasPortableSourceRoot) {
                return "Your Blockbox scripts/run.sh is too old for launcher instances.\nIt runs src/main/scala relative to the instance folder, so scala-cli cannot find the game source.\nUpdate the Blockbox game repo, then run again."
            }
        }

        return null
    }

    private fun commandExists(command: String): Boolean {
        if (command.contains('/')) {
            return Path.of(command).isExecutable()
        }

        return (System.getenv("PATH") ?: "")
            .split(':')
            .filter { it.isNotBlank() }
            .map { Path.of(it, command) }
            .any { it.isExecutable() }
    }

    private fun splitCommandLine(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var escaped = false

        for (ch in input) {
            when {
                escaped -> {
                    cur.append(ch)
                    escaped = false
                }

                ch == '\\' -> escaped = true

                quote != null -> {
                    if (ch == quote) quote = null else cur.append(ch)
                }

                ch == '\'' || ch == '"' -> quote = ch

                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.setLength(0)
                    }
                }

                else -> cur.append(ch)
            }
        }

        if (escaped) cur.append('\\')
        if (cur.isNotEmpty()) out += cur.toString()

        return out
    }

    private fun parseEnv(input: String): Map<String, String> {
        if (input.isBlank()) return emptyMap()

        val result = linkedMapOf<String, String>()

        input.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().trimMatchingQuotes()
                    if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        result[key] = value
                    }
                }
            }

        return result
    }

    private fun String.trimMatchingQuotes(): String {
        if (length >= 2) {
            val first = first()
            val last = last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return substring(1, length - 1)
            }
        }
        return this
    }

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[;?0-9]*[ -/]*[@-~]"), "")
    }
}

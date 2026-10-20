package blockbox.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*

data class LaunchPlan(val command: List<String>, val directory: Path, val environment: Map<String, String>)

class LaunchPlanner @JvmOverloads constructor(
    private val store: LauncherStore,
    private val platform: HostPlatform = HostPlatform.detect(),
    private val arch: String = System.getProperty("os.arch"),
    private val inheritedEnv: Map<String, String> = System.getenv(),
    private val resolveNative: ((String, String, String) -> Path)? = null
) {
    @JvmOverloads fun prepare(config: InstanceConfig, log: (String) -> Unit = {}, cancelled: () -> Boolean = { false }, onProcess: (Process?) -> Unit = {}): LaunchPlan {
        config.validate()
        val main = store.projectRoot.resolve("src/main/scala/blockbox/Main.scala")
        require(main.isRegularFile()) { "Blockbox source not found. Use Launcher → Game location to choose your Blockbox checkout." }
        val env = inheritedEnv.toMutableMap()
        // Preserve PATH and its platform separator. Add common GUI-session omissions.
        val pathKey = env.keys.firstOrNull { it.equals("PATH", platform == HostPlatform.WINDOWS) } ?: "PATH"
        val home = Path.of(System.getProperty("user.home"))
        val extras = if (platform == HostPlatform.WINDOWS) listOf(home.resolve("AppData/Local/Programs/Scala CLI"))
            else listOf(home.resolve(".local/bin"), home.resolve(".local/share/coursier/bin"), Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"), Path.of("/home/linuxbrew/.linuxbrew/bin"))
        env[pathKey] = (env[pathKey].orEmpty().split(platform.pathSeparator) + extras.filter { it.isDirectory() }.map { it.toString() }).filter { it.isNotBlank() }.distinct().joinToString(platform.pathSeparator)

        applyGraphicsEnvironment(env, config.displayBackend, platform)
        // Explicit per-instance overrides win; no environment values are logged.
        parseEnv(config.envVars).forEach { (key, value) ->
            val existing = if (platform == HostPlatform.WINDOWS) env.keys.firstOrNull { it.equals(key, true) } else null
            if (existing != null && existing != key) env.remove(existing)
            env[key] = value
        }
        val javaHome = gameJavaHome(config, env, platform)
            ?: error("A JDK is required to build Blockbox. Install JDK 21+ or select its java executable in Settings.")
        env["JAVA_HOME"] = javaHome.toString()
        val effectivePathKey = env.keys.firstOrNull { it.equals("PATH", platform == HostPlatform.WINDOWS) } ?: "PATH"
        env[effectivePathKey] = javaHome.resolve("bin").toString() + platform.pathSeparator + env[effectivePathKey].orEmpty()
        env["BLOCKBOX_PROJECT_ROOT"] = store.projectRoot.toString()
        env["BLOCKBOX_INSTANCE"] = config.name

        val scala = findExecutable(env["BLOCKBOX_SCALA_CLI"] ?: "scala-cli", env, platform)
            ?: error("Scala CLI was not found. Install scala-cli or set BLOCKBOX_SCALA_CLI to its executable.")
        val buildCache = GameBuildCache(store.launcherRoot.resolve("cache"))
        val command = mutableListOf(scala.toString(), "--power", "run", store.projectRoot.resolve("src/main/scala").toString(), "--workspace", store.instanceDir(config).resolve(".build").toString(), "--server=false", "--java-home", javaHome.toString())
        buildCache.javaHelpers(store.projectRoot, javaHome, env, log, cancelled, onProcess)?.let { command += "--extra-jar=$it" }
        val jvmArgs = listOf("-Xms${config.minMemoryMb}M", "-Xmx${config.maxMemoryMb}M", "--enable-native-access=ALL-UNNAMED") + splitCommandLine(config.jvmArgs)
        if (platform == HostPlatform.MACOS) command += "--java-opt=-XstartOnFirstThread"
        jvmArgs.forEach { command += "--java-opt=$it" }

        val customNatives = env["BLOCKBOX_LWJGL_NATIVE_DIR"]?.takeIf { it.isNotBlank() }
        if (customNatives != null) {
            require(Path.of(customNatives).isDirectory()) { "BLOCKBOX_LWJGL_NATIVE_DIR must point to a folder of native LWJGL libraries." }
            command += "--java-opt=-Dorg.lwjgl.librarypath=${Path.of(customNatives).toAbsolutePath()}"
        } else {
            val classifier = nativeClassifier(platform, arch)
                ?: error("No bundled LWJGL natives for $platform/$arch. Set BLOCKBOX_LWJGL_NATIVE_DIR to libraries built for this system.")
            val dependencies = Regex("org\\.lwjgl:(lwjgl(?:-[a-z0-9-]+)?):([0-9][A-Za-z0-9.+_-]*)").findAll(main.readText())
                .map { it.groupValues[1] to it.groupValues[2] }.distinct().toList()
            require(dependencies.isNotEmpty()) { "No LWJGL dependency versions were found in Main.scala." }
            dependencies.forEach { (module, version) ->
                val jar = resolveNative?.invoke(module, version, classifier) ?: buildCache.nativeJar(module, version, classifier, log, cancelled)
                // Classifier dependencies can replace the unclassified module
                // in Scala CLI. Extra JARs retain both the API and native code.
                command += "--extra-jar=$jar"
            }
        }
        if (platform.unixDesktop && config.displayBackend in listOf("x11", "x11-nvidia")) command += "--java-opt=-Dorg.lwjgl.opengl.contextAPI=native"
        if (platform.unixDesktop && config.displayBackend == "wayland") command += "--java-opt=-Dorg.lwjgl.opengl.contextAPI=EGL"
        val args = splitCommandLine(config.gameArgs)
        if (args.isNotEmpty()) { command += "--"; command += args }
        if (platform == HostPlatform.LINUX && config.useGameMode) {
            val gameMode = findExecutable("gamemoderun", env, platform) ?: error("GameMode is enabled but gamemoderun is not installed. Disable GameMode in Settings.")
            command.add(0, gameMode.toString())
        }
        // Refuse batch shims instead of evaluating arbitrary arguments in cmd.
        if (platform == HostPlatform.WINDOWS && scala.extension.lowercase() in listOf("cmd", "bat")) {
            error("Select the native scala-cli.exe using BLOCKBOX_SCALA_CLI. Batch shims cannot safely forward all JVM and game arguments.")
        }
        return LaunchPlan(command, store.instanceDir(config).createDirectories(), env)
    }
}

fun applyGraphicsEnvironment(env: MutableMap<String, String>, backend: String, platform: HostPlatform) {
    // Linux graphics controls must never leak onto macOS or Windows. Auto keeps
    // the user's graphics environment and never forces a vendor on Intel/AMD.
    if (!platform.unixDesktop) return
    env["BLOCKBOX_DISPLAY_BACKEND"] = backend
    when (backend) {
        "x11" -> { env["GLFW_PLATFORM"] = "x11" }
        "x11-nvidia" -> { env["GLFW_PLATFORM"] = "x11"; env["__GLX_VENDOR_LIBRARY_NAME"] = "nvidia" }
        "wayland" -> { env["GLFW_PLATFORM"] = "wayland"; env.putIfAbsent("GLFW_WAYLAND_LIBDECOR", "0") }
        "software" -> {
            env["LIBGL_ALWAYS_SOFTWARE"] = "1"; env["GALLIUM_DRIVER"] = "llvmpipe"
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "llvmpipe"
            env.remove("__GLX_VENDOR_LIBRARY_NAME"); env.remove("__NV_PRIME_RENDER_OFFLOAD")
        }
    }
}

class GameRunner(private val store: LauncherStore, private val prepare: ((InstanceConfig) -> LaunchPlan)? = null) {
    private class Session(val config: InstanceConfig) {
        @Volatile var cancelled = false
        @Volatile var process: Process? = null
    }
    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile var state = RunState.IDLE
        private set
    val activeInstanceId get() = session?.config?.id
    val activeInstanceName get() = session?.config?.name
    val process get() = session?.process

    fun launch(config: InstanceConfig, onLog: (String) -> Unit, onState: (RunState) -> Unit, onStarted: () -> Unit = {}, onExit: (Int) -> Unit = {}): Boolean {
        val current = synchronized(lock) {
            if (session != null) return false
            Session(config.copy()).also { session = it; state = RunState.STARTING }
        }
        onState(RunState.STARTING)
        thread(name = "blockbox-game", isDaemon = false) {
            var exitCode = 1
            try {
                val logDir = store.logsDir(config).createDirectories()
                val file = logDir.resolve("launch-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))}.log")
                file.bufferedWriter().use { writer ->
                    fun log(line: String) { val clean = stripAnsi(line); writer.appendLine(clean); writer.flush(); onLog(clean) }
                    try {
                        log("Blockbox Launcher $LAUNCHER_VERSION · ${System.getProperty("os.name")} / ${System.getProperty("os.arch")}")
                        log("Preparing ${config.name} · ${config.maxMemoryMb} MiB · graphics ${config.displayBackend}")
                        log("Game: ${store.projectRoot}"); log("Instance: ${store.instanceDir(config)}"); log("Log: $file")
                        val plan = prepare?.invoke(config) ?: LaunchPlanner(store).prepare(config, ::log, { current.cancelled }) { process ->
                            synchronized(lock) { current.process = process; if (current.cancelled) process?.destroy() }
                        }
                        val started = synchronized(lock) {
                            if (current.cancelled) null else ProcessBuilder(plan.command).apply {
                                directory(plan.directory.toFile()); redirectErrorStream(true)
                                environment().clear(); environment().putAll(plan.environment)
                            }.start().also { current.process = it; state = RunState.RUNNING }
                        }
                        if (started == null) { log("Launch cancelled."); exitCode = 0 }
                        else {
                            onState(if (current.cancelled) RunState.STOPPING else RunState.RUNNING)
                            runCatching { store.saveInstance(config.copy(lastPlayed = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))) }
                                .onFailure { log("Could not save last played time: ${it.message}") }
                            if (!current.cancelled) onStarted()
                            var hintShown = false
                            started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { line ->
                                log(line)
                                if (!hintShown && (line.contains("OpenGL context setup failed", true) || line.contains("GLFW_PLATFORM_ERROR", true) || line.contains("no OpenGL context", true))) {
                                    hintShown = true
                                    log("Graphics initialization failed. In Settings, try another backend or Software (Mesa). See Diagnostics for system details.")
                                }
                            } }
                            exitCode = started.waitFor()
                            log(if (current.cancelled) "Game stopped." else "Blockbox exited with code $exitCode.")
                        }
                    } catch (e: Exception) { log("Launch failed: ${e.message ?: e.javaClass.simpleName}") }
                }
            } catch (e: Exception) { onLog("Launch failed: ${e.message ?: e.javaClass.simpleName}") }
            finally {
                // Always reap children if launch setup or log IO fails after start.
                if (current.process?.isAlive == true) terminate(current.process!!)
                synchronized(lock) { if (session === current) { session = null; state = RunState.IDLE } }
                onState(RunState.IDLE)
                onExit(exitCode)
            }
        }
        return true
    }

    fun stop(onState: (RunState) -> Unit = {}) {
        val current = synchronized(lock) {
            val active = session ?: return
            active.cancelled = true; state = RunState.STOPPING; active
        }
        onState(RunState.STOPPING)
        current.process?.let { running -> thread(name = "blockbox-stop", isDaemon = true) { terminate(running) } }
    }

    private fun terminate(process: Process) {
        val handles = process.descendants().use { it.toList().reversed() } + process.toHandle()
        handles.forEach { runCatching { if (it.isAlive) it.destroy() } }
        process.waitFor(1500, TimeUnit.MILLISECONDS)
        // The parent may exit before its children; still give all children a
        // bounded grace period and then kill only captured process handles.
        if (handles.any { it.isAlive }) Thread.sleep(300)
        handles.forEach { runCatching { if (it.isAlive) it.destroyForcibly() } }
    }
}

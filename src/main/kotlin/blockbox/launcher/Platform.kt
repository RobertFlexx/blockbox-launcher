package blockbox.launcher

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable

enum class HostPlatform {
    WINDOWS, MACOS, LINUX, BSD, OTHER;
    val unixDesktop get() = this == LINUX || this == BSD
    val pathSeparator get() = if (this == WINDOWS) ";" else ":"
    companion object {
        fun detect(name: String = System.getProperty("os.name")): HostPlatform = when {
            name.startsWith("Windows", true) -> WINDOWS
            name.contains("Mac", true) || name.equals("Darwin", true) -> MACOS
            name.contains("Linux", true) -> LINUX
            name.contains("BSD", true) || name.contains("DragonFly", true) -> BSD
            else -> OTHER
        }
    }
}

fun findExecutable(command: String, env: Map<String, String> = System.getenv(), platform: HostPlatform = HostPlatform.detect()): Path? {
    val value = command.trim().removeSurrounding("\"")
    if (value.contains('/') || value.contains('\\')) return Path.of(value).toAbsolutePath().normalize().takeIf { Files.isRegularFile(it) && (platform == HostPlatform.WINDOWS || it.isExecutable()) }
    val extensions = if (platform == HostPlatform.WINDOWS) listOf("") + (env.entries.firstOrNull { it.key.equals("PATHEXT", true) }?.value ?: ".EXE;.CMD;.BAT").split(';') else listOf("")
    val path = env.entries.firstOrNull { it.key.equals("PATH", platform == HostPlatform.WINDOWS) }?.value.orEmpty()
    return path.split(platform.pathSeparator).filter { it.isNotBlank() }.asSequence().flatMap { dir ->
        extensions.asSequence().map { Path.of(dir.removeSurrounding("\""), value + it) }
    }.firstOrNull { Files.isRegularFile(it) && (platform == HostPlatform.WINDOWS || it.isExecutable()) }?.toAbsolutePath()
}

fun defaultDataRoot(): Path {
    (System.getProperty("blockbox.launcher.home") ?: System.getenv("BLOCKBOX_LAUNCHER_HOME"))?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
    val home = Path.of(System.getProperty("user.home"))
    val codeRoot = runCatching { Path.of(LauncherStore::class.java.protectionDomain.codeSource.location.toURI()).parent?.parent?.parent }.getOrNull()
    listOfNotNull(Path.of(System.getProperty("user.dir")), codeRoot, home.resolve("blockbox-launcher"), home.resolve("blockbox/launcher"))
        .firstOrNull { it.resolve("instances").isDirectory() }?.let { return it.toAbsolutePath().normalize() }
    return when (HostPlatform.detect()) {
        HostPlatform.WINDOWS -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString()).resolve("BlockboxLauncher")
        HostPlatform.MACOS -> home.resolve("Library/Application Support/BlockboxLauncher")
        else -> Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString()).resolve("blockbox-launcher")
    }
}

fun resolveGameRoot(launcherRoot: Path): Path {
    System.getenv("BLOCKBOX_GAME_ROOT")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
    includedGameRoot()?.let { return it }
    val home = Path.of(System.getProperty("user.home"))
    return listOfNotNull(launcherRoot.parent, launcherRoot.parent?.resolve("blockbox"), Path.of(System.getProperty("user.dir")), home.resolve("blockbox"))
        .firstOrNull { it.resolve("src/main/scala/blockbox/Main.scala").exists() } ?: home.resolve("blockbox")
}

fun gameJavaHome(config: InstanceConfig, env: Map<String, String>, platform: HostPlatform, requireCompiler: Boolean = true): Path? {
    val executableName = if (platform == HostPlatform.WINDOWS) "java.exe" else "java"
    val compilerName = if (platform == HostPlatform.WINDOWS) "javac.exe" else "javac"
    if (config.javaPath.trim() != "java") {
        val executable = findExecutable(config.javaPath, env, platform) ?: error("Java executable not found: ${config.javaPath}")
        return executable.toRealPath().parent?.parent?.also { require(!requireCompiler || it.resolve("bin/$compilerName").exists()) { "Choose a JDK with javac, rather than a JRE." } }
    }
    env["BLOCKBOX_JAVA_HOME"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it).also { path -> require(path.resolve("bin/$executableName").exists() && (!requireCompiler || path.resolve("bin/$compilerName").exists())) { "BLOCKBOX_JAVA_HOME must point to a ${if (requireCompiler) "JDK" else "Java runtime"}." } } }
    val candidates = mutableListOf<Path>()
    if (!requireCompiler) candidates.add(Path.of(System.getProperty("java.home")))
    env["JAVA_HOME"]?.let { candidates.add(Path.of(it)) }
    findExecutable(executableName, env, platform)?.let { candidates.add(it.toRealPath().parent.parent) }
    candidates.add(Path.of(System.getProperty("java.home")))
    for (root in listOf("/usr/lib/jvm", "/usr/lib64/jvm", "/opt", "/usr/local", "/Library/Java/JavaVirtualMachines")) {
        val path = Path.of(root)
        if (path.isDirectory()) Files.list(path).use { stream ->
            stream.filter { it.fileName.toString().contains("21") }.sorted().forEach {
                candidates.add(if (platform == HostPlatform.MACOS) it.resolve("Contents/Home") else it)
            }
        }
    }
    return candidates.firstOrNull { it.resolve("bin/$executableName").exists() && (!requireCompiler || it.resolve("bin/$compilerName").exists()) }
}

fun nativeClassifier(platform: HostPlatform, arch: String): String? = when (platform) {
    HostPlatform.WINDOWS -> when (arch.lowercase()) { "amd64", "x86_64" -> "natives-windows"; "aarch64", "arm64" -> "natives-windows-arm64"; "x86", "i386", "i686" -> "natives-windows-x86"; else -> null }
    HostPlatform.MACOS -> when (arch.lowercase()) { "amd64", "x86_64" -> "natives-macos"; "aarch64", "arm64" -> "natives-macos-arm64"; else -> null }
    HostPlatform.LINUX -> when (arch.lowercase()) { "amd64", "x86_64" -> "natives-linux"; "aarch64", "arm64" -> "natives-linux-arm64"; "arm", "armv7l" -> "natives-linux-arm32"; "ppc64le" -> "natives-linux-ppc64le"; "riscv64" -> "natives-linux-riscv64"; else -> null }
    else -> null
}

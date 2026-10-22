package blockbox.launcher

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import kotlin.io.path.*

/** Completed builds are shared by instances; their worlds and launch options aren't. */
class CompiledGameCache(private val cache: Path) {
    fun getOrBuild(
        sourceRoot: Path,
        javaHome: Path,
        platform: HostPlatform,
        arch: String,
        buildSettings: Map<String, String>,
        log: (String) -> Unit,
        cancelled: () -> Boolean,
        build: (output: Path, workspace: Path) -> Unit
    ): Path {
        fun fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun text(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            fun file(path: Path) { path.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var n = input.read(buffer)
                while (n >= 0) { check(!cancelled()) { "Launch cancelled." }; digest.update(buffer, 0, n); n = input.read(buffer) }
            } }
            text("blockbox-assembly-v1")
            text(sourceRoot.toRealPath().toString())
            text(javaHome.toRealPath().toString())
            javaHome.resolve("release").takeIf { it.isRegularFile() }?.let(::file)
            text(platform.name); text(arch)
            buildSettings.toSortedMap().forEach { (key, value) -> text(key); text(value) }
            gameBuildInputs(sourceRoot).forEach { path -> text(sourceRoot.relativize(path).toString()); file(path) }
            return HexFormat.of().formatHex(digest.digest())
        }

        val key = fingerprint()
        val dir = cache.resolve("game/$key").createDirectories()
        val target = dir.resolve("blockbox-game.jar")
        if (validGameJar(target)) { log("Using cached game build ${key.take(12)} (no compilation)."); return target }
        FileChannel.open(dir.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            var waiting = false
            var lock: java.nio.channels.FileLock? = null
            while (lock == null) {
                check(!cancelled()) { "Launch cancelled." }
                lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) {
                    if (!waiting) { log("Waiting for the other game build to finish…"); waiting = true }
                    Thread.sleep(100)
                }
            }
            lock.use {
                if (validGameJar(target)) { log("Using cached game build ${key.take(12)} (no compilation)."); return target }
                log("Building Blockbox once; unchanged launches will use the saved build.")
                val stage = Files.createTempDirectory(dir, ".build-")
                try {
                    val output = stage.resolve("blockbox-game.jar")
                    build(output, stage.resolve("workspace"))
                    check(!cancelled()) { "Launch cancelled." }
                    require(validGameJar(output)) { "The game build did not produce a runnable JAR." }
                    check(fingerprint() == key) { "Game sources changed during compilation. Launch again to build the updated game." }
                    try { Files.move(output, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: AtomicMoveNotSupportedException) { Files.move(output, target, StandardCopyOption.REPLACE_EXISTING) }
                    log("Game build saved. Future launches start it directly with Java.")
                } finally { deleteBuildDirectory(stage) }
            }
        }
        return target
    }
}

internal fun gameBuildInputs(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    for (folder in listOf("src/main/scala", "src/main/java", "src/main/resources")) {
        val dir = root.resolve(folder)
        if (dir.isDirectory()) Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir.fileName.toString() in setOf(".scala-build", ".bsp", ".git")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.isRegularFile()) files.add(file)
                return FileVisitResult.CONTINUE
            }
        })
    }
    root.resolve("project.scala").takeIf { it.isRegularFile() }?.let { files.add(it) }
    return files.sortedBy { root.relativize(it).toString() }
}

internal fun validGameJar(path: Path): Boolean = path.isRegularFile() && runCatching {
    JarFile(path.toFile()).use { jar ->
        val main = jar.manifest?.mainAttributes?.getValue("Main-Class")
        !main.isNullOrBlank() && jar.getJarEntry(main.replace('.', '/') + ".class") != null
    }
}.getOrDefault(false)

internal fun deleteBuildDirectory(path: Path) {
    if (path.exists()) Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
}

internal fun lwjglNeedsNativeJar(module: String, platform: HostPlatform): Boolean = when (module) {
    "lwjgl-egl", "lwjgl-fmod", "lwjgl-jawt", "lwjgl-odbc", "lwjgl-opencl", "lwjgl-renderdoc" -> false
    // Vulkan uses the installed loader on Linux/Windows. macOS ships MoltenVK.
    "lwjgl-vulkan" -> platform == HostPlatform.MACOS
    else -> true
}

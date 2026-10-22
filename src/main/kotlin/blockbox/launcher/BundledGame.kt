package blockbox.launcher

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import kotlin.io.path.*

const val BUNDLED_GAME_DESCRIPTOR = "blockbox-game.properties"

data class BundledGame(val root: Path, val jar: Path, val id: String, val revision: String, val platform: HostPlatform, val arch: String) {
    /** Assets/config live in writable instance data, even when installed under Program Files or in an AppImage. */
    fun prepareRuntime(store: LauncherStore, config: InstanceConfig, log: (String) -> Unit, cancelled: () -> Boolean): Path {
        val runtime = store.configDir(config).resolve("game").createDirectories()
        require(!Files.isSymbolicLink(runtime)) { "The instance game directory cannot be a symbolic link." }
        val marker = runtime.resolve(".bundle-id")
        if (marker.isRegularFile() && marker.readText() == id && runtime.resolve("assets").isDirectory()) return runtime
        log("Preparing the included game assets for ${config.name}…")
        for (folder in listOf("assets", "shaderpacks")) {
            val source = root.resolve(folder)
            if (!source.isDirectory()) continue
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(!cancelled()) { "Launch cancelled." }
                    val target = runtime.resolve(folder).resolve(source.relativize(dir).toString())
                    require(!Files.isSymbolicLink(target)) { "Game asset directories cannot be symbolic links." }
                    target.createDirectories()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(!cancelled()) { "Launch cancelled." }
                    require(!attrs.isSymbolicLink) { "Bundled assets cannot contain symbolic links: $file" }
                    val relative = source.relativize(file)
                    val target = runtime.resolve(folder).resolve(relative.toString())
                    // Keep the user's selected shader pack on upgrades.
                    if (folder == "shaderpacks" && relative.toString() == "active.txt" && target.exists()) return FileVisitResult.CONTINUE
                    val temp = Files.createTempFile(target.parent, ".asset-", ".tmp")
                    try {
                        Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING)
                        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
                    } finally { Files.deleteIfExists(temp) }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        marker.writeText(id)
        return runtime
    }

    companion object {
        fun read(root: Path): BundledGame? {
            val descriptor = root.resolve(BUNDLED_GAME_DESCRIPTOR)
            if (!descriptor.isRegularFile()) return null
            val p = Properties().apply { descriptor.bufferedReader().use { load(it) } }
            require(p.getProperty("format") == "Blockbox Bundled Game" && p.getProperty("formatVersion") == "1") { "Unsupported bundled game format." }
            val jar = root.resolve("blockbox-game.jar")
            require(validGameJar(jar)) { "The included game is missing or damaged. Reinstall Blockbox Launcher." }
            val platform = HostPlatform.valueOf(p.getProperty("platform"))
            val id = p.getProperty("id")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: error("The included game has no valid build identifier.")
            return BundledGame(root, jar, id, p.getProperty("revision", "bundled"), platform, p.getProperty("arch"))
        }
    }
}

fun includedGameRoot(): Path? {
    System.getProperty("blockbox.bundled.game")?.takeIf { it.isNotBlank() }?.let { path ->
        return Path.of(path).toAbsolutePath().normalize().takeIf { it.resolve(BUNDLED_GAME_DESCRIPTOR).isRegularFile() }
    }
    val location = runCatching { Path.of(LauncherStore::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull() ?: return null
    val parent = if (location.isRegularFile()) location.parent else location
    return parent.resolve("game").takeIf { it.resolve(BUNDLED_GAME_DESCRIPTOR).isRegularFile() }
}

fun cpuArchitecture(arch: String): String = when (arch.lowercase()) {
    "amd64", "x86_64", "x64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    "x86", "i386", "i686" -> "x86"
    else -> arch.lowercase()
}

/** Release smoke check: no desktop, compiler, checkout, or existing player data. */
fun checkIncludedGame(): Int {
    val root = includedGameRoot() ?: error("This build does not include Blockbox.")
    val temp = Files.createTempDirectory("blockbox-bundle-check-")
    try {
        val store = LauncherStore(temp, root)
        val config = store.createInstance("Installation check", "Temporary profile").copy(gameArgs = "--check-mods", maxMemoryMb = 2048)
        val plan = LaunchPlanner(store).prepare(config, ::println)
        val process = ProcessBuilder(plan.command).apply {
            directory(plan.directory.toFile()); inheritIO(); environment().clear(); environment().putAll(plan.environment)
        }.start()
        val exit = process.waitFor()
        if (exit == 0) println("Included Blockbox check passed. No build tools needed.")
        return exit
    } finally { deleteBuildDirectory(temp) }
}

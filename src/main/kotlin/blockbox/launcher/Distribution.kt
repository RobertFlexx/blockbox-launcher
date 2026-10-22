package blockbox.launcher

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import kotlin.io.path.*

/** Build-machine entry point; never used during an installed game's startup. */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { pair -> require(pair.size == 2) { "Expected --option value pairs." }; pair[0] to pair[1] }
    val source = Path.of(options.getValue("--game-root")).toAbsolutePath().normalize()
    val output = Path.of(options.getValue("--output")).toAbsolutePath().normalize()
    val javaHome = Path.of(options.getValue("--java-home")).toAbsolutePath().normalize()
    val cache = Path.of(options.getValue("--cache")).toAbsolutePath().normalize()
    val revision = options["--revision"] ?: "local"
    require(source.resolve("src/main/scala/blockbox/Main.scala").isRegularFile()) { "Set -PgameRoot to a Blockbox checkout to build the full distribution." }
    val platform = HostPlatform.detect()
    val arch = System.getProperty("os.arch")
    val env = System.getenv() + ("BLOCKBOX_JAVA_HOME" to javaHome.toString())
    val jar = GameBuildCache(cache).gameJar(source, javaHome, platform, arch, env, ::println, { false }, {})
    val stage = Files.createTempDirectory(output.parent.createDirectories(), ".bundle-")
    try {
        Files.copy(jar, stage.resolve("blockbox-game.jar"))
        val digest = MessageDigest.getInstance("SHA-256")
        fun hashFile(path: Path) { path.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var count = input.read(buffer)
            while (count >= 0) { digest.update(buffer, 0, count); count = input.read(buffer) }
        } }
        hashFile(jar)
        for (folder in listOf("assets", "shaderpacks")) {
            val root = source.resolve(folder)
            if (!root.isDirectory()) continue
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    stage.resolve(folder).resolve(root.relativize(dir).toString()).createDirectories()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink) { "Release assets cannot be symbolic links: $file" }
                    Files.copy(file, stage.resolve(folder).resolve(root.relativize(file).toString()))
                    return FileVisitResult.CONTINUE
                }
            })
        }
        require(stage.resolve("assets").isDirectory()) { "The game checkout has no assets directory." }
        for (file in Files.walk(stage).use { stream -> stream.filter { it.isRegularFile() && it.name != "blockbox-game.jar" }.sorted().toList() }) {
            digest.update(stage.relativize(file).toString().replace('\\', '/').toByteArray()); digest.update(0.toByte()); hashFile(file)
        }
        source.resolve("LICENSE").takeIf { it.isRegularFile() }?.let { Files.copy(it, stage.resolve("LICENSE")) }
        val props = Properties().apply {
            setProperty("format", "Blockbox Bundled Game"); setProperty("formatVersion", "1")
            setProperty("id", HexFormat.of().formatHex(digest.digest())); setProperty("revision", revision)
            setProperty("platform", platform.name); setProperty("arch", cpuArchitecture(arch))
        }
        writeProperties(stage.resolve(BUNDLED_GAME_DESCRIPTOR), props)
        deleteBuildDirectory(output)
        Files.move(stage, output)
        println("Included game ready: $output ($platform / ${cpuArchitecture(arch)})")
    } finally { deleteBuildDirectory(stage) }
}

package blockbox.launcher

import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LauncherStore(
    val launcherRoot: Path = Path.of(System.getProperty("user.dir")).toAbsolutePath(),
    val projectRoot: Path = resolveGameRoot(launcherRoot)
) {
    val instancesRoot: Path = launcherRoot.resolve("instances")
    val exportsRoot: Path = launcherRoot.resolve("exports")

    init {
        instancesRoot.createDirectories()
        exportsRoot.createDirectories()
    }

    fun loadInstances(): List<InstanceConfig> {
        val instances = Files.list(instancesRoot).use { stream ->
            stream.toList().filter { Files.isDirectory(it) }.mapNotNull { loadInstance(it) }
        }.sortedBy { it.name.lowercase() }
        return instances.ifEmpty { listOf(createInstance("Default", "Main Blockbox profile")) }
    }

    fun createInstance(name: String, description: String): InstanceConfig {
        val config = InstanceConfig(name = cleanName(name), description = description.ifBlank { "Blockbox instance" })
        val dir = instanceDir(config)
        dir.createDirectories()
        dir.resolve("mods").createDirectories()
        dir.resolve("worlds").createDirectories()
        dir.resolve("config").createDirectories()
        dir.resolve("logs").createDirectories()
        saveInstance(config)
        dir.resolve("README.txt").writeText("This folder is managed by Blockbox Launcher. Mods, worlds, config, and logs for '${config.name}' live here.\n")
        return config
    }

    fun duplicateInstance(source: InstanceConfig): InstanceConfig {
        val copy = createInstance("${source.name} Copy", source.description)
        copy.javaPath = source.javaPath
        copy.minMemoryMb = source.minMemoryMb
        copy.maxMemoryMb = source.maxMemoryMb
        copy.jvmArgs = source.jvmArgs
        copy.gameArgs = source.gameArgs
        copy.envVars = source.envVars
        copy.useGameMode = source.useGameMode
        copy.forceX11 = source.forceX11
        copy.displayBackend = source.displayBackend
        copy.closeLauncherOnGameStart = source.closeLauncherOnGameStart
        copyPath(instanceDir(source).resolve("mods"), instanceDir(copy).resolve("mods"))
        copyPath(instanceDir(source).resolve("config"), instanceDir(copy).resolve("config"))
        saveInstance(copy)
        return copy
    }

    fun deleteInstance(config: InstanceConfig) {
        val dir = instanceDir(config).toAbsolutePath().normalize()
        val root = instancesRoot.toAbsolutePath().normalize()
        require(dir.startsWith(root) && dir != root) { "Blocked unsafe instance delete target." }
        if (dir.exists()) deletePath(dir)
    }

    fun saveInstance(config: InstanceConfig) {
        val props = Properties()
        props["id"] = config.id
        props["name"] = config.name
        props["description"] = config.description
        props["javaPath"] = config.javaPath
        props["minMemoryMb"] = config.minMemoryMb.toString()
        props["maxMemoryMb"] = config.maxMemoryMb.toString()
        props["jvmArgs"] = config.jvmArgs
        props["gameArgs"] = config.gameArgs
        props["envVars"] = config.envVars
        props["useGameMode"] = config.useGameMode.toString()
        props["displayBackend"] = config.displayBackend
        props["closeLauncherOnGameStart"] = config.closeLauncherOnGameStart.toString()
        props["lastPlayed"] = config.lastPlayed
        val dir = instanceDir(config)
        dir.createDirectories()
        dir.resolve("instance.properties").outputStream().use { props.store(it, "Blockbox Launcher instance") }
    }

    fun instanceDir(config: InstanceConfig): Path = instancesRoot.resolve(config.id)
    fun modsDir(config: InstanceConfig): Path = instanceDir(config).resolve("mods")
    fun worldsDir(config: InstanceConfig): Path = instanceDir(config).resolve("worlds")
    fun configDir(config: InstanceConfig): Path = instanceDir(config).resolve("config")
    fun logsDir(config: InstanceConfig): Path = instanceDir(config).resolve("logs")

    fun scanMods(config: InstanceConfig): List<BlockboxMod> {
        val dir = modsDir(config)
        dir.createDirectories()
        return Files.list(dir).use { stream ->
            stream.toList().filter { Files.isDirectory(it) || it.extension.lowercase() in setOf("jar", "groovy", "disabled") }
                .map { modInfo(it) }
                .sortedBy { it.name.lowercase() }
        }
    }

    fun importMod(config: InstanceConfig, sourceText: String): String {
        val raw = sourceText.trim()
        require(raw.isNotBlank()) { "Choose a mod file or folder first." }
        val source = Path.of(raw).toAbsolutePath().normalize()
        val modsRoot = modsDir(config).toAbsolutePath().normalize()
        require(source.exists()) { "File does not exist: $source" }
        require(source != modsRoot) { "Choose a mod inside the mods folder, not the mods folder itself." }
        require(!modsRoot.startsWith(source)) { "Refusing to import a parent folder into its own mods directory." }
        val allowed = source.isDirectory() || source.extension.lowercase() in setOf("jar", "groovy", "disabled")
        require(allowed) { "Mods must be a .jar, .groovy file, or a mod folder." }
        val target = modsRoot.resolve(source.name).normalize()
        require(target != source) { "That mod is already installed in this instance." }
        require(target.startsWith(modsRoot)) { "Blocked unsafe mod import target." }
        if (target.exists()) {
            throw IllegalArgumentException("A mod named ${source.name} already exists in this instance.")
        }
        copyPath(source, target)
        return "Imported ${source.name} to ${target.absolutePathString()}"
    }

    fun toggleMod(mod: BlockboxMod): String {
        val path = mod.path
        val target = if (mod.enabled) path.resolveSibling(path.name + ".disabled") else path.resolveSibling(path.name.removeSuffix(".disabled"))
        Files.move(path, target, StandardCopyOption.REPLACE_EXISTING)
        return if (mod.enabled) "Disabled ${mod.name}" else "Enabled ${mod.name}"
    }

    fun deleteMod(config: InstanceConfig, mod: BlockboxMod): String {
        val modsRoot = modsDir(config).toAbsolutePath().normalize()
        val target = mod.path.toAbsolutePath().normalize()
        require(target.startsWith(modsRoot) && target != modsRoot) { "Blocked unsafe mod delete target." }
        deletePath(target)
        return "Deleted ${mod.name}"
    }

    fun exportPack(config: InstanceConfig, includeWorlds: Boolean): Path {
        val safe = config.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "blockbox-pack" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val output = exportsRoot.resolve("$safe-$stamp.$PACK_EXTENSION")
        ZipOutputStream(output.outputStream()).use { zip ->
            fun putText(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            putText("blockbox-pack.properties", "format=Blockbox Launcher Pack\nformatVersion=1\nname=${config.name}\ndescription=${config.description}\nincludeWorlds=$includeWorlds\ncreated=$stamp\n")
            val include = if (includeWorlds) listOf("mods", "config", "worlds") else listOf("mods", "config")
            for (folder in include) {
                val start = instanceDir(config).resolve(folder)
                if (start.exists()) zipDir(start, folder, zip)
            }
        }
        return output
    }

    fun importPack(sourceText: String): InstanceConfig {
        val raw = sourceText.trim()
        require(raw.isNotBlank()) { "Choose a .${PACK_EXTENSION} file first." }
        val source = Path.of(raw).toAbsolutePath().normalize()
        require(source.exists()) { "Pack does not exist: $source" }
        require(source.extension.equals(PACK_EXTENSION, ignoreCase = true)) { "Blockbox packs must end in .${PACK_EXTENSION}." }
        val config = createInstance(source.name.removeSuffix(".$PACK_EXTENSION"), "Imported Blockbox pack")
        ZipInputStream(source.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory && (name.startsWith("mods/") || name.startsWith("config/") || name.startsWith("worlds/"))) {
                    val target = instanceDir(config).resolve(name).normalize()
                    require(target.startsWith(instanceDir(config))) { "Blocked unsafe pack entry: $name" }
                    target.parent.createDirectories()
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return config
    }

    private fun loadInstance(dir: Path): InstanceConfig? {
        val file = dir.resolve("instance.properties")
        if (!file.exists()) return null
        val props = Properties()
        file.inputStream().use { props.load(it) }
        return InstanceConfig(
            id = props.getProperty("id", dir.name),
            name = props.getProperty("name", dir.name),
            description = props.getProperty("description", "Blockbox instance"),
            javaPath = props.getProperty("javaPath", "java"),
            minMemoryMb = props.getProperty("minMemoryMb", "1024").toIntOrNull() ?: 1024,
            maxMemoryMb = props.getProperty("maxMemoryMb", "4096").toIntOrNull() ?: 4096,
            jvmArgs = props.getProperty("jvmArgs", ""),
            gameArgs = props.getProperty("gameArgs", ""),
            envVars = props.getProperty("envVars", ""),
            useGameMode = props.getProperty("useGameMode", "false").toBoolean(),
            forceX11 = props.getProperty("forceX11", "false").toBoolean(),
            displayBackend = props.getProperty("displayBackend") ?: if (props.getProperty("forceX11", "false").toBoolean()) "x11" else "auto",
            closeLauncherOnGameStart = props.getProperty("closeLauncherOnGameStart", "false").toBoolean(),
            lastPlayed = props.getProperty("lastPlayed", "Never")
        )
    }

    private fun modInfo(path: Path): BlockboxMod {
        val enabled = !path.name.endsWith(".disabled")
        val displayPath = path.name.removeSuffix(".disabled")
        val metadata = readMetadata(path)
        return BlockboxMod(
            path = path,
            enabled = enabled,
            name = metadata["name"] ?: displayPath.removeSuffix(".jar").removeSuffix(".groovy"),
            version = metadata["version"] ?: "unknown",
            side = metadata["side"] ?: "unknown",
            description = metadata["description"] ?: "No description"
        )
    }

    private fun readMetadata(path: Path): Map<String, String> {
        val json = when {
            path.isDirectory() && path.resolve("blockbox.mod.json").exists() -> path.resolve("blockbox.mod.json").readText()
            else -> ""
        }
        if (json.isBlank()) return emptyMap()
        return Regex("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun cleanName(value: String): String = value.trim().ifBlank { "Blockbox Instance" }.take(48)
}

private fun resolveGameRoot(launcherRoot: Path): Path {
    val envRoot = System.getenv("BLOCKBOX_GAME_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath() }
    if (envRoot != null) return envRoot
    val parent = launcherRoot.parent ?: launcherRoot
    val sibling = parent.resolve("blockbox")
    return when {
        parent.resolve("src/main/scala/blockbox/Main.scala").exists() -> parent
        sibling.resolve("src/main/scala/blockbox/Main.scala").exists() -> sibling
        else -> launcherRoot
    }
}

private fun copyPath(source: Path, target: Path) {
    if (source.isDirectory()) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                target.resolve(source.relativize(dir).toString()).createDirectories()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    } else {
        target.parent.createDirectories()
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun deletePath(path: Path) {
    if (!path.exists()) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

private fun zipDir(start: Path, prefix: String, zip: ZipOutputStream) {
    Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val entryName = prefix + "/" + start.relativize(file).toString().replace('\\', '/')
            zip.putNextEntry(ZipEntry(entryName))
            Files.copy(file, zip)
            zip.closeEntry()
            return FileVisitResult.CONTINUE
        }
    })
}

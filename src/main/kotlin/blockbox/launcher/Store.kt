package blockbox.launcher

import java.io.StringReader
import java.io.StringWriter
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

class LauncherStore(val launcherRoot: Path = defaultDataRoot(), projectRoot: Path = resolveGameRoot(launcherRoot)) {
    val instancesRoot = launcherRoot.resolve("instances").toAbsolutePath().normalize()
    val exportsRoot = launcherRoot.resolve("exports").toAbsolutePath().normalize()
    val warnings = mutableListOf<String>()
    val preferences = Properties()
    @Volatile var projectRoot: Path = projectRoot.toAbsolutePath().normalize()
        private set

    init {
        instancesRoot.createDirectories()
        exportsRoot.createDirectories()
        require(!Files.isSymbolicLink(instancesRoot)) { "The instances directory cannot be a symbolic link." }
        val file = launcherRoot.resolve("launcher.properties")
        if (file.exists()) runCatching { file.bufferedReader().use { preferences.load(it) } }
            .onFailure { warnings += "Could not read launcher preferences: ${it.message}" }
        // An explicit environment override always wins over a saved location.
        if (System.getenv("BLOCKBOX_GAME_ROOT").isNullOrBlank()) {
            if (preferences.getProperty("gameSource") == "included") includedGameRoot()?.let { this.projectRoot = it }
            else preferences.getProperty("gameRoot")?.let { this.projectRoot = Path.of(it).toAbsolutePath().normalize() }
        }
    }

    @Synchronized fun setGameRoot(path: Path) {
        val root = path.toAbsolutePath().normalize()
        require(root.resolve("src/main/scala/blockbox/Main.scala").isRegularFile() || BundledGame.read(root) != null) { "Choose a Blockbox game folder or source checkout." }
        if (root == includedGameRoot()?.toAbsolutePath()?.normalize()) {
            // AppImage mount paths change each run; remember the choice, not its temporary path.
            preferences.setProperty("gameSource", "included")
            preferences.remove("gameRoot")
        } else {
            preferences.remove("gameSource")
            preferences.setProperty("gameRoot", root.toString())
        }
        savePreferences()
        projectRoot = root
    }

    @Synchronized fun savePreferences() = writeProperties(launcherRoot.resolve("launcher.properties"), preferences)

    @Synchronized fun loadInstances(): List<InstanceConfig> {
        val instances = Files.list(instancesRoot).use { files ->
            files.filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !it.name.startsWith('.') }.map { dir ->
                try { loadInstance(dir) } catch (e: Exception) { warnings += "Skipped ${dir.name}: ${e.message}"; null }
            }.toList().filterNotNull()
        }.sortedBy { it.name.lowercase() }
        return instances.ifEmpty { listOf(createInstance("Default", "Your first Blockbox instance")) }
    }

    @Synchronized fun createInstance(name: String, description: String): InstanceConfig {
        val config = InstanceConfig(name = name.trim(), description = description)
        config.validate()
        val dir = instanceDir(config)
        try {
            listOf("mods", "worlds", "config", "logs").forEach { dir.resolve(it).createDirectories() }
            saveInstance(config)
            return config
        } catch (e: Exception) { deletePath(dir); throw e }
    }

    @Synchronized fun duplicateInstance(source: InstanceConfig): InstanceConfig {
        val copy = source.copy(id = UUID.randomUUID().toString(), name = (source.name.take(70) + " Copy"), lastPlayed = "Never")
        val dir = instanceDir(copy)
        try {
            listOf("mods", "worlds", "config").forEach { copyPath(instanceDir(source).resolve(it), dir.resolve(it)) }
            dir.resolve("logs").createDirectories()
            saveInstance(copy)
            return copy
        } catch (e: Exception) { deletePath(dir); throw e }
    }

    @Synchronized fun deleteInstance(config: InstanceConfig) = deletePath(instanceDir(config))

    @Synchronized fun saveInstance(config: InstanceConfig) {
        config.validate()
        val props = Properties().apply {
            setProperty("id", config.id); setProperty("name", config.name); setProperty("description", config.description)
            setProperty("javaPath", config.javaPath); setProperty("minMemoryMb", config.minMemoryMb.toString()); setProperty("maxMemoryMb", config.maxMemoryMb.toString())
            setProperty("jvmArgs", config.jvmArgs); setProperty("gameArgs", config.gameArgs); setProperty("envVars", config.envVars)
            setProperty("useGameMode", config.useGameMode.toString()); setProperty("displayBackend", config.displayBackend)
            setProperty("closeLauncherOnGameStart", config.closeLauncherOnGameStart.toString()); setProperty("lastPlayed", config.lastPlayed)
        }
        writeProperties(instanceDir(config).resolve("instance.properties"), props)
    }

    fun instanceDir(config: InstanceConfig): Path {
        require(config.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) && config.id !in listOf(".", "..")) { "Invalid instance identifier." }
        val dir = instancesRoot.resolve(config.id).normalize()
        require(dir.parent == instancesRoot && !Files.isSymbolicLink(dir)) { "Unsafe instance path." }
        return dir
    }
    private fun folder(config: InstanceConfig, name: String): Path = instanceDir(config).resolve(name).also {
        require(!Files.isSymbolicLink(it)) { "Managed instance folders cannot be symbolic links: $name" }
    }
    fun modsDir(config: InstanceConfig) = folder(config, "mods")
    fun worldsDir(config: InstanceConfig) = folder(config, "worlds")
    fun configDir(config: InstanceConfig) = folder(config, "config")
    fun logsDir(config: InstanceConfig) = folder(config, "logs")

    @Synchronized fun scanMods(config: InstanceConfig): List<BlockboxMod> {
        val dir = modsDir(config).createDirectories()
        return Files.list(dir).use { files -> files.filter {
            !Files.isSymbolicLink(it) && (it.isDirectory() || it.extension.lowercase() in setOf("jar", "groovy", "disabled"))
        }.map { path ->
            val json = if (path.isDirectory() && path.resolve("blockbox.mod.json").isRegularFile()) path.resolve("blockbox.mod.json").readText() else ""
            val meta = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(json).associate { it.groupValues[1] to it.groupValues[2] }
            BlockboxMod(path, !path.name.endsWith(".disabled"), meta["name"] ?: path.name.removeSuffix(".disabled").removeSuffix(".jar").removeSuffix(".groovy"), meta["version"] ?: "—", meta["side"] ?: "—", meta["description"] ?: "")
        }.toList().sortedBy { it.name.lowercase() } }
    }

    @Synchronized fun importMod(config: InstanceConfig, sourceText: String): String {
        val source = Path.of(sourceText.trim()).toRealPath()
        val root = modsDir(config).createDirectories().toRealPath()
        require(source != root && !root.startsWith(source)) { "Choose a mod file or folder, rather than the mods directory or its parent." }
        require(source.isDirectory() || source.extension.lowercase() in setOf("jar", "groovy", "disabled")) { "Choose a .jar, .groovy file, or mod folder." }
        val target = root.resolve(source.name)
        require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "${source.name} is already installed." }
        try { copyPath(source, target) } catch (e: Exception) { deletePath(target); throw e }
        return "Imported ${source.name}"
    }

    @Synchronized fun toggleMod(config: InstanceConfig, mod: BlockboxMod): String {
        val path = checkedModPath(config, mod)
        val target = path.resolveSibling(if (mod.enabled) path.name + ".disabled" else path.name.removeSuffix(".disabled"))
        require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "A mod named ${target.name} already exists. Rename or remove it first." }
        Files.move(path, target)
        return "${if (mod.enabled) "Disabled" else "Enabled"} ${mod.name}"
    }

    @Synchronized fun deleteMod(config: InstanceConfig, mod: BlockboxMod): String {
        deletePath(checkedModPath(config, mod))
        return "Deleted ${mod.name}"
    }
    private fun checkedModPath(config: InstanceConfig, mod: BlockboxMod): Path = mod.path.toAbsolutePath().normalize().also {
        require(it.parent == modsDir(config).toAbsolutePath().normalize() && !Files.isSymbolicLink(it)) { "Unsafe mod path." }
    }

    @Synchronized fun exportPack(config: InstanceConfig, includeWorlds: Boolean): Path {
        val safe = config.name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "instance" }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val output = exportsRoot.resolve("$safe-$stamp.$PACK_EXTENSION")
        val temp = Files.createTempFile(exportsRoot, ".export-", ".tmp")
        try {
            ZipOutputStream(temp.outputStream()).use { zip ->
                val meta = Properties().apply {
                    setProperty("format", "Blockbox Launcher Pack"); setProperty("formatVersion", "1")
                    setProperty("name", config.name); setProperty("description", config.description)
                    setProperty("includeWorlds", includeWorlds.toString())
                }
                zip.putNextEntry(ZipEntry("blockbox-pack.properties"))
                zip.write(StringWriter().also { meta.store(it, "Blockbox pack") }.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                (listOf("mods", "config") + if (includeWorlds) listOf("worlds") else emptyList()).forEach { name ->
                    val root = folder(config, name)
                    if (root.exists()) Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            zip.putNextEntry(ZipEntry("$name/" + root.relativize(dir).toString().replace('\\', '/').let { if (it.isBlank()) "" else "$it/" })); zip.closeEntry()
                            return FileVisitResult.CONTINUE
                        }
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            require(!attrs.isSymbolicLink) { "Packs cannot contain symbolic links: $file" }
                            zip.putNextEntry(ZipEntry("$name/" + root.relativize(file).toString().replace('\\', '/')))
                            Files.copy(file, zip); zip.closeEntry(); return FileVisitResult.CONTINUE
                        }
                    })
                }
            }
            Files.move(temp, output)
            return output
        } finally { Files.deleteIfExists(temp) }
    }

    @Synchronized fun importPack(sourceText: String): InstanceConfig {
        val source = Path.of(sourceText.trim()).toAbsolutePath().normalize()
        require(source.isRegularFile() && source.extension.equals(PACK_EXTENSION, true)) { "Choose an existing .$PACK_EXTENSION file." }
        val id = UUID.randomUUID().toString()
        val staging = Files.createTempDirectory(instancesRoot, ".import-")
        var total = 0L
        var entries = 0
        var metadata: Properties? = null
        val seen = mutableSetOf<Path>()
        try {
            ZipInputStream(source.inputStream()).use { zip ->
                var entry = zip.nextEntry
                val buffer = ByteArray(32 * 1024)
                while (entry != null) {
                    require(++entries <= 100_000) { "Pack contains too many entries." }
                    val name = entry.name.replace('\\', '/')
                    val target = staging.resolve(name).normalize()
                    require(!name.startsWith('/') && ':' !in name && name.split('/').none { it == ".." } && target.startsWith(staging) && target != staging) { "Unsafe pack entry: $name" }
                    require(seen.add(target)) { "Duplicate pack entry: $name" }
                    val allowed = name == "blockbox-pack.properties" || name.substringBefore('/') in listOf("mods", "worlds", "config")
                    if (allowed) {
                        if (entry.isDirectory) target.createDirectories()
                        else {
                            target.parent.createDirectories()
                            target.outputStream().use { out ->
                                var n = zip.read(buffer)
                                while (n >= 0) {
                                    total += n
                                    require(total <= 8L * 1024 * 1024 * 1024) { "Unpacked pack exceeds 8 GiB." }
                                    if (name == "blockbox-pack.properties") require(outSize(target, n) <= 1024 * 1024) { "Pack metadata is too large." }
                                    out.write(buffer, 0, n); n = zip.read(buffer)
                                }
                            }
                            if (name == "blockbox-pack.properties") metadata = Properties().apply { target.bufferedReader().use { load(it) } }
                        }
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
            val meta = metadata ?: error("This archive has no Blockbox pack metadata.")
            require(meta.getProperty("format") == "Blockbox Launcher Pack" && meta.getProperty("formatVersion") == "1") { "Unsupported Blockbox pack format." }
            val config = InstanceConfig(id = id, name = meta.getProperty("name", source.nameWithoutExtension).trim().take(80).ifBlank { "Imported instance" }, description = meta.getProperty("description", "Imported Blockbox pack"))
            Files.deleteIfExists(staging.resolve("blockbox-pack.properties"))
            listOf("mods", "worlds", "config", "logs").forEach { staging.resolve(it).createDirectories() }
            Files.move(staging, instanceDir(config))
            try { saveInstance(config) } catch (e: Exception) { deletePath(instanceDir(config)); throw e }
            return config
        } finally { deletePath(staging) }
    }

    private fun loadInstance(dir: Path): InstanceConfig? {
        val file = dir.resolve("instance.properties")
        if (!file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return null
        val p = Properties().apply { file.bufferedReader().use { load(it) } }
        // The directory name is authoritative. Never trust a path in metadata.
        val min = (p.getProperty("minMemoryMb")?.toIntOrNull() ?: 512).coerceIn(128, 1_048_576)
        return InstanceConfig(
            id = dir.name, name = p.getProperty("name", dir.name).take(80).ifBlank { dir.name }, description = p.getProperty("description", "Blockbox instance"),
            javaPath = p.getProperty("javaPath", "java"), minMemoryMb = min, maxMemoryMb = (p.getProperty("maxMemoryMb")?.toIntOrNull() ?: 4096).coerceIn(min, 1_048_576),
            jvmArgs = p.getProperty("jvmArgs", ""), gameArgs = p.getProperty("gameArgs", ""), envVars = p.getProperty("envVars", ""),
            useGameMode = p.getProperty("useGameMode", "false").toBoolean(),
            displayBackend = (p.getProperty("displayBackend") ?: if (p.getProperty("forceX11").toBoolean()) "x11" else "auto").takeIf { it in DISPLAY_BACKENDS } ?: "auto",
            closeLauncherOnGameStart = p.getProperty("closeLauncherOnGameStart", "false").toBoolean(), lastPlayed = p.getProperty("lastPlayed", "Never")
        ).also { instanceDir(it) }
    }
}

private fun outSize(path: Path, count: Int) = Files.size(path) + count

fun writeProperties(file: Path, properties: Properties) {
    file.parent.createDirectories()
    val temp = Files.createTempFile(file.parent, ".settings-", ".tmp")
    try {
        temp.bufferedWriter().use { properties.store(it, "Blockbox Launcher") }
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}

private fun copyPath(source: Path, target: Path) {
    if (!source.exists(LinkOption.NOFOLLOW_LINKS)) { target.createDirectories(); return }
    Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            target.resolve(source.relativize(dir).toString()).createDirectories(); return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            require(!attrs.isSymbolicLink) { "Importing symbolic links is not supported: $file" }
            val out = if (source.isDirectory()) target.resolve(source.relativize(file).toString()) else target
            out.parent.createDirectories(); Files.copy(file, out); return FileVisitResult.CONTINUE
        }
    })
}

private fun deletePath(path: Path) {
    if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
    })
}

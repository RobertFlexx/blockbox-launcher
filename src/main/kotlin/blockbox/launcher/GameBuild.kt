package blockbox.launcher

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.*
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.*

class GameBuildCache(private val cache: Path) {
    fun gameJar(
        sourceRoot: Path, javaHome: Path, platform: HostPlatform, arch: String,
        env: Map<String, String>, log: (String) -> Unit, cancelled: () -> Boolean,
        onProcess: (Process?) -> Unit, resolveNative: ((String, String, String) -> Path)? = null
    ): Path {
        val customNatives = env["BLOCKBOX_LWJGL_NATIVE_DIR"]?.takeIf { it.isNotBlank() }
        val settings = listOf("COURSIER_REPOSITORIES", "SCALA_CLI_CONFIG", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
            .associateWith { env[it].orEmpty() } + ("bundledNatives" to (customNatives == null).toString())
        return CompiledGameCache(cache).getOrBuild(sourceRoot, javaHome, platform, arch, settings, log, cancelled) { output, workspace ->
            val scala = findExecutable(env["BLOCKBOX_SCALA_CLI"] ?: "scala-cli", env, platform)
                ?: error("Scala CLI is needed for the first build or after source changes. Install scala-cli or set BLOCKBOX_SCALA_CLI.")
            require(platform != HostPlatform.WINDOWS || scala.extension.lowercase() !in listOf("cmd", "bat")) {
                "Select the native scala-cli.exe using BLOCKBOX_SCALA_CLI; batch shims cannot safely forward build arguments."
            }
            val command = mutableListOf(scala.toString(), "--power", "package", sourceRoot.resolve("src/main/scala").toString(),
                "--assembly", "--preamble=false", "--server=false", "--java-home", javaHome.toString(), "--workspace", workspace.toString(), "--output", output.toString())
            javaHelpers(sourceRoot, javaHome, env, log, cancelled, onProcess)?.let { command += "--extra-jar=$it" }
            sourceRoot.resolve("src/main/resources").takeIf { it.isDirectory() }?.let { command += "--resource-dir=$it" }
            if (customNatives == null) {
                val classifier = nativeClassifier(platform, arch)
                    ?: error("No bundled LWJGL natives for $platform/$arch. Set BLOCKBOX_LWJGL_NATIVE_DIR to compatible native libraries.")
                val dependencies = gameBuildInputs(sourceRoot).filter { it.extension in listOf("scala", "java", "sc") }
                    .flatMap { source -> Regex("org\\.lwjgl:(lwjgl(?:-[a-z0-9-]+)?):([0-9][A-Za-z0-9.+_-]*)").findAll(source.readText())
                        .map { it.groupValues[1] to it.groupValues[2] }.toList() }.distinct()
                require(dependencies.isNotEmpty()) { "No LWJGL dependencies were found in the game sources." }
                dependencies.filter { lwjglNeedsNativeJar(it.first, platform) }.forEach { (module, version) ->
                    val jar = resolveNative?.invoke(module, version, classifier) ?: nativeJar(module, version, classifier, log, cancelled)
                    command += "--extra-jar=$jar"
                }
            }
            runBuildCommand(command, sourceRoot, env, log, cancelled, onProcess, "Blockbox compilation failed. See the errors above.")

            // With --server=false, scalac only reads mixed .java sources for type
            // information. Compile them against the finished Scala/dependency
            // assembly too, so classes such as HardwareRayLoop reach runtime.
            val mixedJava = gameBuildInputs(sourceRoot).filter { it.extension == "java" && it.startsWith(sourceRoot.resolve("src/main/scala")) }
            if (mixedJava.isNotEmpty()) {
                log("Compiling ${mixedJava.size} mixed Java game sources…")
                val classes = output.parent.resolve("mixed-classes").createDirectories()
                val args = output.parent.resolve("mixed-sources.args")
                args.writeText(mixedJava.joinToString("\n") { quoteJavacPath(it) })
                val compiler = javaHome.resolve("bin/" + if (platform == HostPlatform.WINDOWS) "javac.exe" else "javac")
                runBuildCommand(listOf(compiler.toString(), "--release", "21", "-encoding", "UTF-8", "-classpath", output.toString(), "-d", classes.toString(), "@$args"),
                    sourceRoot, env, log, cancelled, onProcess, "Mixed Java game sources failed to compile. See the errors above.")
                val merged = output.resolveSibling("merged.jar")
                JarFile(output.toFile()).use { original -> JarOutputStream(merged.outputStream()).use { jar ->
                    val replacements = Files.walk(classes).use { paths -> paths.filter { it.isRegularFile() }.toList() }
                        .associateBy { classes.relativize(it).toString().replace('\\', '/') }
                    original.entries().asSequence().filter { it.name !in replacements }.forEach { entry ->
                        jar.putNextEntry(JarEntry(entry.name)); original.getInputStream(entry).use { it.copyTo(jar) }; jar.closeEntry()
                    }
                    replacements.forEach { (name, file) -> jar.putNextEntry(JarEntry(name)); Files.copy(file, jar); jar.closeEntry() }
                } }
                Files.move(merged, output, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun nativeJar(module: String, version: String, classifier: String, log: (String) -> Unit, cancelled: () -> Boolean): Path {
        val dir = cache.resolve("lwjgl/$version/$classifier").createDirectories()
        val fileName = "$module-$version-$classifier.jar"
        val target = dir.resolve(fileName)
        if (validJar(target)) return target
        check(!cancelled()) { "Launch cancelled." }
        log("Downloading $fileName")
        val temp = Files.createTempFile(dir, ".download-", ".tmp")
        val connection = URI("https://repo.maven.apache.org/maven2/org/lwjgl/$module/$version/$fileName").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            require(connection.responseCode == 200) { "Could not download $fileName (HTTP ${connection.responseCode}). Check whether this game version supports your OS/CPU." }
            connection.inputStream.use { input -> temp.outputStream().use { out ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                var count = input.read(buffer)
                while (count >= 0) {
                    check(!cancelled()) { "Launch cancelled." }
                    size += count; require(size < 100L * 1024 * 1024) { "Native library download is too large." }
                    out.write(buffer, 0, count); count = input.read(buffer)
                }
            } }
            require(validJar(temp)) { "Downloaded native library is not a valid JAR: $fileName" }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { connection.disconnect(); Files.deleteIfExists(temp) }
    }

    fun javaHelpers(sourceRoot: Path, javaHome: Path, env: Map<String, String>, log: (String) -> Unit, cancelled: () -> Boolean, onProcess: (Process?) -> Unit): Path? {
        val sourceDir = sourceRoot.resolve("src/main/java")
        if (!sourceDir.isDirectory()) return null
        val sources = Files.walk(sourceDir).use { stream -> stream.filter { it.isRegularFile() && it.extension == "java" }.sorted().toList() }
        if (sources.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(javaHome.toString().toByteArray())
        javaHome.resolve("release").takeIf { it.isRegularFile() }?.let { digest.update(it.readBytes()) }
        sources.forEach { digest.update(sourceDir.relativize(it).toString().toByteArray()); digest.update(it.readBytes()) }
        val key = HexFormat.of().formatHex(digest.digest())
        val dir = cache.resolve("java").createDirectories()
        val target = dir.resolve("helpers-$key.jar")
        if (validJar(target)) return target
        check(!cancelled()) { "Launch cancelled." }
        log("Compiling ${sources.size} Java game helpers…")
        val stage = Files.createTempDirectory(dir, ".compile-")
        try {
            val classes = stage.resolve("classes").createDirectories()
            val args = stage.resolve("sources.args")
            args.writeText(sources.joinToString("\n") { quoteJavacPath(it) })
            val compiler = javaHome.resolve("bin/" + if (HostPlatform.detect() == HostPlatform.WINDOWS) "javac.exe" else "javac")
            runBuildCommand(listOf(compiler.toString(), "--release", "21", "-encoding", "UTF-8", "-d", classes.toString(), "@$args"),
                stage, env, log, cancelled, onProcess, "Java game helpers failed to compile. See the errors above.")
            val output = stage.resolve("helpers.jar")
            JarOutputStream(output.outputStream()).use { jar -> Files.walk(classes).use { stream -> stream.filter { it.isRegularFile() }.forEach { file ->
                jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/'))); Files.copy(file, jar); jar.closeEntry()
            } } }
            Files.move(output, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            deleteBuildDirectory(stage)
        }
    }

    private fun validJar(path: Path): Boolean = path.isRegularFile() && runCatching { JarFile(path.toFile()).use { it.size() > 0 } }.getOrDefault(false)
}

private fun quoteJavacPath(path: Path) = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun runBuildCommand(command: List<String>, directory: Path, env: Map<String, String>, log: (String) -> Unit, cancelled: () -> Boolean, onProcess: (Process?) -> Unit, failure: String) {
    check(!cancelled()) { "Launch cancelled." }
    val process = ProcessBuilder(command).apply {
        directory(directory.toFile()); redirectErrorStream(true); environment().clear(); environment().putAll(env)
    }.start()
    try {
        onProcess(process)
        if (cancelled()) process.destroy()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
        require(process.waitFor() == 0) { if (cancelled()) "Launch cancelled." else failure }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        onProcess(null)
    }
}

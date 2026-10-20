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
            args.writeText(sources.joinToString("\n") { "\"" + it.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            val compiler = javaHome.resolve("bin/" + if (HostPlatform.detect() == HostPlatform.WINDOWS) "javac.exe" else "javac")
            val process = ProcessBuilder(compiler.toString(), "--release", "21", "-encoding", "UTF-8", "-d", classes.toString(), "@$args").apply {
                directory(stage.toFile()); redirectErrorStream(true); environment().clear(); environment().putAll(env)
            }.start()
            try {
                onProcess(process)
                if (cancelled()) process.destroy()
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(log) }
                require(process.waitFor() == 0) { if (cancelled()) "Launch cancelled." else "Java game helpers failed to compile. See the errors above." }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                onProcess(null)
            }
            val output = stage.resolve("helpers.jar")
            JarOutputStream(output.outputStream()).use { jar -> Files.walk(classes).use { stream -> stream.filter { it.isRegularFile() }.forEach { file ->
                jar.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/'))); Files.copy(file, jar); jar.closeEntry()
            } } }
            Files.move(output, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            Files.walk(stage).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun validJar(path: Path): Boolean = path.isRegularFile() && runCatching { JarFile(path.toFile()).use { it.size() > 0 } }.getOrDefault(false)
}

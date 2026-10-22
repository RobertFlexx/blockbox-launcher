package blockbox.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GameCacheTest {
    @TempDir lateinit var temp: Path
    private val javaHome get() = Path.of(System.getProperty("java.home"))

    // Container-only fixture for cache-policy tests; real game compilation is
    // checked separately, as are Java sources included in these assemblies.
    private fun gameJar(path: Path) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "example.Main"
        }
        path.parent.createDirectories()
        JarOutputStream(path.outputStream(), manifest).use { jar ->
            jar.putNextEntry(JarEntry("example/Main.class")); jar.write(byteArrayOf(1)); jar.closeEntry()
        }
    }

    private fun sourceRoot(): Path = temp.resolve("game with spaces").also { root ->
        root.resolve("src/main/scala").createDirectories().resolve("Main.scala").writeText("//> using dep \"org.lwjgl:lwjgl:3.4.1\"\nobject Main")
    }

    @Test fun `Vulkan and Java-only bindings do not request nonexistent native jars`() {
        for (platform in listOf(HostPlatform.LINUX, HostPlatform.WINDOWS, HostPlatform.BSD)) assertFalse(lwjglNeedsNativeJar("lwjgl-vulkan", platform))
        assertTrue(lwjglNeedsNativeJar("lwjgl-vulkan", HostPlatform.MACOS))
        assertFalse(lwjglNeedsNativeJar("lwjgl-egl", HostPlatform.LINUX))
        assertFalse(lwjglNeedsNativeJar("lwjgl-opencl", HostPlatform.WINDOWS))
        for (module in listOf("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb")) assertTrue(lwjglNeedsNativeJar(module, HostPlatform.LINUX))
    }

    @Test fun `identical inputs reuse a completed build without invoking builder`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        var builds = 0
        fun get(log: (String) -> Unit = {}) = cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), log, { false }) { output, _ -> builds++; gameJar(output) }
        val first = get()
        val messages = mutableListOf<String>()
        assertEquals(first, get(messages::add))
        assertEquals(1, builds)
        assertTrue(messages.any { "no compilation" in it })
        assertTrue(validGameJar(first))
    }

    @Test fun `source contents dependencies resources and platform invalidate builds`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        var builds = 0
        fun get(platform: HostPlatform = HostPlatform.LINUX, arch: String = "amd64", settings: Map<String, String> = emptyMap()) =
            cache.getOrBuild(root, javaHome, platform, arch, settings, {}, { false }) { output, _ -> builds++; gameJar(output) }
        val first = get()
        val source = root.resolve("src/main/scala/Main.scala")
        val modified = Files.getLastModifiedTime(source)
        source.writeText(source.readText().replace("3.4.1", "3.4.2"))
        Files.setLastModifiedTime(source, modified)
        val dependencyChanged = get()
        assertNotEquals(first, dependencyChanged)
        root.resolve("src/main/scala/Added.scala").writeText("object Added")
        val sourceAdded = get()
        assertNotEquals(dependencyChanged, sourceAdded)
        root.resolve("src/main/resources").createDirectories().resolve("resource.txt").writeText("bundled resource")
        val resourceAdded = get()
        assertNotEquals(sourceAdded, resourceAdded)
        root.resolve("src/main/scala/Added.scala").deleteExisting()
        assertNotEquals(resourceAdded, get())
        assertNotEquals(get(), get(HostPlatform.WINDOWS))
        assertNotEquals(get(), get(arch = "aarch64"))
        assertNotEquals(get(), get(settings = mapOf("bundledNatives" to "false")))
        assertEquals(8, builds)
    }

    @Test fun `worlds mods assets and build output do not cause recompilation`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        var builds = 0
        fun get() = cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { output, _ -> builds++; gameJar(output) }
        val first = get()
        for (dir in listOf("worlds", "mods", "assets/audio", "src/main/scala/.scala-build")) root.resolve(dir).createDirectories().resolve("new-file").writeText("runtime data")
        assertEquals(first, get())
        assertEquals(1, builds)
    }

    @Test fun `failed build leaves no usable partial artifact and preserves prior build`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        val source = root.resolve("src/main/scala/Main.scala")
        val original = source.readText()
        val first = cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { output, _ -> gameJar(output) }
        source.appendText("\nchanged")
        assertFailsWith<IllegalStateException> {
            cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { output, _ -> gameJar(output); error("compiler failed") }
        }
        source.writeText(original)
        assertEquals(first, cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { _, _ -> fail("Prior build must survive") })
        assertEquals(1, Files.walk(temp.resolve("cache")).use { files -> files.filter { it.name == "blockbox-game.jar" }.count().toInt() })
    }

    @Test fun `cancellation and source edits during compilation never commit a build`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        val cancelled = AtomicBoolean(false)
        assertFailsWith<IllegalStateException> {
            cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, cancelled::get) { output, _ -> gameJar(output); cancelled.set(true) }
        }
        assertFailsWith<IllegalStateException> {
            cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { output, _ ->
                gameJar(output); root.resolve("src/main/scala/Main.scala").appendText("\nchanged during build")
            }
        }
        assertEquals(0, Files.walk(temp.resolve("cache")).use { files -> files.filter { it.name == "blockbox-game.jar" || it.name.startsWith(".build-") }.count().toInt() })
    }

    @Test fun `concurrent launches share one compilation`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        val started = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val builds = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            fun get() = cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), { if ("Waiting" in it) waiting.countDown() }, { false }) { output, _ ->
                builds.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); gameJar(output)
            }
            val first = pool.submit<Path> { get() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Path> { get() }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, builds.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `missing or damaged cached jars rebuild automatically`() {
        val root = sourceRoot()
        val cache = CompiledGameCache(temp.resolve("cache"))
        var builds = 0
        fun get() = cache.getOrBuild(root, javaHome, HostPlatform.LINUX, "amd64", emptyMap(), {}, { false }) { output, _ -> builds++; gameJar(output) }
        val jar = get()
        jar.writeText("incomplete")
        assertEquals(jar, get())
        jar.deleteExisting()
        assertEquals(jar, get())
        assertEquals(3, builds)
    }

    private fun fakeScalaCli(): Path {
        gameJar(temp.resolve("template.jar"))
        return temp.resolve("fake-scala-cli").apply {
            // All script paths are passed via environment variables, not interpolated shell code.
            writeText("""#!/bin/sh
printf '%s\n' "${'$'}@" >> "${'$'}TEST_CALLS"
while [ "${'$'}#" -gt 0 ]; do
  if [ "${'$'}1" = --output ]; then shift; /bin/cp "${'$'}TEST_TEMPLATE" "${'$'}1"; exit 0; fi
  shift
done
exit 1
""")
            toFile().setExecutable(true)
        }
    }

    @Test fun `packaging skips Linux Vulkan native downloads and warm launches need no Scala CLI`() {
        if (HostPlatform.detect() == HostPlatform.WINDOWS) return
        val root = sourceRoot()
        root.resolve("src/main/scala/Main.scala").appendText("\n//> using dep \"org.lwjgl:lwjgl-vulkan:3.4.1\"\n//> using dep \"org.lwjgl:lwjgl-glfw:3.4.1\"")
        val cli = fakeScalaCli()
        val env = System.getenv() + mapOf("BLOCKBOX_SCALA_CLI" to cli.toString(), "TEST_CALLS" to temp.resolve("scala-calls.txt").toString(), "TEST_TEMPLATE" to temp.resolve("template.jar").toString())
        val natives = mutableListOf<String>()
        val build = GameBuildCache(temp.resolve("cache"))
        val jar = build.gameJar(root, javaHome, HostPlatform.LINUX, "amd64", env, {}, { false }, {}) { module, _, _ ->
            natives += module; temp.resolve("template.jar")
        }
        assertEquals(listOf("lwjgl", "lwjgl-glfw"), natives)
        val calls = temp.resolve("scala-calls.txt").readText()
        assertTrue("--assembly" in calls)
        val withoutCompiler = env + ("BLOCKBOX_SCALA_CLI" to "/does/not/exist")
        assertEquals(jar, build.gameJar(root, javaHome, HostPlatform.LINUX, "amd64", withoutCompiler, {}, { false }, {}) { _, _, _ -> fail("No native downloads on warm launch") })
        assertEquals(calls, temp.resolve("scala-calls.txt").readText())
    }

    @Test fun `Java sources beside Scala are compiled and included in the saved game jar`() {
        if (HostPlatform.detect() == HostPlatform.WINDOWS) return
        val root = sourceRoot()
        root.resolve("src/main/scala/Mixed.java").writeText("public class Mixed { public static int answer() { return 42; } }")
        val cli = fakeScalaCli()
        val env = System.getenv() + mapOf("BLOCKBOX_SCALA_CLI" to cli.toString(), "TEST_CALLS" to temp.resolve("scala-calls.txt").toString(), "TEST_TEMPLATE" to temp.resolve("template.jar").toString())
        val jar = GameBuildCache(temp.resolve("cache")).gameJar(root, javaHome, HostPlatform.LINUX, "amd64", env, {}, { false }, {}) { _, _, _ -> temp.resolve("template.jar") }
        JarFile(jar.toFile()).use { assertNotNull(it.getJarEntry("Mixed.class")) }
        java.net.URLClassLoader(arrayOf(jar.toUri().toURL()), null).use { loader -> assertEquals(42, loader.loadClass("Mixed").getMethod("answer").invoke(null)) }
    }
}

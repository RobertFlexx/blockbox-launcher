package blockbox.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LauncherTest {
    @TempDir lateinit var temp: Path

    private fun store() = LauncherStore(temp.resolve("data"), temp.resolve("game"))

    @Test fun `arguments preserve Windows paths spaces and empty values`() {
        assertEquals(listOf("-Dhome=C:\\Games\\Blockbox", "hello world", "", "it's fine"), splitCommandLine("-Dhome=C:\\Games\\Blockbox \"hello world\" \"\" \"it's fine\""))
        assertEquals(listOf("C:\\Program Files\\Java\\bin\\java.exe"), splitCommandLine("\"C:\\Program Files\\Java\\bin\\java.exe\""))
        assertFailsWith<IllegalArgumentException> { splitCommandLine("\"unfinished") }
    }

    @Test fun `environment accepts semicolons and rejects malformed lines`() {
        assertEquals(mapOf("PATH" to "C:\\Java;C:\\Tools", "NAME" to "two words", "EMPTY" to ""), parseEnv("# comment\n PATH=C:\\Java;C:\\Tools\nNAME=\"two words\"\nEMPTY="))
        assertFailsWith<IllegalArgumentException> { parseEnv("THIS IS WRONG") }
    }

    @Test fun `platform detection does not mistake Darwin for Windows`() {
        assertEquals(HostPlatform.MACOS, HostPlatform.detect("Darwin"))
        assertEquals(HostPlatform.WINDOWS, HostPlatform.detect("Windows 11"))
        assertEquals(HostPlatform.BSD, HostPlatform.detect("FreeBSD"))
        assertEquals("natives-macos-arm64", nativeClassifier(HostPlatform.MACOS, "aarch64"))
        assertEquals("natives-windows-arm64", nativeClassifier(HostPlatform.WINDOWS, "arm64"))
        assertNull(nativeClassifier(HostPlatform.BSD, "amd64"))
    }

    @Test fun `graphics controls respect host and do not force NVIDIA on normal X11`() {
        val env = mutableMapOf("PATH" to "C:\\Java;C:\\Tools", "LIBGL_ALWAYS_SOFTWARE" to "1")
        val original = env.toMap()
        applyGraphicsEnvironment(env, "x11-nvidia", HostPlatform.WINDOWS)
        assertEquals(original, env)
        applyGraphicsEnvironment(env, "x11", HostPlatform.LINUX)
        assertEquals("x11", env["GLFW_PLATFORM"])
        assertNull(env["__GLX_VENDOR_LIBRARY_NAME"])
        applyGraphicsEnvironment(env, "software", HostPlatform.LINUX)
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
    }

    @Test fun `settings round trip with unicode and directory identity wins over metadata`() {
        val store = store()
        val config = store.createInstance("世界 café", "Description ✓").copy(envVars = "TOKEN=a;b", minMemoryMb = 256, maxMemoryMb = 2048)
        store.saveInstance(config)
        assertEquals(config, store.loadInstances().single())
        val file = store.instanceDir(config).resolve("instance.properties")
        val props = Properties().apply { file.bufferedReader().use { load(it) }; setProperty("id", "../../outside") }
        writeProperties(file, props)
        assertEquals(config.id, store.loadInstances().single().id)
        assertFailsWith<IllegalArgumentException> { store.instanceDir(config.copy(id = "../outside")) }
        assertEquals(0L, Files.list(store.instanceDir(config)).use { it.filter { path -> path.name.endsWith(".tmp") }.count() })
    }

    @Test fun `broken instance does not prevent valid instances from loading`() {
        val store = store()
        val valid = store.createInstance("Valid", "")
        val broken = store.instancesRoot.resolve("broken").createDirectories()
        broken.resolve("instance.properties").writeText("name=\\uZZZZ")
        assertEquals(listOf(valid), store.loadInstances())
        assertTrue(store.warnings.any { "broken" in it })
    }

    @Test fun `duplicate includes worlds and does not duplicate logs`() {
        val store = store()
        val config = store.createInstance("Survival", "").copy(lastPlayed = "Yesterday")
        store.worldsDir(config).resolve("my world").createDirectories().resolve("world.dat").writeText("precious world")
        store.logsDir(config).resolve("old.log").writeText("old log")
        val copy = store.duplicateInstance(config)
        assertNotEquals(config.id, copy.id)
        assertEquals("Never", copy.lastPlayed)
        assertEquals("precious world", store.worldsDir(copy).resolve("my world/world.dat").readText())
        assertEquals(0, countFiles(store.logsDir(copy)))
    }

    @Test fun `mod enable collision never overwrites an installed mod`() {
        val store = store()
        val config = store.createInstance("Test", "")
        store.modsDir(config).resolve("mod.jar").writeText("enabled")
        store.modsDir(config).resolve("mod.jar.disabled").writeText("disabled")
        val mod = store.scanMods(config).first { !it.enabled }
        assertFailsWith<IllegalArgumentException> { store.toggleMod(config, mod) }
        assertEquals("enabled", store.modsDir(config).resolve("mod.jar").readText())
    }

    @Test fun `packs preserve metadata and optionally worlds`() {
        val store = store()
        val config = store.createInstance("世界= café", "A description\nwith another line")
        store.modsDir(config).resolve("mod.groovy").writeText("// a mod")
        store.worldsDir(config).resolve("world.dat").writeText("world")
        val without = store.importPack(store.exportPack(config, false).toString())
        assertEquals(config.name, without.name)
        assertEquals(config.description, without.description)
        assertEquals(0, countFiles(store.worldsDir(without)))
        assertEquals("// a mod", store.modsDir(without).resolve("mod.groovy").readText())
        val with = store.importPack(store.exportPack(config, true).toString())
        assertEquals("world", store.worldsDir(with).resolve("world.dat").readText())
    }

    @Test fun `malicious and invalid packs leave no partial instances`() {
        val store = store()
        store.createInstance("Keep me", "")
        val pack = temp.resolve("bad.bbpack")
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mods/../../escaped")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> { store.importPack(pack.toString()) }
        assertEquals(1, countFiles(store.instancesRoot))
        assertFalse(temp.resolve("escaped").exists())
        ZipOutputStream(pack.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("mods/a.jar")); zip.closeEntry() }
        assertFails { store.importPack(pack.toString()) }
        assertEquals(1, countFiles(store.instancesRoot))
    }

    @Test fun `cached game launches directly with Java and keeps instance cwd`() {
        val store = store()
        store.projectRoot.resolve("src/main/scala/blockbox").createDirectories().resolve("Main.scala").writeText("//> using dep \"org.lwjgl:lwjgl:3.4.1\"\n//> using dep \"org.lwjgl:lwjgl-glfw:3.4.1\"")
        val bin = temp.resolve("fake JDK/bin").createDirectories()
        listOf("java", "javac", "java.exe", "javac.exe").forEach { bin.resolve(it).writeText(""); bin.resolve(it).toFile().setExecutable(true) }
        val scala = temp.resolve("scala-cli.exe").apply { writeText(""); toFile().setExecutable(true) }
        val env = mapOf("JAVA_HOME" to bin.parent.toString(), "BLOCKBOX_SCALA_CLI" to scala.toString(), "Path" to "C:\\Java;C:\\Tools")
        val config = store.createInstance("Profile with spaces", "").copy(gameArgs = "\"two words\" \"\"", displayBackend = "x11-nvidia")
        val gameJar = temp.resolve("compiled game.jar")
        val windows = LaunchPlanner(store, HostPlatform.WINDOWS, "arm64", env, resolveGameJar = { _, _ -> gameJar }).prepare(config)
        assertEquals(store.instanceDir(config), windows.directory)
        assertEquals(bin.resolve("java.exe").toString(), windows.command.first())
        assertTrue(windows.command.windowed(2).any { it == listOf("-jar", gameJar.toString()) })
        assertFalse(windows.command.any { "scala-cli" in it || it == "--power" })
        assertTrue(windows.environment["Path"]!!.endsWith("C:\\Java;C:\\Tools"))
        assertNull(windows.environment["GLFW_PLATFORM"])
        assertEquals(listOf("two words", ""), windows.command.takeLast(2))
        val mac = LaunchPlanner(store, HostPlatform.MACOS, "aarch64", env, resolveGameJar = { _, _ -> gameJar }).prepare(config)
        assertTrue("-XstartOnFirstThread" in mac.command)
    }

    @Test fun `launch uses the game checkout for recorded sound effects`() {
        val store = store()
        store.projectRoot.resolve("src/main/scala/blockbox").createDirectories().resolve("Main.scala").writeText("//> using dep \"org.lwjgl:lwjgl:3.4.1\"\n")
        val sounds = store.projectRoot.resolve("assets/audio/kenney").createDirectories()
        sounds.resolve("footstep_grass_000.ogg").writeText("ogg")
        val bin = temp.resolve("jdk/bin").createDirectories()
        listOf("java", "javac").forEach { bin.resolve(it).writeText(""); bin.resolve(it).toFile().setExecutable(true) }
        val scala = temp.resolve("scala-cli").apply { writeText(""); toFile().setExecutable(true) }
        val env = mapOf("JAVA_HOME" to bin.parent.toString(), "BLOCKBOX_SCALA_CLI" to scala.toString(), "PATH" to bin.toString())
        val config = store.createInstance("Sounds", "")
        val plan = LaunchPlanner(store, HostPlatform.LINUX, "amd64", env, resolveGameJar = { _, _ -> temp.resolve("game.jar") }).prepare(config)
        assertEquals(store.instanceDir(config), plan.directory)
        assertTrue(plan.command.any { it == "-Dblockbox.audioRoot=${sounds.parent.toAbsolutePath().normalize()}" })
    }

    @Test fun `process failure resets launch state and permits retry`() {
        if (HostPlatform.detect() == HostPlatform.WINDOWS) return
        val store = store()
        val config = store.createInstance("Failure", "")
        val runner = GameRunner(store) { LaunchPlan(listOf("/no/such/executable"), store.instanceDir(it), System.getenv()) }
        repeat(2) {
            val exited = CountDownLatch(1)
            assertTrue(runner.launch(config, {}, {}, onExit = { exited.countDown() }))
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            assertEquals(RunState.IDLE, runner.state)
            assertNull(runner.activeInstanceId)
        }
    }

    @Test fun `software renderer stays alive and duplicate launch is rejected`() {
        if (HostPlatform.detect() == HostPlatform.WINDOWS) return
        val store = store()
        val config = store.createInstance("Software", "").copy(displayBackend = "software")
        store.saveInstance(config)
        val runner = GameRunner(store) { LaunchPlan(listOf("/bin/sh", "-c", "echo renderer=llvmpipe; sleep 30"), store.instanceDir(it), System.getenv()) }
        val output = CountDownLatch(1)
        val exited = CountDownLatch(1)
        assertTrue(runner.launch(config, { if ("renderer=llvmpipe" in it) output.countDown() }, {}, onExit = { exited.countDown() }))
        try {
            assertTrue(output.await(5, TimeUnit.SECONDS))
            assertFalse(runner.launch(config, {}, {}))
            assertEquals(RunState.RUNNING, runner.state)
            assertTrue(runner.process!!.isAlive)
            assertEquals("software", store.loadInstances().single().displayBackend)
        } finally { runner.stop() }
        assertTrue(exited.await(6, TimeUnit.SECONDS))
        assertEquals(RunState.IDLE, runner.state)
    }

    @Test fun `stop during preparation prevents a process from starting`() {
        if (HostPlatform.detect() == HostPlatform.WINDOWS) return
        val store = store()
        val config = store.createInstance("Cancel", "")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val marker = temp.resolve("must-not-exist")
        val runner = GameRunner(store) {
            entered.countDown(); release.await(5, TimeUnit.SECONDS)
            LaunchPlan(listOf("/usr/bin/touch", marker.toString()), store.instanceDir(it), System.getenv())
        }
        assertTrue(runner.launch(config, {}, {}, onExit = { exited.countDown() }))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        runner.stop(); release.countDown()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertFalse(marker.exists())
    }

    @Test fun `Java helpers are compiled to runnable bytecode and source edits invalidate cache`() {
        val root = temp.resolve("game with spaces")
        val source = root.resolve("src/main/java").createDirectories().resolve("GameHelper.java")
        source.writeText("public class GameHelper { public static int answer() { return 42; } }")
        val build = GameBuildCache(temp.resolve("build cache"))
        fun compile() = build.javaHelpers(root, Path.of(System.getProperty("java.home")), System.getenv(), {}, { false }, {})!!
        val first = compile()
        java.net.URLClassLoader(arrayOf(first.toUri().toURL()), null).use { loader ->
            assertEquals(42, loader.loadClass("GameHelper").getMethod("answer").invoke(null))
        }
        assertEquals(first, compile())
        source.writeText("public class GameHelper { public static int answer() { return 43; } }")
        assertNotEquals(first, compile())
    }

    @Test fun `cached native jars work without a network download`() {
        val cache = temp.resolve("cache")
        val jar = cache.resolve("lwjgl/3.4.1/natives-linux").createDirectories().resolve("lwjgl-3.4.1-natives-linux.jar")
        java.util.jar.JarOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(java.util.jar.JarEntry("linux/x64/liblwjgl.so")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        assertEquals(jar, GameBuildCache(cache).nativeJar("lwjgl", "3.4.1", "natives-linux", { fail("Should use cached native jar") }, { false }))
    }
}

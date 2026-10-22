package blockbox.launcher

import java.nio.file.Path
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BundledGameTest {
    @TempDir lateinit var temp: Path

    private fun bundle(id: String = "a".repeat(64), platform: HostPlatform = HostPlatform.detect(), arch: String = cpuArchitecture(System.getProperty("os.arch"))): Path {
        val root = temp.resolve("installed game").createDirectories()
        root.resolve("assets/textures").createDirectories().resolve("grass.png").writeText("texture")
        root.resolve("shaderpacks").createDirectories().resolve("active.txt").writeText("default")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "example.Main"
        }
        JarOutputStream(root.resolve("blockbox-game.jar").outputStream(), manifest).use { jar ->
            jar.putNextEntry(JarEntry("example/Main.class")); jar.write(byteArrayOf(1)); jar.closeEntry()
        }
        writeProperties(root.resolve(BUNDLED_GAME_DESCRIPTOR), Properties().apply {
            setProperty("format", "Blockbox Bundled Game"); setProperty("formatVersion", "1"); setProperty("id", id)
            setProperty("revision", "test release"); setProperty("platform", platform.name); setProperty("arch", arch)
        })
        return root
    }

    @Test fun `included game needs a runtime but no compiler Scala CLI or checkout`() {
        val root = bundle()
        val store = LauncherStore(temp.resolve("player data"), root)
        val runtime = temp.resolve("private runtime/bin").createDirectories()
        val javaName = if (HostPlatform.detect() == HostPlatform.WINDOWS) "java.exe" else "java"
        val executable = runtime.resolve(javaName).apply { writeText(""); toFile().setExecutable(true) }
        val config = store.createInstance("Play", "").copy(javaPath = executable.toString())
        val plan = LaunchPlanner(store, inheritedEnv = mapOf("PATH" to "", "BLOCKBOX_SCALA_CLI" to "/missing/scala-cli"),
            resolveNative = { _, _, _ -> fail("Installed game must not fetch native jars") },
            resolveGameJar = { _, _ -> fail("Installed game must not invoke the source build") }).prepare(config)
        assertEquals(executable.toString(), plan.command.first())
        assertTrue(plan.command.windowed(2).any { it == listOf("-jar", root.resolve("blockbox-game.jar").toString()) })
        assertEquals(store.instanceDir(config), plan.directory)
        assertEquals(store.configDir(config).resolve("game").toString(), plan.environment["BLOCKBOX_PROJECT_ROOT"])
        assertTrue(store.configDir(config).resolve("game/assets/textures/grass.png").exists())
        assertFalse(root.resolve("src").exists())
        assertFalse(runtime.resolve("javac").exists())
    }

    @Test fun `game upgrades preserve player settings selected shader and custom packs`() {
        val root = bundle()
        val store = LauncherStore(temp.resolve("data"), root)
        val config = store.createInstance("Survival", "")
        val runtime = BundledGame.read(root)!!.prepareRuntime(store, config, {}, { false })
        runtime.resolve("blockbox-settings.properties").writeText("volume=0.5")
        runtime.resolve("shaderpacks/active.txt").writeText("my-pack")
        runtime.resolve("shaderpacks/my-pack").createDirectories().resolve("pack.properties").writeText("custom")
        val second = bundle("b".repeat(64))
        second.resolve("assets/textures/grass.png").writeText("updated texture")
        BundledGame.read(second)!!.prepareRuntime(store, config, {}, { false })
        assertEquals("updated texture", runtime.resolve("assets/textures/grass.png").readText())
        assertEquals("volume=0.5", runtime.resolve("blockbox-settings.properties").readText())
        assertEquals("my-pack", runtime.resolve("shaderpacks/active.txt").readText())
        assertEquals("custom", runtime.resolve("shaderpacks/my-pack/pack.properties").readText())
        val next = store.createInstance("Creative", "")
        val separate = BundledGame.read(root)!!.prepareRuntime(store, next, {}, { false })
        assertNotEquals(runtime, separate)
        assertFalse(separate.resolve("blockbox-settings.properties").exists())
    }

    @Test fun `wrong architecture is rejected before attempting to build or launch`() {
        val root = bundle(arch = "wrong-cpu")
        val store = LauncherStore(temp.resolve("data"), root)
        val config = store.createInstance("Play", "")
        val error = assertFailsWith<IllegalArgumentException> { LaunchPlanner(store).prepare(config) }
        assertTrue(error.message!!.contains("Download the build"))
    }

    @Test fun `installed game can be discovered and selected without source files`() {
        val root = bundle()
        val before = System.getProperty("blockbox.bundled.game")
        try {
            System.setProperty("blockbox.bundled.game", root.toString())
            assertEquals(root, includedGameRoot())
            val store = LauncherStore(temp.resolve("data"), temp.resolve("missing checkout"))
            store.setGameRoot(root)
            assertEquals(root, store.projectRoot)
        } finally {
            if (before == null) System.clearProperty("blockbox.bundled.game") else System.setProperty("blockbox.bundled.game", before)
        }
    }

    @Test fun `damaged included game fails clearly instead of demanding developer tools`() {
        val root = bundle()
        root.resolve("blockbox-game.jar").writeText("damaged")
        val error = assertFailsWith<IllegalArgumentException> { BundledGame.read(root) }
        assertTrue(error.message!!.contains("Reinstall"))
    }

    @Test fun `included game selection follows AppImage remounts and application moves`() {
        val original = bundle()
        val data = temp.resolve("player data")
        val before = System.getProperty("blockbox.bundled.game")
        try {
            System.setProperty("blockbox.bundled.game", original.toString())
            LauncherStore(data, original).setGameRoot(original)
            val moved = temp.resolve("new application mount/game")
            moved.parent.createDirectories()
            java.nio.file.Files.move(original, moved)
            System.setProperty("blockbox.bundled.game", moved.toString())
            val next = LauncherStore(data, moved)
            assertEquals(moved, next.projectRoot)
            assertNull(next.preferences.getProperty("gameRoot"))
            assertEquals("included", next.preferences.getProperty("gameSource"))
            val checkout = temp.resolve("developer checkout")
            checkout.resolve("src/main/scala/blockbox").createDirectories().resolve("Main.scala").writeText("source")
            next.setGameRoot(checkout)
            assertEquals(checkout, LauncherStore(data, moved).projectRoot)
        } finally {
            if (before == null) System.clearProperty("blockbox.bundled.game") else System.setProperty("blockbox.bundled.game", before)
        }
    }
}

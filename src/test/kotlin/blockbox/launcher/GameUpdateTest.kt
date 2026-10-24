package blockbox.launcher

import java.nio.file.Path
import java.util.Properties
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class GameUpdateTest {
    @TempDir lateinit var temp: Path

    @Test fun `an up to date checkout is launched without fetching or merging`() {
        val player = checkout()
        val before = head(player)
        player.resolve(".git/FETCH_HEAD").toFile().delete()
        val messages = update(player)
        assertEquals(before, head(player))
        assertTrue(messages.any { "up to date" in it })
        assertFalse(player.resolve(".git/FETCH_HEAD").exists())
        assertFalse(player.resolve(".git/MERGE_HEAD").exists())
    }

    @Test fun `a clean checkout fast-forwards when the remote has new commits`() {
        val (publisher, player) = pair()
        writeGame(publisher, "object Main\nval version = 2\n")
        commit(publisher, "version 2")
        git(publisher, "push")
        val messages = update(player)
        assertEquals("object Main\nval version = 2\n", readGame(player))
        assertEquals(head(publisher), head(player))
        assertTrue(messages.any { "Updated Blockbox" in it })
        player.resolve(".git/FETCH_HEAD").toFile().delete()
        val again = update(player)
        assertTrue(again.any { "up to date" in it })
        assertFalse(player.resolve(".git/FETCH_HEAD").exists())
    }

    @Test fun `local edits and unpushed commits are left untouched`() {
        val (publisher, player) = pair()
        writeGame(player, "object Main\nval localEdit = true\n")
        writeGame(publisher, "object Main\nval version = 2\n")
        commit(publisher, "version 2")
        git(publisher, "push")
        val dirtyHead = head(player)
        val dirty = update(player)
        assertEquals("object Main\nval localEdit = true\n", readGame(player))
        assertEquals(dirtyHead, head(player))
        assertTrue(dirty.any { "local changes" in it })
    }

    @Test fun `unpushed local commits are not rewound`() {
        val player = checkout()
        writeGame(player, "object Main\nval localCommit = true\n")
        commit(player, "local only")
        val ahead = head(player)
        val kept = update(player)
        assertEquals(ahead, head(player))
        assertEquals("object Main\nval localCommit = true\n", readGame(player))
        assertTrue(kept.any { "ahead" in it })
    }

    @Test fun `diverged history is not merged or reset`() {
        val (publisher, player) = pair()
        writeGame(publisher, "object Main\nval remote = true\n")
        commit(publisher, "remote")
        git(publisher, "push")
        writeGame(player, "object Main\nval local = true\n")
        commit(player, "local")
        val before = head(player)
        val messages = update(player)
        assertEquals(before, head(player))
        assertEquals("object Main\nval local = true\n", readGame(player))
        assertFalse(player.resolve(".git/MERGE_HEAD").exists())
        assertTrue(messages.any { "cannot fast-forward" in it })
    }

    @Test fun `an untracked file does not block a fast-forward`() {
        val (publisher, player) = pair()
        player.resolve("notes.txt").writeText("keep me")
        writeGame(publisher, "object Main\nval version = 2\n")
        commit(publisher, "version 2")
        git(publisher, "push")
        update(player)
        assertEquals("object Main\nval version = 2\n", readGame(player))
        assertEquals("keep me", player.resolve("notes.txt").readText())
    }

    @Test fun `a folder that is not a checkout and an unreachable remote still launch`() {
        val plain = temp.resolve("plain").createDirectories()
        assertEquals(emptyList(), update(plain))
        val player = checkout()
        val before = head(player)
        git(player, "remote", "set-url", "origin", temp.resolve("missing-remote").toString())
        val messages = update(player)
        assertEquals(before, head(player))
        assertTrue(messages.any { "Could not reach" in it })
    }

    @Test fun `launch updates the checkout before the game starts`() {
        val (publisher, player) = pair()
        writeGame(publisher, "object Main\nval version = 2\n")
        commit(publisher, "version 2")
        git(publisher, "push")
        val store = LauncherStore(temp.resolve("data"), player)
        val bin = temp.resolve("jdk/bin").createDirectories()
        listOf("java", "javac").forEach { bin.resolve(it).writeText(""); bin.resolve(it).toFile().setExecutable(true) }
        val env = mapOf("JAVA_HOME" to bin.parent.toString(), "PATH" to System.getenv("PATH").orEmpty())
        LaunchPlanner(store, HostPlatform.LINUX, "amd64", env, resolveGameJar = { _, _ -> temp.resolve("game.jar") })
            .prepare(store.createInstance("Updated", ""))
        assertEquals("object Main\nval version = 2\n", readGame(player))
    }

    @Test fun `an included snapshot launches a newer sibling checkout`() {
        assumeTrue(findExecutable("git") != null)
        val (publisher, player) = pairAt(temp.resolve("blockbox"))
        val includedRevision = head(player)
        writeGame(publisher, "object Main\nval version = 2\n")
        commit(publisher, "version 2")
        git(publisher, "push")
        val bundled = includedGame(includedRevision)
        val store = LauncherStore(temp.resolve("data"), bundled)
        val built = temp.resolve("built-game.jar")
        val plan = launch(store, built)
        assertEquals("object Main\nval version = 2\n", readGame(player))
        assertTrue(plan.command.contains(built.toString()))
        assertEquals(player.toAbsolutePath().normalize().toString(), plan.environment["BLOCKBOX_PROJECT_ROOT"])
        player.resolve(".git/FETCH_HEAD").toFile().delete()
        val again = mutableListOf<String>()
        val second = launch(store, built, again)
        assertTrue(again.any { "up to date" in it })
        assertTrue(again.any { "ahead of the included game" in it })
        assertFalse(player.resolve(".git/FETCH_HEAD").exists())
        assertTrue(second.command.contains(built.toString()))
    }

    @Test fun `an included snapshot stays when the sibling checkout is the same commit`() {
        assumeTrue(findExecutable("git") != null)
        val player = temp.resolve("blockbox")
        git(temp, "init", "-b", "main", player.toString())
        configure(player)
        writeGame(player, "object Main\nval version = 1\n")
        commit(player, "version 1")
        val store = LauncherStore(temp.resolve("data"), includedGame(head(player)))
        val plan = launch(store, null)
        assertTrue(plan.command.windowed(2).any { it == listOf("-jar", store.projectRoot.resolve("blockbox-game.jar").toString()) })
    }

    @Test fun `an older clean checkout keeps the newer included snapshot`() {
        assumeTrue(findExecutable("git") != null)
        val player = temp.resolve("blockbox")
        git(temp, "init", "-b", "main", player.toString())
        configure(player)
        writeGame(player, "object Main\nval version = 1\n")
        commit(player, "version 1")
        writeGame(player, "object Main\nval version = 2\n")
        commit(player, "version 2")
        val newer = head(player)
        git(player, "reset", "--hard", "HEAD~1")
        val store = LauncherStore(temp.resolve("data"), includedGame(newer))
        val plan = launch(store, null)
        assertEquals("object Main\nval version = 1\n", readGame(player))
        assertTrue(plan.command.windowed(2).any { it == listOf("-jar", store.projectRoot.resolve("blockbox-game.jar").toString()) })
    }

    @Test fun `local changes launch the checkout even when the included snapshot is newer`() {
        assumeTrue(findExecutable("git") != null)
        val player = temp.resolve("blockbox")
        git(temp, "init", "-b", "main", player.toString())
        configure(player)
        writeGame(player, "object Main\nval version = 1\n")
        commit(player, "version 1")
        writeGame(player, "object Main\nval version = 2\n")
        commit(player, "version 2")
        val newer = head(player)
        git(player, "reset", "--hard", "HEAD~1")
        writeGame(player, "object Main\nval localEdit = true\n")
        val store = LauncherStore(temp.resolve("data"), includedGame(newer))
        val built = temp.resolve("built-game.jar")
        val messages = mutableListOf<String>()
        val plan = launch(store, built, messages)
        assertEquals("object Main\nval localEdit = true\n", readGame(player))
        assertTrue(plan.command.contains(built.toString()))
        assertTrue(messages.any { "local changes" in it })
    }

    @Test fun `cancellation does not change the checkout`() {
        assumeTrue(findExecutable("git") != null)
        val player = checkout()
        val before = head(player)
        assertFailsWith<IllegalStateException> { updateGameCheckout(player, {}, { true }) }
        assertEquals(before, head(player))
    }

    private fun pair(): Pair<Path, Path> = pairAt(temp.resolve("player-${System.nanoTime()}"))

    private fun pairAt(player: Path): Pair<Path, Path> {
        val bare = temp.resolve("remote-${System.nanoTime()}.git")
        val publisher = temp.resolve("publisher-${bare.fileName}")
        git(temp, "init", "--bare", "-b", "main", bare.toString())
        git(temp, "clone", bare.toString(), publisher.toString())
        configure(publisher)
        writeGame(publisher, "object Main\nval version = 1\n")
        commit(publisher, "version 1")
        git(publisher, "push", "-u", "origin", "main")
        git(temp, "clone", bare.toString(), player.toString())
        configure(player)
        return publisher to player
    }

    private fun checkout(): Path = pair().second

    private fun includedGame(revision: String): Path {
        val root = temp.resolve("included-$revision").createDirectories()
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "example.Main"
        }
        JarOutputStream(root.resolve("blockbox-game.jar").outputStream(), manifest).use { jar ->
            jar.putNextEntry(JarEntry("example/Main.class")); jar.write(byteArrayOf(1)); jar.closeEntry()
        }
        writeProperties(root.resolve(BUNDLED_GAME_DESCRIPTOR), Properties().apply {
            setProperty("format", "Blockbox Bundled Game"); setProperty("formatVersion", "1"); setProperty("id", "a".repeat(64))
            setProperty("revision", revision); setProperty("platform", "LINUX"); setProperty("arch", "x64")
        })
        return root
    }

    private fun launch(store: LauncherStore, built: Path?, log: MutableList<String> = mutableListOf()): LaunchPlan {
        val jdk = temp.resolve("jdk-${System.nanoTime()}")
        val bin = jdk.resolve("bin").createDirectories()
        listOf("java", "javac").forEach { bin.resolve(it).writeText(""); bin.resolve(it).toFile().setExecutable(true) }
        val env = mapOf("JAVA_HOME" to jdk.toString(), "PATH" to System.getenv("PATH").orEmpty())
        return LaunchPlanner(store, HostPlatform.LINUX, "amd64", env, resolveGameJar = { _, _ -> built ?: fail("Included game must not invoke the source build") })
            .prepare(store.createInstance("Play", ""), log::add)
    }

    private fun configure(dir: Path) {
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
    }

    private fun commit(dir: Path, message: String) {
        git(dir, "add", "-A")
        git(dir, "commit", "-m", message)
    }

    private fun writeGame(dir: Path, source: String) {
        val file = dir.resolve("src/main/scala/blockbox/Main.scala")
        file.parent.createDirectories()
        file.writeText(source)
    }

    private fun readGame(dir: Path) = dir.resolve("src/main/scala/blockbox/Main.scala").readText()

    private fun head(dir: Path): String = git(dir, "rev-parse", "HEAD")

    private fun update(root: Path): List<String> {
        assumeTrue(findExecutable("git") != null)
        val messages = mutableListOf<String>()
        updateGameCheckout(root, messages::add, { false })
        return messages
    }

    private fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        assertEquals(0, process.waitFor(), text)
        return text
    }
}

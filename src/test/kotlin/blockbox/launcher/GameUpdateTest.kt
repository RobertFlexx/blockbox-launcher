package blockbox.launcher

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test fun `cancellation does not change the checkout`() {
        assumeTrue(findExecutable("git") != null)
        val player = checkout()
        val before = head(player)
        assertFailsWith<IllegalStateException> { updateGameCheckout(player, {}, { true }) }
        assertEquals(before, head(player))
    }

    private fun pair(): Pair<Path, Path> {
        val bare = temp.resolve("remote-${System.nanoTime()}.git")
        val publisher = temp.resolve("publisher-${bare.fileName}")
        val player = temp.resolve("player-${bare.fileName}")
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

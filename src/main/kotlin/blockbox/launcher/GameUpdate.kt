package blockbox.launcher

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** Bring a source checkout up to its remote when that remote has moved, and otherwise leave it alone. */
internal fun updateGameCheckout(root: Path, log: (String) -> Unit, cancelled: () -> Boolean, env: Map<String, String> = System.getenv()) {
    if (!root.resolve(".git").exists()) return
    val git = findExecutable("git", env) ?: run {
        log("Git is not installed, so this checkout was not checked for updates.")
        return
    }
    try {
        withUpdateLock(root, cancelled) { syncCheckout(git, root, log, cancelled, env) }
    } catch (e: IllegalStateException) {
        if (e.message == "Launch cancelled.") throw e
        log("Game update skipped: ${e.message}")
    } catch (e: Exception) {
        log("Game update skipped: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun syncCheckout(git: Path, root: Path, log: (String) -> Unit, cancelled: () -> Boolean, env: Map<String, String>) {
    check(!cancelled()) { "Launch cancelled." }
    val branch = git(git, root, listOf("rev-parse", "--abbrev-ref", "HEAD"), env, 15, cancelled).takeIf { it.code == 0 }?.output
    if (branch.isNullOrBlank() || branch == "HEAD") {
        log("This checkout is not on a branch, so it was left unchanged.")
        return
    }
    val remote = git(git, root, listOf("config", "--get", "branch.$branch.remote"), env, 15, cancelled).takeIf { it.code == 0 }?.output?.takeIf { it.isNotBlank() } ?: "origin"
    if (remote == ".") {
        log("This branch tracks another local branch, so it was left unchanged.")
        return
    }
    val mergeRef = git(git, root, listOf("config", "--get", "branch.$branch.merge"), env, 15, cancelled).takeIf { it.code == 0 }?.output?.takeIf { it.startsWith("refs/heads/") }
        ?: "refs/heads/$branch"
    val remoteBranch = mergeRef.removePrefix("refs/heads/")
    log("Checking $remote/$remoteBranch for game updates…")
    val remoteTip = remoteHead(git, root, remote, remoteBranch, env, cancelled)
    if (remoteTip == null) {
        log("Could not reach $remote. Launching this checkout.")
        return
    }
    if (remoteTip.isEmpty()) {
        log("No $remote/$remoteBranch on the remote. Launching this checkout.")
        return
    }
    val head = git(git, root, listOf("rev-parse", "HEAD"), env, 15, cancelled).takeIf { it.code == 0 }?.output
    if (head == null) {
        log("Could not read this checkout. Launching it as it is.")
        return
    }
    if (head == remoteTip) {
        log("Game is up to date.")
        return
    }
    if (trackedChanges(git, root, env, cancelled)) {
        log("This checkout has local changes, so it was not updated. Launching it as it is.")
        return
    }
    val available = haveCommit(git, root, remoteTip, env, cancelled) || fetch(git, root, remote, remoteBranch, env, cancelled)
    if (!available || !haveCommit(git, root, remoteTip, env, cancelled)) {
        log("Could not download the game update. Launching this checkout.")
        return
    }
    when {
        git(git, root, listOf("merge-base", "--is-ancestor", remoteTip, head), env, 15, cancelled).code == 0 ->
            log("This checkout is ahead of $remote/$remoteBranch. Launching your local commits.")
        git(git, root, listOf("merge-base", "--is-ancestor", head, remoteTip), env, 15, cancelled).code == 0 -> {
            val merged = git(git, root, listOf("merge", "--ff-only", remoteTip), env, 60, cancelled)
            if (merged.code == 0) log("Updated Blockbox (${head.take(12)} → ${remoteTip.take(12)}).")
            else log("Game update did not apply. Launching this checkout.")
        }
        else -> log("The remote history cannot fast-forward this checkout, so it was left unchanged.")
    }
}

private fun remoteHead(git: Path, root: Path, remote: String, branch: String, env: Map<String, String>, cancelled: () -> Boolean): String? {
    val listed = git(git, root, listOf("ls-remote", remote, "refs/heads/$branch"), env, 20, cancelled)
    if (listed.code != 0) return null
    val line = listed.output.lineSequence().firstOrNull { it.substringAfter('\t').trim() == "refs/heads/$branch" } ?: return ""
    val sha = line.substringBefore('\t').substringBefore(' ').trim()
    return sha.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: ""
}

private fun trackedChanges(git: Path, root: Path, env: Map<String, String>, cancelled: () -> Boolean): Boolean =
    git(git, root, listOf("status", "--porcelain=v1", "--untracked-files=no"), env, 20, cancelled).output.isNotBlank()

private fun haveCommit(git: Path, root: Path, sha: String, env: Map<String, String>, cancelled: () -> Boolean): Boolean =
    git(git, root, listOf("cat-file", "-e", "$sha^{commit}"), env, 15, cancelled).code == 0

private fun fetch(git: Path, root: Path, remote: String, branch: String, env: Map<String, String>, cancelled: () -> Boolean): Boolean =
    git(git, root, listOf("fetch", "--prune", remote, "refs/heads/$branch:refs/remotes/$remote/$branch"), env, 60, cancelled).code == 0

private data class GitCommand(val code: Int, val output: String)

private fun git(git: Path, root: Path, args: List<String>, env: Map<String, String>, timeoutSeconds: Long, cancelled: () -> Boolean): GitCommand {
    check(!cancelled()) { "Launch cancelled." }
    val process = ProcessBuilder(listOf(git.toString(), "-C", root.toString()) + args).apply {
        redirectErrorStream(true)
        environment().putAll(env)
        environment()["GIT_TERMINAL_PROMPT"] = "0"
        environment()["GCM_INTERACTIVE"] = "Never"
    }.start()
    val output = StringBuilder()
    val reader = thread(name = "blockbox-git", isDaemon = true) {
        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length < 8_000) output.append(buffer, 0, count)
            }
        }
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var finished = false
    while (!finished && System.nanoTime() < deadline) {
        if (cancelled()) break
        finished = process.waitFor(200, TimeUnit.MILLISECONDS)
    }
    if (!finished || cancelled()) {
        process.destroyForcibly()
        reader.join(1_000)
        check(!cancelled()) { "Launch cancelled." }
        return GitCommand(124, output.toString().trim())
    }
    reader.join(1_000)
    return GitCommand(process.exitValue(), output.toString().trim())
}

private fun withUpdateLock(root: Path, cancelled: () -> Boolean, body: () -> Unit) {
    val gitDir = root.resolve(".git")
    if (!gitDir.isDirectory()) { body(); return }
    val channel = FileChannel.open(gitDir.resolve("blockbox-launcher-update.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    channel.use {
        var held: java.nio.channels.FileLock? = null
        while (held == null) {
            check(!cancelled()) { "Launch cancelled." }
            held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (held == null) Thread.sleep(100)
        }
        held.use { body() }
    }
}

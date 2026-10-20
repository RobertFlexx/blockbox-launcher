package blockbox.launcher

import java.awt.Component
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun parseEnv(raw: String): Map<String, String> = buildMap {
    raw.lineSequence().forEachIndexed { index, line ->
        val value = line.trim()
        if (value.isNotEmpty() && !value.startsWith('#')) {
            val key = value.substringBefore('=').trim()
            require('=' in value && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Environment line ${index + 1} must be KEY=value." }
            val text = value.substringAfter('=').trim()
            put(key, if (text.length >= 2 && text.first() in "\"'" && text.last() == text.first()) text.substring(1, text.length - 1) else text)
        }
    }
}

private val ansi = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\))")
fun stripAnsi(value: String): String = value.replace(ansi, "").replace("\r", "")

// Keep Windows path separators literal, and preserve explicitly empty arguments.
fun splitCommandLine(raw: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        val next = raw.getOrNull(index + 1)
        when {
            ch == '\\' && quote != '\'' && next != null && (next == '"' || (quote == null && (next.isWhitespace() || next == '\''))) -> { current.append(next); started = true; index++ }
            ch == quote -> { quote = null; started = true }
            quote == null && ch in "\"'" -> { quote = ch; started = true }
            quote == null && ch.isWhitespace() -> if (started) { result += current.toString(); current.clear(); started = false }
            else -> { current.append(ch); started = true }
        }
        index++
    }
    require(quote == null) { "An argument has an unclosed quote." }
    if (started) result += current.toString()
    require(result.none { '\n' in it || '\r' in it || '\u0000' in it }) { "Arguments cannot contain line breaks or NUL characters." }
    return result
}

fun choosePath(parent: Component, title: String, directories: Boolean = false, extension: String? = null, fileOrDirectory: Boolean = false): Path? {
    check(javax.swing.SwingUtilities.isEventDispatchThread())
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = when { fileOrDirectory -> JFileChooser.FILES_AND_DIRECTORIES; directories -> JFileChooser.DIRECTORIES_ONLY; else -> JFileChooser.FILES_ONLY }
        if (extension != null) fileFilter = FileNameExtensionFilter("Blockbox pack (*.$extension)", extension)
    }
    return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath().toAbsolutePath() else null
}

fun openPath(path: Path): String {
    require(Files.exists(path)) { "Folder does not exist: $path" }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(path.toFile())
    else ProcessBuilder(when (HostPlatform.detect()) {
        HostPlatform.WINDOWS -> listOf("explorer.exe", path.toAbsolutePath().toString())
        HostPlatform.MACOS -> listOf("open", path.toAbsolutePath().toString())
        else -> listOf("xdg-open", path.toAbsolutePath().toString())
    }).start()
    return "Opened ${path.fileName}"
}

fun countFiles(path: Path): Int = if (Files.isDirectory(path)) Files.list(path).use { it.count().toInt() } else 0

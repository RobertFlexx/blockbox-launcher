package blockbox.launcher

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.FilenameFilter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

fun parseEnv(raw: String): Map<String, String> = raw.split('\n', ';').mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isBlank() || !trimmed.contains('=')) null else trimmed.substringBefore('=') to trimmed.substringAfter('=')
}.toMap()

fun stripAnsi(value: String): String = value.replace(Regex("\\u001B\\[[;?0-9]*[ -/]*[@-~]"), "")

fun splitCommandLine(raw: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    fun flush() {
        if (current.isNotEmpty()) {
            result += current.toString()
            current.clear()
        }
    }
    for (ch in raw) {
        when {
            escaping -> {
                current.append(ch)
                escaping = false
            }
            ch == '\\' -> escaping = true
            quote != null && ch == quote -> quote = null
            quote == null && (ch == '\'' || ch == '"') -> quote = ch
            quote == null && ch.isWhitespace() -> flush()
            else -> current.append(ch)
        }
    }
    if (escaping) current.append('\\')
    flush()
    return result
}

fun chooseFile(title: String, extension: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.filenameFilter = FilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
    dialog.file = "*.$extension"
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(directory, file).toAbsolutePath().toString()
}

fun chooseAnyFileOrFolder(title: String): String? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(directory, file).toAbsolutePath().toString()
}

fun openPath(path: Path): String = try {
    path.createDirectories()
    if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().open(path.toFile())
        "Opened ${path.toAbsolutePath()}"
    } else {
        val command = when {
            System.getProperty("os.name").lowercase().contains("win") -> listOf("explorer", path.toAbsolutePath().toString())
            System.getProperty("os.name").lowercase().contains("mac") -> listOf("open", path.toAbsolutePath().toString())
            else -> listOf("xdg-open", path.toAbsolutePath().toString())
        }
        ProcessBuilder(command).start()
        "Opened ${path.toAbsolutePath()}"
    }
} catch (e: Exception) {
    e.message ?: "Could not open ${path.toAbsolutePath()}"
}

fun commandExists(command: String): Boolean = try {
    ProcessBuilder("sh", "-c", "command -v ${command.replace("'", "'\\''")}").start().waitFor() == 0
} catch (_: Exception) {
    false
}

fun countFiles(path: Path): Int = try {
    if (!Files.exists(path)) 0 else Files.list(path).use { it.count().toInt() }
} catch (_: Exception) {
    0
}

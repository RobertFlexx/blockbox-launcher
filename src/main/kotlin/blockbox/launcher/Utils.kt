package blockbox.launcher

import java.awt.Desktop
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
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
    platformFilePicker(title, extension, false)?.let { return it }
    return swingFilePicker(title, extension, false)
}

fun chooseAnyFileOrFolder(title: String): String? {
    platformFilePicker(title, null, true)?.let { return it }
    return swingFilePicker(title, null, true)
}

private fun platformFilePicker(title: String, extension: String?, allowDirectory: Boolean): String? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> windowsPicker(title, extension)
        commandExists("kdialog") -> runPicker(buildList {
            add("kdialog")
            add("--title")
            add(title)
            add("--getopenfilename")
            add(System.getProperty("user.home"))
            if (extension != null) add("*.$extension")
        })
        commandExists("zenity") -> runPicker(buildList {
            add("zenity")
            add("--file-selection")
            add("--title=$title")
            if (allowDirectory) add("--directory")
            if (extension != null) add("--file-filter=Blockbox packs (*.$extension) | *.$extension")
        })
        commandExists("yad") -> runPicker(buildList {
            add("yad")
            add("--file")
            add("--title=$title")
            if (allowDirectory) add("--directory")
            if (extension != null) add("--file-filter=*.$extension")
        })
        commandExists("qarma") -> runPicker(buildList {
            add("qarma")
            add("--file-selection")
            add("--title=$title")
            if (allowDirectory) add("--directory")
            if (extension != null) add("--file-filter=*.$extension")
        })
        commandExists("matedialog") -> runPicker(buildList {
            add("matedialog")
            add("--file-selection")
            add("--title=$title")
            if (allowDirectory) add("--directory")
            if (extension != null) add("--file-filter=*.$extension")
        })
        else -> null
    }
}

private fun swingFilePicker(title: String, extension: String?, allowDirectory: Boolean): String? = try {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val chooser = JFileChooser(System.getProperty("user.home"))
    chooser.dialogTitle = title
    chooser.fileSelectionMode = if (allowDirectory) JFileChooser.FILES_AND_DIRECTORIES else JFileChooser.FILES_ONLY
    chooser.isAcceptAllFileFilterUsed = true
    if (extension != null) chooser.fileFilter = FileNameExtensionFilter("Blockbox packs (*.$extension)", extension)
    val result = chooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath()?.toAbsolutePath()?.toString() else null
} catch (_: Exception) {
    null
}

private fun windowsPicker(title: String, extension: String?): String? = try {
    val filter = if (extension == null) "All files (*.*)|*.*" else "Blockbox packs (*.$extension)|*.$extension|All files (*.*)|*.*"
    val script = "Add-Type -AssemblyName System.Windows.Forms; " +
        "\$d = New-Object System.Windows.Forms.OpenFileDialog; " +
        "\$d.Title = '$title'; \$d.Filter = '$filter'; " +
        "if (\$d.ShowDialog() -eq 'OK') { Write-Output \$d.FileName }"
    runPicker(listOf("powershell", "-NoProfile", "-Command", script))
} catch (_: Exception) {
    null
}

private fun runPicker(command: List<String>): String? = try {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val text = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
    val code = process.waitFor()
    if (code == 0 && text.isNotBlank()) text.lines().first().trim().takeIf { it.isNotBlank() } else null
} catch (_: Exception) {
    null
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

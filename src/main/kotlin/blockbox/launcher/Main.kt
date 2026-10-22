package blockbox.launcher

import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if ("--version" in args) { println("Blockbox Launcher $LAUNCHER_VERSION"); return }
    val dataIndex = args.indexOf("--data-dir")
    if (dataIndex >= 0) {
        require(dataIndex + 1 < args.size) { "--data-dir requires a directory." }
        System.setProperty("blockbox.launcher.home", args[dataIndex + 1])
    }
    if ("--check-game" in args) {
        try { kotlin.system.exitProcess(checkIncludedGame()) }
        catch (e: Exception) { System.err.println(e.message); kotlin.system.exitProcess(1) }
    }
    // No Skia, OpenGL, D3D, or Metal renderer is needed for the launcher.
    // Keep these overridable for systems where Java2D acceleration is preferred.
    for (property in listOf("sun.java2d.opengl", "sun.java2d.d3d", "sun.java2d.metal")) {
        if (System.getProperty(property) == null) System.setProperty(property, "false")
    }
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "Blockbox Launcher")
    if (System.getProperty("awt.useSystemAAFontSettings") == null) System.setProperty("awt.useSystemAAFontSettings", "on")
    if (GraphicsEnvironment.isHeadless()) { System.err.println("Blockbox Launcher needs a desktop display. Use --version for a headless check."); kotlin.system.exitProcess(1) }
    SwingUtilities.invokeLater {
        try { installTheme(); LauncherWindow().isVisible = true }
        catch (e: Exception) { e.printStackTrace(); JOptionPane.showMessageDialog(null, e.message, "Blockbox Launcher could not start", JOptionPane.ERROR_MESSAGE) }
    }
}

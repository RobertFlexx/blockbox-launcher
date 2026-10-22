package blockbox.launcher

import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.border.BevelBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import kotlin.io.path.*

private fun onEdt(action: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeLater(action) }
private fun label(text: String, bold: Boolean = false) = JLabel(text).apply { if (bold) font = font.deriveFont(Font.BOLD); foreground = Ink }
private fun note(text: String) = JTextArea(text).apply {
    isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; foreground = Muted
    font = UIManager.getFont("Label.font"); border = null; isFocusable = false
}
private fun button(text: String, action: () -> Unit) = JButton(text).apply { addActionListener { action() }; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
private fun padded(component: JComponent, size: Int = 14) = JPanel(BorderLayout()).apply { isOpaque = false; border = BorderFactory.createEmptyBorder(size, size, size, size); add(component) }
private fun scroll(component: JComponent) = JScrollPane(component).apply { border = null; verticalScrollBar.unitIncrement = 24 }
private fun readOnly(text: String) = JTextField(text).apply { isEditable = false; background = Paper; border = BorderFactory.createEmptyBorder(4, 0, 4, 0); caretPosition = 0 }

class LauncherWindow(private val store: LauncherStore = LauncherStore(), private val runner: GameRunner = GameRunner(store)) : JFrame("Blockbox Launcher") {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "blockbox-files").apply { isDaemon = true } }
    private val logQueue = ConcurrentLinkedQueue<String>()
    private val listModel = DefaultListModel<InstanceConfig>()
    private val instanceList = JList(listModel)
    private var instances = emptyList<InstanceConfig>()
    private var selected: InstanceConfig? = null
    private var selectionGuard = false
    private var busy = false
    @Volatile private var closing = false
    private val search = JTextField()
    private val tabs = JTabbedPane()
    private val titleLabel = label("Your Blockbox library", true).apply { font = font.deriveFont(23f) }
    private val subtitle = label("Select an instance to begin").apply { foreground = Muted }
    private val status = label("Loading instances…")
    private val runStatus = label("Idle")
    private val progress = JProgressBar().apply { preferredSize = Dimension(70, 12); isVisible = false }
    private val launch = button("Launch game") { launchSelected() }.apply { background = Green; foreground = Color.WHITE; font = font.deriveFont(Font.BOLD, 15f); preferredSize = Dimension(150, 40); mnemonic = KeyEvent.VK_L }
    private val stop = button("Stop game") { runner.stop(::stateChanged) }
    private val console = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12); background = Color(0x202B33); foreground = Color(0xDFE7E7); margin = Insets(12, 12, 12, 12); tabSize = 4 }
    private val followLogs = JCheckBox("Follow output", true)
    private val consoleScroll = scroll(console)
    private val logTimer = Timer(100) { flushLogs() }
    private val settings = SettingsForm()
    private val modModel = object : DefaultTableModel(arrayOf("State", "Mod", "Version", "Side"), 0) { override fun isCellEditable(row: Int, column: Int) = false }
    private val modTable = JTable(modModel)
    private var mods = emptyList<BlockboxMod>()
    private val modDescription = note("Select a mod to see its details.")
    private val modButtons = mutableListOf<JButton>()
    private val instanceButtons = mutableListOf<JButton>()
    private val overview = JPanel(BorderLayout())
    private val diagnostics = JTextArea().apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12); margin = Insets(12, 12, 12, 12); lineWrap = true; wrapStyleWord = true }
    private val split: JSplitPane

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        iconImage = java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { image ->
            val graphics = image.createGraphics(); BlockIcon().paintIcon(null, graphics, 0, 0); graphics.dispose()
        }
        jMenuBar = createMenu()
        val top = JPanel(BorderLayout(14, 0)).apply {
            background = Paper; border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Line), BorderFactory.createEmptyBorder(12, 18, 12, 18))
            add(JLabel(BlockIcon()), BorderLayout.WEST)
            add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; add(label("BLOCKBOX", true).apply { font = Font(Font.SERIF, Font.BOLD, 25) }); add(Box.createVerticalStrut(3)); add(label("INSTANCE LAUNCHER").apply { foreground = Muted; font = font.deriveFont(10f) }) })
            add(label("CLASSIC EDITION  /  $LAUNCHER_VERSION").apply { foreground = Muted; font = Font(Font.MONOSPACED, Font.PLAIN, 11) }, BorderLayout.EAST)
        }
        val right = JPanel(BorderLayout(0, 14)).apply {
            border = BorderFactory.createEmptyBorder(14, 14, 0, 14)
            add(JPanel(BorderLayout(12, 0)).apply {
                add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(titleLabel); add(Box.createVerticalStrut(5)); add(subtitle) })
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { add(stop); add(launch) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(tabs)
        }
        split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createSidebar(), right).apply {
            border = null; resizeWeight = 0.0; dividerLocation = 240; isContinuousLayout = true
            leftComponent.minimumSize = Dimension(175, 0); rightComponent.minimumSize = Dimension(470, 0)
        }
        val footer = JPanel(BorderLayout(12, 0)).apply {
            border = BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED), BorderFactory.createEmptyBorder(6, 10, 6, 10))
            add(status)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply { add(progress); add(runStatus) }, BorderLayout.EAST)
        }
        contentPane = JPanel(BorderLayout()).apply { add(top, BorderLayout.NORTH); add(split); add(footer, BorderLayout.SOUTH) }
        tabs.addTab("Overview", overview)
        tabs.addTab("Settings", settings.panel)
        tabs.addTab("Mods", createMods())
        tabs.addTab("Console", createConsole())
        tabs.addTab("Diagnostics", createDiagnostics())
        tabs.addChangeListener {
            if (tabs.selectedIndex == 2) refreshMods()
            SwingUtilities.invokeLater { resetFormScroll(tabs.selectedComponent) }
        }
        minimumSize = Dimension(760, 540)
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val width = store.preferences.getProperty("width")?.toIntOrNull() ?: 1120
        val height = store.preferences.getProperty("height")?.toIntOrNull() ?: 780
        setSize(width.coerceIn(760, maxOf(760, available.width)), height.coerceIn(540, maxOf(540, available.height)))
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { requestClose() } })
        val shortcut = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        bind("new", KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), ::createInstance)
        bind("launch", KeyStroke.getKeyStroke(KeyEvent.VK_L, shortcut), ::launchSelected)
        bind("save", KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut)) { saveSettings() }
        bind("console", KeyStroke.getKeyStroke(KeyEvent.VK_4, shortcut)) { tabs.selectedIndex = 3 }
        logTimer.start()
        stateChanged(RunState.IDLE)
        reloadInstances(store.preferences.getProperty("selected"))
    }

    private fun bind(name: String, key: KeyStroke, action: () -> Unit) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name)
        rootPane.actionMap.put(name, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) { action() } })
    }

    private fun createMenu() = JMenuBar().apply {
        add(JMenu("Launcher").apply {
            mnemonic = KeyEvent.VK_L
            add(JMenuItem("New instance…").apply { addActionListener { createInstance() } })
            add(JMenuItem("Import pack…").apply { addActionListener { importPack() } })
            addSeparator()
            includedGameRoot()?.let { included -> add(JMenuItem("Use included game").apply { addActionListener {
                if (!busy && runner.state == RunState.IDLE) task("Selecting included game", { store.setGameRoot(included) }) { refreshDiagnostics(); rebuildOverview(); setStatus("Included game selected") }
            } }) }
            add(JMenuItem("Game location…").apply { addActionListener { chooseGameRoot() } })
            add(JMenuItem("Open launcher data").apply { addActionListener { open(store.launcherRoot) } })
            addSeparator(); add(JMenuItem("Exit").apply { addActionListener { requestClose() } })
        })
        add(JMenu("Help").apply {
            add(JMenuItem("Diagnostics").apply { addActionListener { tabs.selectedIndex = 4; refreshDiagnostics() } })
            add(JMenuItem("About Blockbox Launcher").apply { addActionListener { JOptionPane.showMessageDialog(this@LauncherWindow, "Blockbox Launcher $LAUNCHER_VERSION\nClassic edition · Kotlin / Swing\n\nManage independent mods, worlds, and launch settings.\nThe launcher uses Java2D without a GPU renderer.", "About", JOptionPane.INFORMATION_MESSAGE, BlockIcon()) } })
        })
    }

    private fun createSidebar() = JPanel(BorderLayout(0, 10)).apply {
        border = BorderFactory.createEmptyBorder(16, 12, 12, 0); preferredSize = Dimension(240, 0)
        add(JPanel(BorderLayout(0, 10)).apply {
            add(label("INSTANCES", true).apply { foreground = Navy }, BorderLayout.NORTH)
            search.toolTipText = "Filter instances by name"; search.accessibleContext.accessibleName = "Search instances"
            add(search)
            add(JPanel(GridLayout(1, 2, 6, 0)).apply { add(button("New…", ::createInstance)); add(button("Import…", ::importPack)) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        instanceList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        instanceList.fixedCellHeight = 64
        instanceList.cellRenderer = ListCellRenderer { _, config, _, active, focused ->
            JPanel(BorderLayout(10, 0)).apply {
                background = if (active) Color(0xDCE5E9) else Color.WHITE
                border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, if (active) 3 else 0, 1, 0, if (active) Navy else Color(0xE7E7E2)), BorderFactory.createEmptyBorder(9, 10, 9, 8))
                add(JLabel(BlockIcon(28)), BorderLayout.WEST)
                add(JPanel(GridLayout(2, 1, 0, 3)).apply { isOpaque = false; add(label(config.name, true)); add(label("${config.maxMemoryMb} MiB  ·  ${config.displayBackend}").apply { font = font.deriveFont(11f); foreground = Muted }) })
                toolTipText = config.name
            }
        }
        instanceList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && !selectionGuard) {
                val candidate = instanceList.selectedValue
                if (candidate != null && candidate.id != selected?.id) {
                    if (resolveUnsaved()) selectInstance(candidate)
                    else { selectionGuard = true; instanceList.setSelectedValue(selected, true); selectionGuard = false }
                }
            }
        }
        search.document.addDocumentListener(changeListener { filterInstances() })
        add(JScrollPane(instanceList).apply { border = BorderFactory.createBevelBorder(BevelBorder.LOWERED) })
        add(note("Separate worlds. Separate mods.\nOne place to play."), BorderLayout.SOUTH)
    }

    private fun filterInstances() {
        selectionGuard = true
        listModel.clear()
        instances.filter { it.name.contains(search.text, true) }.forEach { listModel.addElement(it) }
        selected?.let { selection -> instances.firstOrNull { it.id == selection.id }?.let { instanceList.setSelectedValue(it, true) } }
        selectionGuard = false
    }

    private fun reloadInstances(preferredId: String? = selected?.id) {
        task("Loading instances", { store.loadInstances() }) { loaded ->
            instances = loaded
            selectInstance(loaded.firstOrNull { it.id == preferredId } ?: loaded.firstOrNull())
            filterInstances()
            if (store.warnings.isNotEmpty()) { appendLog(store.warnings.joinToString("\n")); setStatus("Some data could not be loaded. See Console."); store.warnings.clear() }
        }
    }

    private fun selectInstance(config: InstanceConfig?) {
        selected = config
        titleLabel.text = config?.name ?: "No instance selected"
        titleLabel.toolTipText = config?.name
        subtitle.text = config?.description?.ifBlank { "Independent Blockbox profile" } ?: "Create an instance to begin"
        subtitle.toolTipText = config?.description
        settings.load(config)
        rebuildOverview()
        SwingUtilities.invokeLater { resetFormScroll(tabs.selectedComponent) }
        updateControls()
        if (tabs.selectedIndex == 2) refreshMods()
        refreshDiagnostics()
    }

    private fun createInstance() {
        if (busy || !resolveUnsaved()) return
        val name = JOptionPane.showInputDialog(this, "Instance name", "New instance", JOptionPane.PLAIN_MESSAGE) ?: return
        if (name.isBlank()) return
        task("Creating instance", { store.createInstance(name, "A separate Blockbox profile") }) { reloadInstances(it.id) }
    }

    private fun importPack() {
        if (busy || !resolveUnsaved()) return
        val path = choosePath(this, "Import Blockbox pack", extension = PACK_EXTENSION) ?: return
        task("Importing pack", { store.importPack(path.toString()) }) { reloadInstances(it.id); setStatus("Imported ${it.name}") }
    }

    private fun chooseGameRoot() {
        if (busy || runner.state != RunState.IDLE) return
        val path = choosePath(this, "Choose Blockbox game checkout", directories = true) ?: return
        task("Saving game location", { store.setGameRoot(path) }) { refreshDiagnostics(); rebuildOverview(); setStatus("Game location saved") }
    }

    private fun rebuildOverview() {
        instanceButtons.clear()
        overview.removeAll()
        val config = selected
        if (config == null) { overview.add(padded(note("Create an instance to begin."))); overview.revalidate(); overview.repaint(); return }
        val content = WidthPanel(GridBagLayout()).apply { background = Paper; border = BorderFactory.createEmptyBorder(18, 18, 18, 18) }
        var row = 0
        fun section(title: String, body: JComponent) {
            val panel = JPanel(BorderLayout()).apply { isOpaque = false; border = BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Line), title), BorderFactory.createEmptyBorder(8, 10, 10, 10)); add(body) }
            content.add(panel, GridBagConstraints().apply { gridx = 0; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 12, 0) })
        }
        section("Instance", JPanel(GridLayout(0, 2, 12, 8)).apply {
            isOpaque = false
            listOf("Memory limit" to "${config.maxMemoryMb} MiB", "Graphics" to config.displayBackend, "Last played" to config.lastPlayed, "Java runtime" to config.javaPath).forEach { (key, value) -> add(label(key).apply { foreground = Muted }); add(readOnly(value)) }
        })
        section("Folders", JPanel(GridLayout(2, 3, 8, 8)).apply {
            isOpaque = false
            listOf("Instance" to store.instanceDir(config), "Worlds" to store.worldsDir(config), "Mods" to store.modsDir(config), "Logs" to store.logsDir(config), "Exports" to store.exportsRoot, "Game" to store.projectRoot).forEach { (title, path) -> add(button(title) { open(path) }) }
        })
        section("Packs & backups", JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(note("Share mods and configuration as a Blockbox pack. Include worlds to make a save backup."))
            val includeWorlds = JCheckBox("Include worlds").apply { isOpaque = false }
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(button("Export pack…") { if (!busy) task("Exporting pack", { store.exportPack(config, includeWorlds.isSelected) }) { setStatus("Exported ${it.fileName}"); JOptionPane.showMessageDialog(this@LauncherWindow, "Pack saved to:\n$it", "Pack exported", JOptionPane.INFORMATION_MESSAGE) } }.also { instanceButtons += it })
                add(includeWorlds)
            }, BorderLayout.SOUTH)
        })
        section("Manage instance", JPanel(BorderLayout(0, 10)).apply {
            isOpaque = false
            add(note("Duplicate this instance with its worlds, mods, and configuration. Logs stay with the original."))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(button("Duplicate…") { if (!busy && resolveUnsaved()) task("Duplicating instance", { store.duplicateInstance(selected!!) }) { reloadInstances(it.id) } }.also { instanceButtons += it })
                add(button("Delete…") { deleteInstance(config) }.apply { foreground = Color(0x8A3330) }.also { instanceButtons += it })
            }, BorderLayout.SOUTH)
        })
        content.add(note("Tip: choose Settings for memory, graphics, and launch options.\nUse Console to follow game startup and Diagnostics to check your setup."), GridBagConstraints().apply { gridx = 0; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        content.add(Box.createVerticalGlue(), GridBagConstraints().apply { gridx = 0; gridy = row; weighty = 1.0; fill = GridBagConstraints.VERTICAL })
        overview.add(scroll(content)); overview.revalidate(); overview.repaint()
    }

    private fun deleteInstance(config: InstanceConfig) {
        if (busy || runner.activeInstanceId == config.id) return
        if (JOptionPane.showConfirmDialog(this, "Permanently delete ${config.name}?\n\nThis removes its worlds, mods, configuration, and logs.\nExport a pack with worlds first if you need a backup.", "Delete instance", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return
        task("Deleting instance", { store.deleteInstance(config) }) { reloadInstances(null) }
    }

    private fun createMods() = JPanel(BorderLayout(0, 10)).apply {
        background = Paper; border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(button("Import…") {
                val config = selected ?: return@button
                if (busy || runner.activeInstanceId == config.id) return@button
                val path = choosePath(this@LauncherWindow, "Import mod file or folder", fileOrDirectory = true) ?: return@button
                task("Importing mod", { store.importMod(config, path.toString()) }) { refreshMods(); setStatus(it) }
            }.also { modButtons += it })
            add(button("Enable / disable") { selectedMod()?.let { mod -> val config = selected!!; task("Changing mod", { store.toggleMod(config, mod) }) { refreshMods(); setStatus(it) } } }.also { modButtons += it })
            add(button("Delete…") { selectedMod()?.let { mod ->
                val config = selected!!
                if (JOptionPane.showConfirmDialog(this@LauncherWindow, "Permanently remove ${mod.name}?", "Delete mod", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) task("Deleting mod", { store.deleteMod(config, mod) }) { refreshMods(); setStatus(it) }
            } }.also { modButtons += it })
            add(button("Refresh", ::refreshMods))
        }, BorderLayout.NORTH)
        modTable.rowHeight = 30; modTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); modTable.autoCreateRowSorter = true
        modTable.fillsViewportHeight = true; modTable.showVerticalLines = false
        modTable.columnModel.getColumn(0).preferredWidth = 80; modTable.columnModel.getColumn(1).preferredWidth = 260
        modTable.selectionModel.addListSelectionListener { selectedMod()?.let { modDescription.text = "${it.path.fileName}\n${it.description}" } }
        add(JScrollPane(modTable))
        add(modDescription.apply { preferredSize = Dimension(100, 50) }, BorderLayout.SOUTH)
    }

    private fun selectedMod(): BlockboxMod? = modTable.selectedRow.takeIf { it >= 0 }?.let { mods.getOrNull(modTable.convertRowIndexToModel(it)) }
    private fun refreshMods() {
        val config = selected ?: return
        worker.execute {
            val result = runCatching { store.scanMods(config) }
            onEdt { if (selected?.id == config.id) result.onSuccess { loaded ->
                mods = loaded; modModel.rowCount = 0
                loaded.forEach { modModel.addRow(arrayOf(if (it.enabled) "Enabled" else "Disabled", it.name, it.version, it.side)) }
                modDescription.text = if (loaded.isEmpty()) "No mods installed. Import a .jar, .groovy file, or mod folder." else "${loaded.size} installed mods. Select a row to see details."
            }.onFailure { setStatus(it.message ?: "Could not read mods") } }
        }
    }

    private fun createConsole() = JPanel(BorderLayout(0, 10)).apply {
        background = Paper; border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false; followLogs.isOpaque = false; add(followLogs)
            add(button("Copy all") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(console.text), null); setStatus("Console copied") })
            add(button("Clear view") { logQueue.clear(); console.text = "" })
            add(button("Open logs") { selected?.let { open(store.logsDir(it)) } })
        }, BorderLayout.NORTH)
        add(consoleScroll)
    }

    private fun createDiagnostics() = JPanel(BorderLayout(0, 10)).apply {
        background = Paper; border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false; add(button("Refresh", ::refreshDiagnostics)); add(button("Game location…", ::chooseGameRoot))
            add(button("Copy details") { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(diagnostics.text), null); setStatus("Diagnostics copied") })
        }, BorderLayout.NORTH)
        add(scroll(diagnostics))
    }

    private fun refreshDiagnostics() {
        val config = selected
        worker.execute {
            val text = buildString {
                appendLine("BLOCKBOX LAUNCHER  $LAUNCHER_VERSION\n")
                appendLine("System     ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                appendLine("Launcher   Java ${System.getProperty("java.version")} · ${System.getProperty("java.vendor")}")
                appendLine("UI         Swing / Java2D · GPU pipelines disabled by default")
                appendLine("Native     ${nativeClassifier(HostPlatform.detect(), System.getProperty("os.arch")) ?: "requires local LWJGL libraries"}")
                appendLine("\nGame folder\n${store.projectRoot}")
                val bundled = runCatching { BundledGame.read(store.projectRoot) }.getOrNull()
                appendLine(if (bundled != null) "Included game ready (${bundled.revision})." else if (store.projectRoot.resolve("src/main/scala/blockbox/Main.scala").isRegularFile()) "Source found." else "Game missing. Reinstall the full app or choose Game location above.")
                appendLine("\nLauncher data\n${store.launcherRoot}")
                val env = System.getenv()
                if (bundled == null) appendLine("\nScala CLI  ${findExecutable(env["BLOCKBOX_SCALA_CLI"] ?: "scala-cli") ?: "Not found on PATH; needed only to build source checkouts."}")
                config?.let {
                    appendLine("Game Java  ${runCatching { gameJavaHome(it, env, HostPlatform.detect(), requireCompiler = bundled == null) }.getOrNull() ?: "Not found; select Java in Settings."}")
                    appendLine("\nInstance\n${store.instanceDir(it)}")
                    appendLine("Graphics   ${it.displayBackend}")
                }
                if (HostPlatform.detect().unixDesktop) {
                    appendLine("\nSession    ${env["XDG_SESSION_TYPE"] ?: "unspecified"}")
                    appendLine("X11        ${if (env["DISPLAY"].isNullOrBlank()) "not advertised" else "available"}")
                    appendLine("Wayland    ${if (env["WAYLAND_DISPLAY"].isNullOrBlank()) "not advertised" else "available"}")
                    appendLine("\nGraphics help\nAuto preserves your driver environment. X11 and Wayland select the window system. NVIDIA GLX is an explicit NVIDIA option. Software requires Mesa/llvmpipe and can be slow. A software-rendered game is allowed to keep running.")
                }
                appendLine("\nThe launcher never changes backends or restarts the game automatically. Full game output is saved in the instance logs directory.")
            }
            onEdt { diagnostics.text = text; diagnostics.caretPosition = 0 }
        }
    }

    private fun launchSelected() {
        if (busy || runner.state != RunState.IDLE || !resolveUnsaved()) return
        val config = selected ?: return
        tabs.selectedIndex = 3
        runner.launch(config, ::appendLog, ::stateChanged, onStarted = {
            if (config.closeLauncherOnGameStart) onEdt { closeWindow() }
        }, onExit = { code -> onEdt {
            setStatus(if (code == 0) "Game finished" else "Game exited ($code). See Console for details.")
            if (!closing) {
                val selectedId = selected?.id
                worker.execute {
                    val loaded = runCatching { store.loadInstances() }.getOrNull()
                    onEdt {
                        if (loaded != null) {
                            instances = loaded
                            if (selected?.id == selectedId && !settings.dirty()) selectInstance(loaded.firstOrNull { it.id == selectedId })
                            filterInstances()
                        }
                    }
                }
            }
        } })
    }

    private fun stateChanged(state: RunState) = onEdt {
        runStatus.text = when (state) { RunState.IDLE -> "Idle"; RunState.STARTING -> "Preparing…"; RunState.RUNNING -> "Running · ${runner.activeInstanceName}"; RunState.STOPPING -> "Stopping…" }
        runStatus.toolTipText = runStatus.text
        runStatus.preferredSize = if (state == RunState.IDLE) null else Dimension(220, 18)
        updateControls()
    }

    private fun updateControls() {
        val active = runner.activeInstanceId == selected?.id && selected != null
        launch.isEnabled = !busy && selected != null && runner.state == RunState.IDLE
        stop.isVisible = runner.state != RunState.IDLE; stop.isEnabled = runner.state in listOf(RunState.STARTING, RunState.RUNNING)
        modButtons.forEach { it.isEnabled = selected != null && !busy && !active }
        instanceButtons.forEach { it.isEnabled = selected != null && !busy && !active }
        settings.setEditable(selected != null && !busy && !active)
    }

    private fun appendLog(text: String) { if (closing) return; text.lineSequence().forEach { logQueue.offer(it.take(16_384)) }; while (logQueue.size > 4000) logQueue.poll() }
    private fun flushLogs() {
        val output = StringBuilder()
        repeat(300) { val line = logQueue.poll() ?: return@repeat; output.appendLine(line) }
        if (output.isEmpty()) return
        val bar = consoleScroll.verticalScrollBar
        val atEnd = bar.value + bar.visibleAmount >= bar.maximum - 32
        console.append(output.toString())
        if (console.lineCount > 2500) console.replaceRange("", 0, console.getLineStartOffset(console.lineCount - 2200))
        if (followLogs.isSelected && atEnd) console.caretPosition = console.document.length
    }

    private fun open(path: Path) { task("Opening folder", { openPath(path) }) { setStatus(it) } }
    private fun setStatus(text: String) { status.text = text.replace('\n', ' '); status.toolTipText = text }
    private fun <T> task(title: String, action: () -> T, done: (T) -> Unit = {}) {
        if (busy || closing) return
        busy = true; progress.isVisible = true; progress.isIndeterminate = true; setStatus("$title…"); updateControls()
        worker.execute {
            val result = runCatching(action)
            onEdt {
                busy = false; progress.isIndeterminate = false; progress.isVisible = false; updateControls()
                result.onSuccess { setStatus("Ready"); done(it) }.onFailure {
                    appendLog("$title: ${it.message}"); setStatus(it.message ?: "$title failed")
                    JOptionPane.showMessageDialog(this, it.message ?: it.javaClass.simpleName, title, JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    private fun resolveUnsaved(): Boolean {
        if (!settings.dirty()) return true
        return when (JOptionPane.showOptionDialog(this, "Save changes to ${selected?.name}?", "Unsaved settings", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf("Save", "Discard", "Cancel"), "Save")) {
            0 -> saveSettings()
            1 -> { settings.load(selected); true }
            else -> false
        }
    }

    private fun saveSettings(): Boolean {
        if (busy || runner.activeInstanceId == selected?.id) return false
        return try {
            val config = settings.snapshot() ?: return true
            config.validate()
            // Small atomic settings writes finish before navigation or launching.
            store.saveInstance(config)
            instances = instances.map { if (it.id == config.id) config else it }
            selectInstance(config); filterInstances(); setStatus("Settings saved"); true
        } catch (e: Exception) { JOptionPane.showMessageDialog(this, e.message, "Check settings", JOptionPane.ERROR_MESSAGE); false }
    }

    private fun requestClose() {
        if (busy) { setStatus("Wait for the current file operation to finish before closing."); return }
        if (!resolveUnsaved()) return
        if (runner.state != RunState.IDLE) {
            val choice = JOptionPane.showOptionDialog(this, "Blockbox is still running. Choose what happens when the launcher closes.", "Close launcher", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, arrayOf("Keep game running", "Stop game and close", "Cancel"), "Keep game running")
            if (choice !in 0..1) return
            if (choice == 1) {
                runner.stop(::stateChanged)
                val wait = Timer(100, null)
                wait.addActionListener { if (runner.state == RunState.IDLE) { wait.stop(); closeWindow() } }
                wait.start(); return
            }
        }
        closeWindow()
    }

    private fun closeWindow() {
        if (closing) return
        closing = true
        store.preferences.setProperty("selected", selected?.id.orEmpty())
        store.preferences.setProperty("width", width.toString()); store.preferences.setProperty("height", height.toString())
        runCatching { store.savePreferences() }
        // Do not strand the game's stdout pipe when the UI exits. The daemon
        // reader and launcher process remain until the game has exited.
        logTimer.stop(); dispose()
        worker.shutdown()
    }

    private inner class SettingsForm {
        private var original: InstanceConfig? = null
        private val name = JTextField()
        private val description = JTextField()
        private val java = JTextField()
        private val minMemory = JTextField()
        private val maxMemory = JTextField()
        private val jvm = JTextArea(3, 15)
        private val game = JTextArea(2, 15)
        private val env = JTextArea(4, 15)
        private val backend = JComboBox(arrayOf("Automatic", "X11", "Wayland", "NVIDIA X11 / GLX", "Software (Mesa)"))
        private val gameMode = JCheckBox("Use GameMode (gamemoderun)")
        private val closeOnStart = JCheckBox("Close launcher after the game process starts")
        val panel: JPanel
        init {
            val form = WidthPanel(GridBagLayout()).apply { background = Paper; border = BorderFactory.createEmptyBorder(16, 18, 16, 18) }
            var row = 0
            fun title(text: String) {
                form.add(label(text, true).apply { foreground = Navy }, GridBagConstraints().apply { gridx = 0; gridy = row++; gridwidth = 2; weightx = 1.0; anchor = GridBagConstraints.WEST; insets = Insets(12, 0, 10, 0) })
            }
            fun field(text: String, component: JComponent, helper: String? = null) {
                form.add(label(text), GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = Insets(7, 0, 8, 14) })
                form.add(component, GridBagConstraints().apply { gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 8, 0) })
                if (helper != null) form.add(note(helper), GridBagConstraints().apply { gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(0, 0, 12, 0) })
            }
            title("Profile")
            field("Name", name); field("Description", description)
            title("Java & memory")
            field("Java executable", JPanel(BorderLayout(6, 0)).apply { isOpaque = false; add(java); add(button("Browse…") { choosePath(this@LauncherWindow, "Choose Java executable")?.let { java.text = it.toString() } }, BorderLayout.EAST) }, "Use java for the included runtime, or choose a Java 21+ executable.")
            field("Memory (MiB)", JPanel(GridLayout(1, 4, 6, 0)).apply { isOpaque = false; add(label("Minimum")); add(minMemory); add(label("Maximum")); add(maxMemory) })
            field("Quick presets", JPanel(GridLayout(1, 4, 6, 0)).apply { isOpaque = false; listOf(2048, 4096, 6144, 8192).forEach { mb -> add(button("${mb / 1024} GiB") { maxMemory.text = mb.toString(); minMemory.text = "512" }) } })
            jvm.lineWrap = true; jvm.wrapStyleWord = true
            field("JVM arguments", JScrollPane(jvm), "Quoted values and Windows paths are supported. Memory is set above.")
            title("Graphics & launch")
            field("Game graphics", backend, "Auto uses the system default. NVIDIA GLX and Software are explicit fallbacks for Linux/BSD.")
            field("", gameMode)
            field("", closeOnStart)
            game.lineWrap = true; game.wrapStyleWord = true
            field("Game arguments", JScrollPane(game))
            env.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            field("Environment", JScrollPane(env), "One KEY=value per line. Values may contain semicolons. Lines starting with # are comments.")
            form.add(Box.createVerticalGlue(), GridBagConstraints().apply { gridx = 0; gridy = row; weighty = 1.0 })
            panel = JPanel(BorderLayout()).apply {
                background = Paper; add(scroll(form))
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
                    add(button("Revert") { load(selected) })
                    add(button("Save settings") { saveSettings() }.apply { mnemonic = KeyEvent.VK_S })
                }, BorderLayout.SOUTH)
            }
        }
        fun load(config: InstanceConfig?) {
            original = config
            name.text = config?.name.orEmpty(); description.text = config?.description.orEmpty(); java.text = config?.javaPath ?: "java"
            minMemory.text = (config?.minMemoryMb ?: 512).toString(); maxMemory.text = (config?.maxMemoryMb ?: 4096).toString()
            jvm.text = config?.jvmArgs.orEmpty(); game.text = config?.gameArgs.orEmpty(); env.text = config?.envVars.orEmpty()
            backend.selectedIndex = DISPLAY_BACKENDS.indexOf(config?.displayBackend ?: "auto").coerceAtLeast(0)
            gameMode.isSelected = config?.useGameMode == true; closeOnStart.isSelected = config?.closeLauncherOnGameStart == true
        }
        fun snapshot(): InstanceConfig? = original?.copy(
            name = name.text.trim(), description = description.text.trim(), javaPath = java.text.trim(),
            minMemoryMb = minMemory.text.trim().toIntOrNull() ?: error("Minimum memory must be a whole number in MiB."),
            maxMemoryMb = maxMemory.text.trim().toIntOrNull() ?: error("Maximum memory must be a whole number in MiB."),
            jvmArgs = jvm.text, gameArgs = game.text, envVars = env.text,
            displayBackend = DISPLAY_BACKENDS[backend.selectedIndex], useGameMode = gameMode.isSelected,
            closeLauncherOnGameStart = closeOnStart.isSelected
        )
        fun dirty() = original != null && runCatching { snapshot() != original }.getOrDefault(true)
        fun setEditable(value: Boolean) {
            fun enable(component: Component) {
                component.isEnabled = value
                if (component is Container) component.components.forEach(::enable)
            }
            enable(panel)
            backend.isEnabled = value && HostPlatform.detect().unixDesktop
            gameMode.isEnabled = value && HostPlatform.detect() == HostPlatform.LINUX
        }
    }
}

private fun changeListener(action: () -> Unit) = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = action()
    override fun removeUpdate(e: DocumentEvent) = action()
    override fun changedUpdate(e: DocumentEvent) = action()
}

private fun resetFormScroll(component: Component?) {
    if (component is JScrollPane && component.viewport.view is WidthPanel) component.viewport.viewPosition = Point(0, 0)
    else if (component is Container) component.components.forEach(::resetFormScroll)
}

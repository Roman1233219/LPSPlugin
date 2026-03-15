package com.example.logkatplugin

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class LogkatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = LogkatToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(project, myToolWindow)
    }

    class LogkatToolWindow(private val project: Project) : Disposable {
        private val panel = JPanel(BorderLayout())
        private val rootNode = DefaultMutableTreeNode("Processes")
        private val treeModel = DefaultTreeModel(rootNode)
        private val processTree = Tree(treeModel)
        
        private val columnNames = arrayOf("Время", "PID", "TID", "Ур.", "Тег", "Сообщение")
        private val logTableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        private val logTable = JBTable(logTableModel)
        
        private val allLogs = mutableListOf<String>() 
        private val pidToPackage = ConcurrentHashMap<Int, String>()
        private val packageToLabel = ConcurrentHashMap<String, String>()
        private val iconCache = ConcurrentHashMap<String, ImageIcon>()
        
        private var currentDevice: IDevice? = null
        private var autoscroll = true
        private var lastSelectedPackage: String? = null
        private var lastKnownPids: Map<Int, String> = emptyMap()
        private var projectPkg: String? = null
        
        private var activeBalloon: Balloon? = null
        private var isStickyBalloon = false
        private val stalledMsg = "Простаивает / нет логов"

        private val deviceComboBox = ComboBox<IDevice>()
        private val searchField = SearchTextField().apply { textEditor.preferredSize = Dimension(150, 28) }
        private val statusField = JTextField().apply {
            isEditable = false; font = Font("Monospaced", Font.PLAIN, 11); preferredSize = Dimension(180, 26)
            background = JBColor(Color(230, 230, 230), Color(45, 45, 45)); foreground = JBColor.GRAY; border = BorderFactory.createLineBorder(JBColor.border())
        }
        private val stopResolutionButton = JButton(AllIcons.Actions.Suspend).apply {
            preferredSize = Dimension(26, 26); toolTipText = "Остановить поиск"; isBorderPainted = false; isContentAreaFilled = false
        }
        private val resStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(statusField); add(stopResolutionButton); isVisible = false }

        private val openDictionaryButton = createToolbarButton(AllIcons.Actions.EditSource, "Словарь")
        private val openDescriptionsButton = createToolbarButton(AllIcons.Actions.Help, "База знаний")
        private val resetToDefaultButton = createToolbarButton(AllIcons.Actions.Rollback, "Сброс")
        private val bulkUpdateLabelsButton = createToolbarButton(AllIcons.Actions.Refresh, "Синхронизация")

        private val clearButton = createToolbarButton(AllIcons.Actions.GC, "Очистить")
        private val saveButton = createToolbarButton(AllIcons.Actions.MenuSaveall, "Сохранить")
        private val autoscrollButton = createToggleButton(AllIcons.RunConfigurations.Scroll_down, "Автопрокрутка", true)
        private val allLogsButton = createToggleButton(AllIcons.General.Filter, "Все логи", true)

        enum class ResolutionMode(val label: String) { NONE("Нет"), TEST_ONLY("Мои"), INSTALLED_ONLY("Уст."), ALL("Все") }
        private val resolutionCombo = ComboBox(ResolutionMode.values()).apply { preferredSize = Dimension(100, 28); selectedIndex = 0 }
        
        private var filterLevel: String? = null
        private val colorButtons = mutableListOf<JToggleButton>()
        private val currentResolutionId = AtomicInteger(0)
        private var isDisposed = false
        private val timer: javax.swing.Timer

        init {
            loadInitialData()
            setupTableMouseListener()
            setupUI()
            ToolTipManager.sharedInstance().initialDelay = 100 
            allLogsButton.isSelected = true; updateButtonBorders()
            
            bulkUpdateLabelsButton.addActionListener { runDeepSync() }
            openDictionaryButton.addActionListener { openFileInEditor(LogExplanationProvider.DICTIONARY_FILENAME) }
            openDescriptionsButton.addActionListener { openFileInEditor(LogExplanationProvider.DESCRIPTIONS_FILENAME) }
            clearButton.addActionListener { logTableModel.rowCount = 0; synchronized(allLogs) { allLogs.clear() } }
            saveButton.addActionListener { saveLogsToFile() }
            autoscrollButton.addActionListener { autoscroll = autoscrollButton.isSelected }

            resetToDefaultButton.addActionListener {
                if (Messages.showYesNoDialog(project, "Восстановить настройки?", "Сброс", Messages.getWarningIcon()) == Messages.YES) {
                    LogExplanationProvider.resetToDefault(project.basePath); packageToLabel.clear(); loadInitialData(); reloadTreeSafely()
                }
            }
            timer = javax.swing.Timer(3000) { if (!isDisposed) { refreshDevices(); refreshProcesses() } }
            timer.start(); refreshDevices()
        }

        private fun setupTableMouseListener() {
            val mouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = checkAndHideBalloon(e)
                override fun mouseClicked(e: MouseEvent) {
                    val row = logTable.rowAtPoint(e.point)
                    if (row == -1) return
                    val messageValue = logTable.getValueAt(row, 5)?.toString() ?: ""
                    if (messageValue == stalledMsg) return
                    
                    if (SwingUtilities.isRightMouseButton(e)) {
                        logTable.setRowSelectionInterval(row, row)
                        val menu = JPopupMenu()
                        
                        val copyItem = JMenuItem("Копировать", AllIcons.Actions.Copy)
                        copyItem.addActionListener {
                            val sb = StringBuilder()
                            for (i in 0 until logTable.columnCount) {
                                sb.append(logTable.getValueAt(row, i)?.toString() ?: "").append(" ")
                            }
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(sb.toString().trim()), null)
                        }
                        
                        val infoItem = JMenuItem("Что это?", AllIcons.Actions.Help)
                        infoItem.addActionListener {
                            val pidStr = logTable.getValueAt(row, 1)?.toString() ?: ""
                            val pid = pidStr.toIntOrNull()
                            val pkgName = if (pid != null) pidToPackage[pid] ?: findPackageByPid(pidStr) else null
                            val tag = logTable.getValueAt(row, 4)?.toString() ?: ""
                            val level = logTable.getValueAt(row, 3)?.toString() ?: ""
                            
                            val explanation = LogExplanationProvider.getSmartLogExplanation(pkgName, tag, messageValue, level)
                            showHint(explanation, e, logTable, true, messageValue)
                        }
                        
                        menu.add(copyItem); menu.add(infoItem)
                        menu.show(logTable, e.x, e.y)
                    }
                }
            }
            logTable.addMouseListener(mouseListener)
            logTable.addMouseMotionListener(mouseListener)
        }

        private fun findPackageByPid(pid: String): String? {
            val root = treeModel.root as? DefaultMutableTreeNode ?: return null
            val e = root.breadthFirstEnumeration()
            while (e.hasMoreElements()) {
                val node = e.nextElement() as DefaultMutableTreeNode
                if (node.userObject.toString() == pid) {
                    val parent = node.parent as? DefaultMutableTreeNode
                    return parent?.userObject?.toString()
                }
            }
            return null
        }

        override fun dispose() { isDisposed = true; timer.stop(); currentResolutionId.incrementAndGet(); hideActiveBalloon() }

        private fun ensureScriptsExist(): File? {
            val baseDir = File(project.basePath, "on-device-server/src")
            if (!baseDir.exists()) baseDir.mkdirs()

            val psFile = File(baseDir, "run_resolver.ps1")
            val javaFile = File(baseDir, "LabelResolver.java")

            fun extractResource(resName: String, target: File) {
                if (!target.exists()) {
                    val stream = javaClass.getResourceAsStream("/scripts/$resName")
                    if (stream != null) {
                        target.writeBytes(stream.readBytes())
                    }
                }
            }

            try {
                extractResource("run_resolver.ps1", psFile)
                extractResource("LabelResolver.java", javaFile)
                LocalFileSystem.getInstance().refreshIoFiles(listOf(psFile, javaFile))
                return psFile
            } catch (e: Exception) {
                return null
            }
        }

        private fun runDeepSync() {
            if (Messages.showYesNoDialog(project, "Синхронизировать имена и иконки? Старые иконки будут удалены.", "Синхронизация", Messages.getQuestionIcon()) == Messages.YES) {
                val scriptFile = ensureScriptsExist()
                if (scriptFile == null || !scriptFile.exists()) {
                    Messages.showErrorDialog(project, "Не удалось подготовить скрипты синхронизации!", "Ошибка")
                    return
                }

                val iconsDir = File(project.basePath, "on-device-server/src/icons")
                if (iconsDir.exists()) {
                    iconsDir.deleteRecursively()
                }

                bulkUpdateLabelsButton.isEnabled = false; setStatusText("🔍 Синхронизация...", true)
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        val process = ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
                            .directory(File(project.basePath ?: ""))
                            .start()

                        process.inputStream.bufferedReader().use { it.forEachLine { line ->
                            if (line.contains("|")) {
                                val parts = line.split("|"); if (parts.size >= 2) {
                                    val pkg = parts[0].trim(); val lbl = parts[1].trim()
                                    packageToLabel[pkg] = lbl; LogExplanationProvider.writeToDictionary(project.basePath, pkg, lbl, projectPkg)
                                }
                            }
                        }}
                        process.waitFor()
                        
                        val freshIconsDir = File(project.basePath, "on-device-server/src/icons")
                        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(freshIconsDir)
                        vf?.refresh(false, true)

                        ApplicationManager.getApplication().invokeLater { if (!isDisposed) { loadInitialData(); reloadTreeSafely(); bulkUpdateLabelsButton.isEnabled = true; setStatusText("Готово", false) } }
                    } catch (e: Exception) { 
                        ApplicationManager.getApplication().invokeLater { 
                            Messages.showErrorDialog(project, "Ошибка запуска: ${e.message}", "Ошибка")
                            setStatusText("Ошибка", false); bulkUpdateLabelsButton.isEnabled = true 
                        } 
                    }
                }
            }
        }

        private fun setupUI() {
            processTree.isRootVisible = false
            processTree.setCellRenderer(object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, sel: Boolean, exp: Boolean, leaf: Boolean, row: Int, foc: Boolean): Component {
                    val label = super.getTreeCellRendererComponent(tree, value, sel, exp, leaf, row, foc) as JLabel
                    val node = value as? DefaultMutableTreeNode; val userObject = node?.userObject as? String ?: ""
                    val parent = node?.parent as? DefaultMutableTreeNode; val grandParent = parent?.parent as? DefaultMutableTreeNode
                    val isGroup = parent == rootNode; val isPackage = grandParent == rootNode; val isPid = !isGroup && !isPackage && node != rootNode
                    if (isGroup) {
                        label.text = userObject; label.font = label.font.deriveFont(Font.BOLD, 13f)
                        label.foreground = getGroupColor(userObject)
                        label.icon = getGroupIcon(userObject)
                        return label
                    }
                    if (isPackage) { val appLabel = packageToLabel[userObject]; label.text = appLabel ?: userObject; label.foreground = if (sel) Color.WHITE else getGroupColor(parent?.userObject as? String ?: ""); label.icon = getAppIcon(userObject); return label }
                    if (isPid) { label.text = "PID: $userObject"; label.foreground = if (sel) Color.WHITE else Color.GRAY; label.icon = AllIcons.Debugger.Console; return label }
                    return label
                }
            })

            logTable.setShowGrid(false); logTable.background = Color(30, 30, 30); logTable.font = Font("Monospaced", Font.PLAIN, 14); logTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
            val columnModel = logTable.columnModel
            columnModel.getColumn(0).preferredWidth = 110; columnModel.getColumn(1).preferredWidth = 60
            columnModel.getColumn(2).preferredWidth = 60; columnModel.getColumn(3).preferredWidth = 35
            columnModel.getColumn(4).preferredWidth = 150; columnModel.getColumn(5).preferredWidth = 1500
            
            logTable.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    val level = table?.getValueAt(row, 3) as? String ?: ""
                    if (table?.getValueAt(row, 5) == stalledMsg) { c.foreground = Color.GRAY; return c }
                    when (level) {
                        "E" -> { c.foreground = Color.WHITE; c.background = Color(180, 0, 0) }
                        "W" -> { c.foreground = Color.BLACK; c.background = Color(250, 200, 0) }
                        else -> {
                            val pid = (table?.getValueAt(row, 1) as? String)?.toIntOrNull()
                            val pkg = pid?.let { pidToPackage[it] }
                            if (pkg != null && (pkg.contains("android") || pkg.contains("system"))) { c.foreground = Color(100, 150, 255); c.background = Color(40, 40, 60) }
                            else { c.foreground = Color(100, 255, 100); c.background = Color(55, 55, 55) }
                        }
                    }
                    if (isSelected) c.background = c.background.darker()
                    return c
                }
            })

            val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            leftToolbar.add(deviceComboBox); leftToolbar.add(resStatusPanel); leftToolbar.add(openDictionaryButton); leftToolbar.add(openDescriptionsButton); leftToolbar.add(resetToDefaultButton); leftToolbar.add(bulkUpdateLabelsButton)
            val filterGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            listOf(createFilterToggleButton(Color(180, 0, 0), "E", "Ошибки"), createFilterToggleButton(Color(250, 200, 0), "W", "Варнинги"), createFilterToggleButton(Color(100, 255, 100), "I", "Инфо"), createFilterToggleButton(Color(100, 150, 255), "S", "Система")).forEach { colorButtons.add(it); filterGroupPanel.add(it) }
            val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
            rightToolbar.add(searchField); rightToolbar.add(allLogsButton); rightToolbar.add(resolutionCombo); rightToolbar.add(filterGroupPanel); rightToolbar.add(autoscrollButton); rightToolbar.add(clearButton); rightToolbar.add(saveButton)
            val topPanel = JPanel(BorderLayout()); topPanel.add(leftToolbar, BorderLayout.WEST); topPanel.add(rightToolbar, BorderLayout.EAST); panel.add(topPanel, BorderLayout.NORTH)
            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), JBScrollPane(logTable)); splitPane.dividerLocation = 280; panel.add(splitPane, BorderLayout.CENTER)
            processTree.addTreeSelectionListener { val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode; val selectedValue = node?.userObject as? String; if (selectedValue != null && selectedValue != lastSelectedPackage) { val parent = node.parent as? DefaultMutableTreeNode; val pkgName = if (parent != null && parent != rootNode && (parent.parent as? DefaultMutableTreeNode) == rootNode) parent.userObject as? String else selectedValue; if (pkgName != lastSelectedPackage) { lastSelectedPackage = pkgName; rebuildLogTable() } } }
            deviceComboBox.addActionListener { val selected = deviceComboBox.selectedItem as? IDevice; if (selected != null && selected.serialNumber != currentDevice?.serialNumber) { currentDevice = selected; startLogcatCapture(selected) } }
        }

        private fun getGroupIcon(groupName: String): Icon = when {
            groupName.contains("МОЙ ПРОЕКТ") -> AllIcons.Nodes.HomeFolder
            groupName.contains("ПОЛЬЗОВАТЕЛЬСКИЕ") -> AllIcons.Nodes.Package
            groupName.contains("ПРИЛОЖЕНИЯ ГУГЛ") -> AllIcons.Nodes.PpWeb
            groupName.contains("СЛУЖБЫ ГУГЛ") -> AllIcons.Nodes.PpLib
            groupName.contains("ИНТЕРФЕЙС") -> AllIcons.Nodes.Editorconfig
            groupName.contains("СИСТЕМНЫЕ СЛУЖБЫ") -> AllIcons.Nodes.ConfigFolder
            groupName.contains("НИЗКОУРОВНЕВЫЕ") -> AllIcons.Nodes.ResourceBundle
            groupName.contains("ЖЕЛЕЗО") -> AllIcons.General.Settings
            groupName.contains("СЕТЬ") -> AllIcons.General.Web
            groupName.contains("МЕДИА") -> AllIcons.Nodes.Class
            else -> AllIcons.Nodes.Folder
        }

        private fun getGroupColor(groupName: String): Color = when {
            groupName.contains("МОЙ ПРОЕКТ") -> Color(180, 150, 255)
            groupName.contains("ПОЛЬЗОВАТЕЛЬСКИЕ") -> Color(150, 255, 150)
            groupName.contains("ГУГЛ") -> Color(255, 255, 150)
            groupName.contains("ИНТЕРФЕЙС") -> Color(150, 255, 255)
            groupName.contains("СИСТЕМНЫЕ") -> Color(100, 150, 255)
            groupName.contains("НИЗКОУРОВНЕВЫЕ") -> Color(200, 200, 200)
            groupName.contains("ЖЕЛЕЗО") -> Color(255, 180, 150)
            groupName.contains("СЕТЬ") -> Color(150, 200, 255)
            groupName.contains("МЕДИА") -> Color(255, 150, 255)
            else -> Color.LIGHT_GRAY
        }

        private fun getAppIcon(pkgName: String): Icon { val cached = iconCache[pkgName]; if (cached != null) return cached; val iconFile = File("${project.basePath}/on-device-server/src/icons/$pkgName.png"); if (iconFile.exists()) { try { val img = ImageIcon(iconFile.absolutePath); val scaled = ImageIcon(img.image.getScaledInstance(16, 16, Image.SCALE_SMOOTH)); iconCache[pkgName] = scaled; return scaled } catch (e: Exception) {} }; return when { pkgName.contains("example") || pkgName == projectPkg -> AllIcons.Nodes.HomeFolder; pkgName.contains("google") -> AllIcons.Nodes.PpWeb; pkgName.contains("android") -> AllIcons.General.Settings; else -> AllIcons.Nodes.Package } }

        private fun refreshProcesses() {
            val device = currentDevice ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                device.clients.forEach { it.clientData.packageName?.let { pkg -> pidToPackage[it.clientData.pid] = pkg } }
                val processReceiver = object : MultiLineReceiver() { override fun processNewLines(lines: Array<out String>) { lines.forEach { line -> val parts = line.trim().split(Regex("\\s+")); if (parts.size >= 8) { val pid = parts[1].toIntOrNull() ?: parts[0].toIntOrNull(); if (pid != null) pidToPackage.putIfAbsent(pid, parts.last()) } } } override fun isCancelled() = isDisposed }
                try { device.executeShellCommand("ps -A", processReceiver, 0, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
                val currentPids = pidToPackage.toMap(); if (currentPids == lastKnownPids) return@executeOnPooledThread
                lastKnownPids = currentPids
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    val expandedNames = mutableSetOf<List<String>>(); for (i in 0 until processTree.rowCount) if (processTree.isExpanded(i)) expandedNames.add(processTree.getPathForRow(i).path.map { (it as DefaultMutableTreeNode).userObject.toString() })
                    rootNode.removeAllChildren(); val pkgToPids = mutableMapOf<String, MutableList<Int>>(); currentPids.forEach { (pid, pkg) -> if (pkg != "Unknown") pkgToPids.getOrPut(pkg) { mutableListOf() }.add(pid) }
                    val allPackages = pkgToPids.keys.sorted()
                    val usedPackages = mutableSetOf<String>()

                    fun filterPackages(predicate: (String) -> Boolean): List<String> {
                        val filtered = allPackages.filter { it !in usedPackages && predicate(it) }
                        usedPackages.addAll(filtered)
                        return filtered
                    }

                    addGroupWithChildren("⭐ МОЙ ПРОЕКТ", filterPackages { it == projectPkg || it.contains("example") }, pkgToPids)
                    
                    addGroupWithChildren("🔍 ПРИЛОЖЕНИЯ ГУГЛ", filterPackages { 
                        it.startsWith("com.google.android.") && (it.contains("youtube") || it.contains("maps") || it.contains("chrome") || it.contains("gm") || it.contains("calendar") || it.contains("photos") || it.contains("vending"))
                    }, pkgToPids)
                    
                    addGroupWithChildren("☁️ СЛУЖБЫ ГУГЛ", filterPackages { it.contains("google") }, pkgToPids)
                    
                    addGroupWithChildren("🖼️ ИНТЕРФЕЙС И ГРАФИКА", filterPackages { 
                        it.contains("systemui") || it.contains("launcher") || it.contains("surfaceflinger") || it.contains("wm.") || it.contains("wallpaper") || it.contains("renderengine") || it.contains("gpu") || it.contains("composer")
                    }, pkgToPids)
                    
                    addGroupWithChildren("📡 СЕТЬ И СВЯЗЬ", filterPackages { 
                        it.contains("wifi") || it.contains("bluetooth") || it.contains("telephony") || it.contains("nfc") || it.contains("netd") || it.contains("networkstack") || it.contains("wpa_supplicant") || it.contains("phone") || it.contains("iptables") || it.contains("ipsec") || it.contains("modem")
                    }, pkgToPids)
                    
                    addGroupWithChildren("🔊 МЕДИА И ЗВУК", filterPackages { 
                        it.contains("audio") || it.contains("media") || it.contains("codec") || it.contains("drm") || it.contains("sound") || it.contains("video") || it.contains("camera")
                    }, pkgToPids)

                    addGroupWithChildren("🛠️ ЖЕЛЕЗО И ДРАЙВЕРЫ", filterPackages { 
                        it.contains("hal") || it.contains("hardware") || it.contains("sensor") || it.contains("gps") || it.contains("fingerprint") || it.contains("thermal") || it.contains("light") || it.contains("power") || it.contains("usb") || it.contains("battery")
                    }, pkgToPids)

                    addGroupWithChildren("⚙️ СИСТЕМНЫЕ СЛУЖБЫ", filterPackages { 
                        it.startsWith("com.android.") || it.contains("providers") || it.contains("settings") || it.contains("system_server") || it.contains("permission") || it.contains("keystore") || it.contains("credstore") || it.contains("gatekeeper") || it.contains("security")
                    }, pkgToPids)
                    
                    addGroupWithChildren("🧱 НИЗКОУРОВНЕВЫЕ", filterPackages { 
                        it.startsWith("[") || !it.contains(".") || it == "init" || it == "zygote" || it == "adbd" || it == "logd" || it.contains("logger") || it.contains("incident") || it.contains("crash") || it.contains("stats") || it == "sh" || it == "magisk"
                    }, pkgToPids)

                    addGroupWithChildren("👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ", filterPackages { 
                        it.contains(".")
                    }, pkgToPids)

                    addGroupWithChildren("📂 ПРОЧЕЕ", allPackages.filter { it !in usedPackages }, pkgToPids)

                    treeModel.reload(); restoreExpansionState(rootNode, TreePath(rootNode), expandedNames)
                }
            }
        }

        private fun restoreExpansionState(node: DefaultMutableTreeNode, path: TreePath, expandedNames: Set<List<String>>) { if (expandedNames.contains(path.path.map { (it as DefaultMutableTreeNode).userObject.toString() })) processTree.expandPath(path); for (i in 0 until node.childCount) { val child = node.getChildAt(i) as DefaultMutableTreeNode; restoreExpansionState(child, path.pathByAddingChild(child), expandedNames) } }
        private fun addGroupWithChildren(title: String, packages: List<String>, pkgMap: Map<String, List<Int>>) { if (packages.isEmpty()) return; val groupNode = DefaultMutableTreeNode(title); packages.forEach { pkg -> val pkgNode = DefaultMutableTreeNode(pkg); pkgMap[pkg]?.sorted()?.forEach { pid -> pkgNode.add(DefaultMutableTreeNode(pid.toString())) }; groupNode.add(pkgNode) }; rootNode.add(groupNode) }
        private fun reloadTreeSafely() { ApplicationManager.getApplication().invokeLater { if (!isDisposed) treeModel.reload() } }
        private fun loadInitialData() { LogExplanationProvider.loadAllDescriptions(project.basePath); packageToLabel.putAll(LogExplanationProvider.loadDictionary(project.basePath)); projectPkg = LogExplanationProvider.getProjectPackageName(project); iconCache.clear() }
        private fun createToolbarButton(icon: Icon, tip: String) = JButton(icon).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createToggleButton(icon: Icon, tip: String, initial: Boolean) = JToggleButton(icon, initial).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createFilterToggleButton(color: Color, level: String, tip: String) = JToggleButton().apply {
            preferredSize = Dimension(28, 28); background = color; isOpaque = true; isContentAreaFilled = true; border = BorderFactory.createLineBorder(Color.GRAY, 1); toolTipText = tip; addActionListener { if (isSelected) { filterLevel = level; allLogsButton.isSelected = false; colorButtons.filter { it != this }.forEach { it.isSelected = false } } else if (filterLevel == level) filterLevel = null; updateButtonBorders(); rebuildLogTable() } }
        private fun updateButtonBorders() { val activeBorder = BorderFactory.createLineBorder(JBColor.namedColor("Label.foreground", Color.BLACK), 3); allLogsButton.border = if (allLogsButton.isSelected) activeBorder else BorderFactory.createLineBorder(Color.GRAY, 1); colorButtons.forEach { it.border = if (it.isSelected) activeBorder else BorderFactory.createLineBorder(Color.GRAY, 1) } }
        private fun showHint(text: String, e: MouseEvent, component: Component, isSticky: Boolean, originalMessage: String = "") { hideActiveBalloon(); isStickyBalloon = isSticky; val balloon = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), object : HyperlinkListener { override fun hyperlinkUpdate(event: HyperlinkEvent) { if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "show_stacktrace") Messages.showInfoMessage(LogExplanationProvider.getDetailedStackTraceExplanation(originalMessage), "Информация") } }).setFadeoutTime(0).setHideOnClickOutside(true).createBalloon(); balloon.show(RelativePoint(component, e.point), Balloon.Position.above); activeBalloon = balloon }
        private fun checkAndHideBalloon(e: MouseEvent) { if (activeBalloon != null && !isStickyBalloon) hideActiveBalloon() }
        private fun hideActiveBalloon() { activeBalloon?.hide(); activeBalloon = null }
        private fun startLogcatCapture(device: IDevice) { ApplicationManager.getApplication().executeOnPooledThread { try { synchronized(allLogs) { allLogs.clear() }; device.executeShellCommand("logcat -v threadtime", object : MultiLineReceiver() { override fun processNewLines(lines: Array<out String>) { lines.forEach { line -> if (line.length > 10) { val cleanLine = if (line.length > 700) line.substring(0, 700) else line; synchronized(allLogs) { allLogs.add(cleanLine); if (allLogs.size > 5000) allLogs.removeAt(0) }; ApplicationManager.getApplication().invokeLater { if (isDisposed) return@invokeLater; if (lastSelectedPackage != null && isLineRelatedToPackage(line, lastSelectedPackage!!) && isLinePassingFilter(line)) { if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) logTableModel.removeRow(0); logTableModel.addRow(parseLogLine(cleanLine)); if (logTableModel.rowCount > 1500) logTableModel.removeRow(0); if (autoscroll) scrollTableToBottom() } } } } } override fun isCancelled() = (currentDevice?.serialNumber != device.serialNumber || isDisposed) }, 0, TimeUnit.MILLISECONDS) } catch (e: Exception) {} } }
        private fun parseLogLine(line: String): Array<String> { val parts = line.trim().split(Regex("\\s+"), 6); if (parts.size < 6) return arrayOf("", "", "", "", "", line); val rest = parts[5].split(":", limit = 2); return arrayOf(parts[1], parts[2], parts[3], parts[4], rest.getOrNull(0) ?: "", rest.getOrNull(1)?.trim() ?: "") }
        private fun isLinePassingFilter(line: String): Boolean { val query = searchField.text.trim(); if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false; if (allLogsButton.isSelected) return true; val parts = parseLogLine(line); val level = parts[3]; return when (filterLevel) { "E" -> level == "E"; "W" -> level == "W"; "I" -> level == "I" || level == "V" || level == "D"; "S" -> { val pid = parts[1].toIntOrNull(); val pkg = pid?.let { pidToPackage[it] }; pkg != null && (pkg.contains("android") || pkg.contains("system")) }; else -> true } }
        private fun isLineRelatedToPackage(line: String, packageName: String): Boolean { val parts = parseLogLine(line); val pidFromLine = parts[1].toIntOrNull(); if (pidFromLine != null && pidToPackage[pidFromLine] == packageName) return true; return line.contains(packageName, ignoreCase = true) || (line.contains("ActivityManager") && line.contains(packageName)) }
        private fun rebuildLogTable() { if (isDisposed || lastSelectedPackage == null) return; logTableModel.rowCount = 0; val snapshot = synchronized(allLogs) { allLogs.toList() }; val filtered = snapshot.filter { isLineRelatedToPackage(it, lastSelectedPackage!!) && isLinePassingFilter(it) }; if (filtered.isEmpty()) logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg)); else filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }; if (autoscroll) scrollTableToBottom() }
        private fun scrollTableToBottom() { if (logTableModel.rowCount > 0 && !isDisposed) logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true)) }
        private fun refreshDevices() { val adb = AndroidDebugBridge.getBridge(); val devices = adb?.devices?.toList() ?: emptyList(); ApplicationManager.getApplication().invokeLater { if (isDisposed) return@invokeLater; val current = deviceComboBox.selectedItem as? IDevice; if (deviceComboBox.model.size != devices.size) { deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray()); if (current != null) deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber } } } }
        private fun setStatusText(text: String, active: Boolean) { ApplicationManager.getApplication().invokeLater { if (!isDisposed) { statusField.text = text; resStatusPanel.isVisible = active || text.isNotEmpty(); stopResolutionButton.isVisible = active } } }
        private fun openFileInEditor(relativeName: String) { val file = File(project.basePath, relativeName); if (file.exists()) { val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file); if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true) } }
        private fun saveLogsToFile() {
            val selectedPackage = lastSelectedPackage ?: "all"
            val fileName = "logs_${selectedPackage.replace(".", "_")}_${System.currentTimeMillis()}.txt"
            val fileWrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Сохранить логи", "Выберите место", "txt"), project)
                .save(LocalFileSystem.getInstance().findFileByPath(project.basePath ?: ""), fileName)
            if (fileWrapper != null) {
                try {
                    val content = StringBuilder()
                    for (row in 0 until logTableModel.rowCount) content.append((0 until logTable.columnCount).joinToString(" ") { logTable.getValueAt(row, it).toString() }).append("\n")
                    fileWrapper.file.writeText(content.toString())
                } catch (e: Exception) { Messages.showErrorDialog("Ошибка: ${e.message}", "Ошибка") }
            }
        }
        fun getContent() = panel
    }
}

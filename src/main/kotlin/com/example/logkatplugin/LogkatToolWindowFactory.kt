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
        private val searchField = SearchTextField().apply {
            textEditor.preferredSize = Dimension(150, 28)
        }
        
        private val statusField = JTextField().apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 11)
            preferredSize = Dimension(180, 26)
            background = JBColor(Color(230, 230, 230), Color(45, 45, 45))
            foreground = JBColor.GRAY
            border = BorderFactory.createLineBorder(JBColor.border())
        }
        
        private val stopResolutionButton = JButton(AllIcons.Actions.Suspend).apply {
            preferredSize = Dimension(26, 26)
            toolTipText = "Остановить поиск имен"
            isBorderPainted = false
            isContentAreaFilled = false
        }

        private val resStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            add(statusField); add(stopResolutionButton)
            isVisible = false 
        }

        private val openDictionaryButton = createToolbarButton(AllIcons.Actions.EditSource, "Открыть словарь имен")
        private val openDescriptionsButton = createToolbarButton(AllIcons.Actions.Help, "Открыть базу знаний")
        private val resetToDefaultButton = createToolbarButton(AllIcons.Actions.Rollback, "Сбросить настройки")

        private val bulkUpdateLabelsButton = createToolbarButton(AllIcons.Actions.Refresh, "Синхронизировать имена и иконки").apply {
            addActionListener {
                val device = currentDevice
                if (device == null) {
                    Messages.showErrorDialog(project, "Устройство не выбрано", "Ошибка")
                    return@addActionListener
                }
                if (Messages.showYesNoDialog(project, "Запустить глубокую синхронизацию (имена + иконки)?", "Синхронизация", Messages.getQuestionIcon()) == Messages.YES) {
                    isEnabled = false
                    setStatusText("🔍 Глубокая синхронизация...", true)
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            val scriptPath = "${project.basePath}/on-device-server/src/run_resolver.ps1"
                            val processBuilder = ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath)
                            processBuilder.directory(File(project.basePath ?: ""))
                            val process = processBuilder.start()
                            process.inputStream.bufferedReader().use { reader ->
                                reader.forEachLine { line ->
                                    if (line.contains("|")) {
                                        val parts = line.split("|")
                                        if (parts.size >= 2) {
                                            val pkg = parts[0].trim()
                                            val label = parts[1].trim()
                                            packageToLabel[pkg] = label
                                            LogExplanationProvider.writeToDictionary(project.basePath, pkg, label, projectPkg)
                                        }
                                    }
                                }
                            }
                            process.waitFor()
                            ApplicationManager.getApplication().invokeLater {
                                if (!isDisposed) { loadInitialData(); reloadTreeSafely(); isEnabled = true; setStatusText("Готово", false) }
                            }
                        } catch (e: Exception) {
                            ApplicationManager.getApplication().invokeLater { setStatusText("Ошибка: ${e.message}", false); isEnabled = true }
                        }
                    }
                }
            }
        }

        private val clearButton = createToolbarButton(AllIcons.Actions.GC, "Очистить логи")
        private val saveButton = createToolbarButton(AllIcons.Actions.MenuSaveall, "Сохранить логи")
        private val autoscrollButton = createToggleButton(AllIcons.RunConfigurations.Scroll_down, "Автопрокрутка", true)
        private val allLogsButton = createToggleButton(AllIcons.General.Filter, "Все логи", true)

        enum class ResolutionMode(val label: String) {
            NONE("Не сопоставлять"), TEST_ONLY("Только мои"), INSTALLED_ONLY("Установленные"), ALL("Все процессы")
        }

        private val resolutionCombo = ComboBox(ResolutionMode.values()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is ResolutionMode) text = value.label
                    return c
                }
            }
            preferredSize = Dimension(140, 28); selectedIndex = 0
        }
        
        private var filterLevel: String? = null
        private val colorButtons = mutableListOf<JToggleButton>()
        private val currentResolutionId = AtomicInteger(0)
        private var isDisposed = false
        private val timer: javax.swing.Timer

        init {
            loadInitialData(); setupUI()
            ToolTipManager.sharedInstance().initialDelay = 100 
            allLogsButton.isSelected = true; updateButtonBorders()
            openDictionaryButton.addActionListener { openFileInEditor(LogExplanationProvider.DICTIONARY_FILENAME) }
            openDescriptionsButton.addActionListener { openFileInEditor(LogExplanationProvider.DESCRIPTIONS_FILENAME) }
            resetToDefaultButton.addActionListener {
                if (Messages.showYesNoDialog(project, "Восстановить настройки?", "Сброс", Messages.getWarningIcon()) == Messages.YES) {
                    LogExplanationProvider.resetToDefault(project.basePath); packageToLabel.clear(); loadInitialData(); reloadTreeSafely()
                }
            }
            timer = javax.swing.Timer(3000) { if (!isDisposed) { refreshDevices(); refreshProcesses() } }
            timer.start(); refreshDevices()
        }

        override fun dispose() { isDisposed = true; timer.stop(); currentResolutionId.incrementAndGet(); hideActiveBalloon() }

        private fun openFileInEditor(relativeName: String) {
            val file = File(project.basePath, relativeName)
            if (file.exists()) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (virtualFile != null) FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }

        private fun loadInitialData() {
            LogExplanationProvider.loadAllDescriptions(project.basePath)
            packageToLabel.putAll(LogExplanationProvider.loadDictionary(project.basePath))
            projectPkg = LogExplanationProvider.getProjectPackageName(project)
            iconCache.clear()
        }

        private fun setStatusText(text: String, active: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                statusField.text = text; resStatusPanel.isVisible = active || text.isNotEmpty(); stopResolutionButton.isVisible = active
            }
        }

        private fun setupUI() {
            processTree.isRootVisible = false
            processTree.setCellRenderer(object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
                    val label = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus) as JLabel
                    val node = value as? DefaultMutableTreeNode
                    val userObject = node?.userObject as? String ?: ""
                    val parent = node?.parent as? DefaultMutableTreeNode
                    val grandParent = parent?.parent as? DefaultMutableTreeNode
                    
                    val isGroup = parent == rootNode
                    val isPackage = grandParent == rootNode
                    val isPid = !isGroup && !isPackage && node != rootNode

                    if (isGroup) {
                        label.text = userObject; label.font = label.font.deriveFont(Font.BOLD, 13f)
                        label.foreground = getGroupColor(userObject); label.icon = AllIcons.Nodes.Folder
                        return label
                    }
                    if (isPackage) {
                        val appLabel = packageToLabel[userObject]
                        label.text = appLabel ?: userObject
                        label.foreground = if (sel) Color.WHITE else getGroupColor(parent?.userObject as? String ?: "")
                        label.icon = getAppIcon(userObject)
                        return label
                    }
                    if (isPid) {
                        label.text = "PID: $userObject"; label.foreground = if (sel) Color.WHITE else Color.GRAY
                        label.icon = AllIcons.Debugger.Console
                        return label
                    }
                    return label
                }
            })

            logTable.setShowGrid(false); logTable.background = Color(30, 30, 30)
            logTable.font = Font("Monospaced", Font.PLAIN, 14); logTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
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
                            if (pkg != null && (pkg.contains("android") || pkg.contains("system"))) {
                                c.foreground = Color(100, 150, 255); c.background = Color(40, 40, 60)
                            } else { c.foreground = Color(100, 255, 100); c.background = Color(55, 55, 55) }
                        }
                    }
                    return c
                }
            })

            val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            leftToolbar.add(deviceComboBox); leftToolbar.add(resStatusPanel)
            leftToolbar.add(openDictionaryButton); leftToolbar.add(openDescriptionsButton)
            leftToolbar.add(resetToDefaultButton); leftToolbar.add(bulkUpdateLabelsButton)

            val filterGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            listOf(createFilterToggleButton(Color(180, 0, 0), "E", "Ошибки"),
                   createFilterToggleButton(Color(250, 200, 0), "W", "Варнинги"),
                   createFilterToggleButton(Color(100, 255, 100), "I", "Инфо"),
                   createFilterToggleButton(Color(100, 150, 255), "S", "Система")
            ).forEach { colorButtons.add(it); filterGroupPanel.add(it) }

            val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
            rightToolbar.add(searchField); rightToolbar.add(allLogsButton); rightToolbar.add(resolutionCombo)
            rightToolbar.add(filterGroupPanel); rightToolbar.add(autoscrollButton); rightToolbar.add(clearButton)
            
            val topPanel = JPanel(BorderLayout())
            topPanel.add(leftToolbar, BorderLayout.WEST); topPanel.add(rightToolbar, BorderLayout.EAST)
            panel.add(topPanel, BorderLayout.NORTH)

            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), JBScrollPane(logTable))
            splitPane.dividerLocation = 280; panel.add(splitPane, BorderLayout.CENTER)
            
            processTree.addTreeSelectionListener { 
                val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val selectedValue = node?.userObject as? String
                if (selectedValue != null && selectedValue != lastSelectedPackage) {
                    val parent = node.parent as? DefaultMutableTreeNode
                    val pkgName = if (parent != null && parent != rootNode && (parent.parent as? DefaultMutableTreeNode) == rootNode) parent.userObject as? String else selectedValue
                    if (pkgName != lastSelectedPackage) { lastSelectedPackage = pkgName; rebuildLogTable() }
                }
            }
            deviceComboBox.addActionListener {
                val selected = deviceComboBox.selectedItem as? IDevice
                if (selected != null && selected.serialNumber != currentDevice?.serialNumber) {
                    currentDevice = selected; startLogcatCapture(selected)
                }
            }
        }

        private fun getGroupColor(groupName: String): Color = when {
            groupName.contains("МОЙ ПРОЕКТ") -> Color(180, 150, 255)
            groupName.contains("СИСТЕМНЫЕ") -> Color(100, 150, 255)
            groupName.contains("ПРИЛОЖЕНИЯ") -> Color(150, 255, 150)
            groupName.contains("GOOGLE") -> Color(255, 255, 150)
            groupName.contains("ИНТЕРФЕЙС") -> Color(150, 255, 255)
            else -> Color.LIGHT_GRAY
        }

        private fun getAppIcon(pkgName: String): Icon {
            val cached = iconCache[pkgName]
            if (cached != null) return cached
            val iconFile = File("${project.basePath}/on-device-server/src/icons/$pkgName.png")
            if (iconFile.exists()) {
                try {
                    val img = ImageIcon(iconFile.absolutePath)
                    val scaled = ImageIcon(img.image.getScaledInstance(16, 16, Image.SCALE_SMOOTH))
                    iconCache[pkgName] = scaled
                    return scaled
                } catch (e: Exception) {}
            }
            return when {
                pkgName.contains("example") || pkgName == projectPkg -> AllIcons.Nodes.HomeFolder
                pkgName.contains("google") -> AllIcons.Nodes.PpWeb
                pkgName.contains("android") -> AllIcons.General.Settings
                else -> AllIcons.Nodes.Package
            }
        }

        private fun refreshProcesses() {
            val device = currentDevice ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                device.clients.forEach { it.clientData.packageName?.let { pkg -> pidToPackage[it.clientData.pid] = pkg } }
                val processReceiver = object : MultiLineReceiver() {
                    override fun processNewLines(lines: Array<out String>) {
                        lines.forEach { line ->
                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size >= 8) {
                                val pid = parts[1].toIntOrNull() ?: parts[0].toIntOrNull()
                                if (pid != null) pidToPackage.putIfAbsent(pid, parts.last())
                            }
                        }
                    }
                    override fun isCancelled() = isDisposed
                }
                try { device.executeShellCommand("ps -A", processReceiver, 0, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
                val currentPids = pidToPackage.toMap()
                if (currentPids == lastKnownPids) return@executeOnPooledThread
                lastKnownPids = currentPids

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    
                    val expandedNames = mutableSetOf<List<String>>()
                    for (i in 0 until processTree.rowCount) {
                        if (processTree.isExpanded(i)) {
                            val path = processTree.getPathForRow(i)
                            expandedNames.add(path.path.map { (it as DefaultMutableTreeNode).userObject.toString() })
                        }
                    }
                    
                    rootNode.removeAllChildren()
                    val pkgToPids = mutableMapOf<String, MutableList<Int>>()
                    currentPids.forEach { (pid, pkg) -> if (pkg != "Unknown") pkgToPids.getOrPut(pkg) { mutableListOf() }.add(pid) }
                    val allPackages = pkgToPids.keys.sorted()
                    
                    addGroupWithChildren("⭐ МОЙ ПРОЕКТ", allPackages.filter { it.contains("example") || it == projectPkg }, pkgToPids)
                    addGroupWithChildren("👤 ПРИЛОЖЕНИЯ", allPackages.filter { !it.contains("android") && !it.contains("google") && it != projectPkg && !it.contains("example") && !it.startsWith("[") }, pkgToPids)
                    addGroupWithChildren("☁️ GOOGLE", allPackages.filter { it.contains("google") }, pkgToPids)
                    addGroupWithChildren("🖼️ ИНТЕРФЕЙС", allPackages.filter { it.contains("systemui") || it.contains("launcher") }, pkgToPids)
                    addGroupWithChildren("🛠️ СИСТЕМНЫЕ", allPackages.filter { (it.contains("android") && !it.contains("systemui")) || it.startsWith("[") }, pkgToPids)
                    
                    treeModel.reload()
                    restoreExpansionState(rootNode, TreePath(rootNode), expandedNames)
                }
            }
        }

        private fun restoreExpansionState(node: DefaultMutableTreeNode, path: TreePath, expandedNames: Set<List<String>>) {
            val currentPathNames = path.path.map { (it as DefaultMutableTreeNode).userObject.toString() }
            if (expandedNames.contains(currentPathNames)) { processTree.expandPath(path) }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                restoreExpansionState(child, path.pathByAddingChild(child), expandedNames)
            }
        }

        private fun addGroupWithChildren(title: String, packages: List<String>, pkgMap: Map<String, List<Int>>) {
            if (packages.isEmpty()) return
            val groupNode = DefaultMutableTreeNode(title)
            packages.forEach { pkg ->
                val pkgNode = DefaultMutableTreeNode(pkg)
                pkgMap[pkg]?.sorted()?.forEach { pid -> pkgNode.add(DefaultMutableTreeNode(pid.toString())) }
                groupNode.add(pkgNode)
            }
            rootNode.add(groupNode)
        }

        private fun reloadTreeSafely() { ApplicationManager.getApplication().invokeLater { if (!isDisposed) treeModel.reload() } }
        private fun createToolbarButton(icon: Icon, tip: String) = JButton(icon).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createToggleButton(icon: Icon, tip: String, initial: Boolean) = JToggleButton(icon, initial).apply { preferredSize = Dimension(28, 28); toolTipText = tip }

        private fun createFilterToggleButton(color: Color, level: String, tip: String): JToggleButton {
            return JToggleButton().apply {
                preferredSize = Dimension(28, 28); background = color; isOpaque = true; isContentAreaFilled = true
                border = BorderFactory.createLineBorder(Color.GRAY, 1); toolTipText = tip
                addActionListener {
                    if (isSelected) { filterLevel = level; allLogsButton.isSelected = false; colorButtons.filter { it != this }.forEach { it.isSelected = false } }
                    else if (filterLevel == level) filterLevel = null
                    updateButtonBorders(); rebuildLogTable()
                }
            }
        }

        private fun updateButtonBorders() {
            val activeBorder = BorderFactory.createLineBorder(JBColor.namedColor("Label.foreground", Color.BLACK), 3)
            val normalBorder = BorderFactory.createLineBorder(Color.GRAY, 1)
            allLogsButton.border = if (allLogsButton.isSelected) activeBorder else normalBorder
            colorButtons.forEach { it.border = if (it.isSelected) activeBorder else normalBorder }
        }

        private fun startLogcatCapture(device: IDevice) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    synchronized(allLogs) { allLogs.clear() }
                    device.executeShellCommand("logcat -v threadtime", object : MultiLineReceiver() {
                        override fun processNewLines(lines: Array<out String>) {
                            lines.forEach { line ->
                                if (line.length > 10) {
                                    val cleanLine = if (line.length > 700) line.substring(0, 700) else line
                                    synchronized(allLogs) { allLogs.add(cleanLine); if (allLogs.size > 5000) allLogs.removeAt(0) }
                                    ApplicationManager.getApplication().invokeLater { 
                                        if (isDisposed) return@invokeLater
                                        if (lastSelectedPackage != null && isLineRelatedToPackage(line, lastSelectedPackage!!) && isLinePassingFilter(line)) {
                                            if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) logTableModel.removeRow(0)
                                            logTableModel.addRow(parseLogLine(cleanLine))
                                            if (logTableModel.rowCount > 1500) logTableModel.removeRow(0); if (autoscroll) scrollTableToBottom()
                                        }
                                    }
                                }
                            }
                        }
                        override fun isCancelled() = (currentDevice?.serialNumber != device.serialNumber || isDisposed)
                    }, 0, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {}
            }
        }

        private fun parseLogLine(line: String): Array<String> {
            val parts = line.trim().split(Regex("\\s+"), 6)
            if (parts.size < 6) return arrayOf("", "", "", "", "", line)
            val rest = parts[5].split(":", limit = 2)
            return arrayOf(parts[1], parts[2], parts[3], parts[4], rest.getOrNull(0) ?: "", rest.getOrNull(1)?.trim() ?: "")
        }

        private fun isLinePassingFilter(line: String): Boolean {
            val query = searchField.text.trim()
            if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false
            if (allLogsButton.isSelected) return true
            val parts = parseLogLine(line); val level = parts[3]
            return when (filterLevel) {
                "E" -> level == "E"; "W" -> level == "W"
                "I" -> level == "I" || level == "V" || level == "D"
                "S" -> { val pid = parts[1].toIntOrNull(); val pkg = pid?.let { pidToPackage[it] }; pkg != null && (pkg.contains("android") || pkg.contains("system")) }
                else -> true
            }
        }

        private fun isLineRelatedToPackage(line: String, packageName: String): Boolean {
            val parts = parseLogLine(line); val pidFromLine = parts[1].toIntOrNull()
            if (pidFromLine != null && pidToPackage[pidFromLine] == packageName) return true
            return line.contains(packageName, ignoreCase = true) || (line.contains("ActivityManager") && line.contains(packageName))
        }

        private fun rebuildLogTable() {
            if (isDisposed || lastSelectedPackage == null) return
            logTableModel.rowCount = 0; val snapshot = synchronized(allLogs) { allLogs.toList() }
            val filtered = snapshot.filter { isLineRelatedToPackage(it, lastSelectedPackage!!) && isLinePassingFilter(it) }
            if (filtered.isEmpty()) logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg))
            else filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }
            if (autoscroll) scrollTableToBottom()
        }

        private fun scrollTableToBottom() { if (logTableModel.rowCount > 0 && !isDisposed) logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true)) }

        private fun refreshDevices() {
            val adb = AndroidDebugBridge.getBridge(); val devices = adb?.devices?.toList() ?: emptyList()
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                val current = deviceComboBox.selectedItem as? IDevice
                if (deviceComboBox.model.size != devices.size) {
                    deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray())
                    if (current != null) deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber }
                }
            }
        }

        private fun stopResolution(msg: String) { currentResolutionId.incrementAndGet(); setStatusText(msg, false) }
        private fun hideActiveBalloon() { activeBalloon?.hide(); activeBalloon = null }
        fun getContent() = panel
    }
}

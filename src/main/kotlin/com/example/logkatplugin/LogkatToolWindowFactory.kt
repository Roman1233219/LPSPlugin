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
        
        private var currentDevice: IDevice? = null
        private var autoscroll = true
        private var lastSelectedPackage: String? = null
        private var lastKnownPackages: Set<String> = emptySet()
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
            add(statusField)
            add(stopResolutionButton)
            isVisible = false 
        }

        private val openDictionaryButton = createToolbarButton(AllIcons.Actions.EditSource, "Открыть словарь имен (пакет=название)").apply {
            addActionListener { openFileInEditor(LogExplanationProvider.DICTIONARY_FILENAME) }
        }

        private val openDescriptionsButton = createToolbarButton(AllIcons.Actions.Help, "Открыть базу знаний (описания служб и ошибок)").apply {
            addActionListener { openFileInEditor(LogExplanationProvider.DESCRIPTIONS_FILENAME) }
        }

        private val clearButton = createToolbarButton(AllIcons.Actions.GC, "Очистить текущие логи")
        private val saveButton = createToolbarButton(AllIcons.Actions.MenuSaveall, "Сохранить логи в файл")
        private val autoscrollButton = createToggleButton(AllIcons.RunConfigurations.Scroll_down, "Автопрокрутка", true)
        private val allLogsButton = createToggleButton(AllIcons.General.Filter, "Все логи процесса", true)

        enum class ResolutionMode(val label: String) {
            NONE("Не сопоставлять"),
            TEST_ONLY("Только мои"),
            INSTALLED_ONLY("Установленные"),
            ALL("Все процессы")
        }

        private val resolutionCombo = ComboBox(ResolutionMode.values()).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is ResolutionMode) text = value.label
                    return c
                }
            }
            preferredSize = Dimension(140, 28)
            selectedIndex = 0
        }
        
        private var filterLevel: String? = null
        private val colorButtons = mutableListOf<JToggleButton>()
        private val currentResolutionId = AtomicInteger(0)
        private var isDisposed = false
        private val timer: javax.swing.Timer

        init {
            loadInitialData()
            setupUI()
            ToolTipManager.sharedInstance().initialDelay = 100 
            allLogsButton.isSelected = true
            updateButtonBorders()
            
            stopResolutionButton.addActionListener { stopResolution("Остановлено") }

            timer = javax.swing.Timer(3000) { 
                if (!isDisposed) { refreshDevices(); refreshProcesses() }
            }
            timer.start()
            refreshDevices()
        }

        override fun dispose() {
            isDisposed = true
            timer.stop()
            currentResolutionId.incrementAndGet()
            hideActiveBalloon()
        }

        private fun openFileInEditor(relativeName: String) {
            val file = File(project.basePath, relativeName)
            if (file.exists()) {
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } else {
                Messages.showInfoMessage("Файл ещё не создан. Запустите сопоставление процессов.", "Информация")
            }
        }

        private fun loadInitialData() {
            LogExplanationProvider.loadAllDescriptions(project.basePath)
            packageToLabel.putAll(LogExplanationProvider.loadDictionary(project.basePath))
            projectPkg = LogExplanationProvider.getProjectPackageName(project)
        }

        private fun setStatusText(text: String, active: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                statusField.text = text
                resStatusPanel.isVisible = active || text.isNotEmpty()
                stopResolutionButton.isVisible = active
            }
        }

        private fun stopResolution(msg: String) {
            currentResolutionId.incrementAndGet()
            setStatusText(msg, false)
        }

        private fun setupUI() {
            processTree.isRootVisible = false
            processTree.setCellRenderer(object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component {
                    val label = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus) as JLabel
                    val node = value as? DefaultMutableTreeNode
                    val textValue = node?.userObject as? String ?: ""
                    val parentNode = node?.parent as? DefaultMutableTreeNode
                    val groupName = if (leaf) parentNode?.userObject as? String ?: "" else textValue

                    val groupColor = when {
                        groupName.contains("МОЙ ПРОЕКТ") -> Color(180, 150, 255)
                        groupName.contains("СИСТЕМНЫЕ") -> Color(100, 150, 255)
                        groupName.contains("ПРИЛОЖЕНИЯ") -> Color(150, 255, 150)
                        groupName.contains("GOOGLE") -> Color(255, 255, 150)
                        groupName.contains("ИНТЕРФЕЙС") -> Color(150, 255, 255)
                        else -> Color.LIGHT_GRAY
                    }

                    if (!leaf) {
                        label.text = textValue
                        label.font = label.font.deriveFont(Font.BOLD, 13f)
                        label.foreground = groupColor
                        label.icon = AllIcons.Nodes.Folder
                        return label
                    }

                    val appLabel = packageToLabel[textValue]
                    val pid = pidToPackage.entries.find { it.value == textValue }?.key
                    label.text = if (appLabel != null) (if (pid != null) "$pid | $appLabel" else appLabel) else (if (pid != null) "$pid | $textValue" else textValue)
                    label.foreground = if (sel) Color.WHITE else groupColor
                    label.toolTipText = if (appLabel != null) "Пакет: $textValue" else null
                    
                    label.icon = when {
                        textValue.contains("example") || (projectPkg != null && textValue == projectPkg) -> AllIcons.Nodes.HomeFolder
                        textValue.contains("google") -> AllIcons.Nodes.PpWeb
                        textValue.contains("systemui") || textValue.contains("launcher") -> AllIcons.Nodes.Artifact
                        textValue.contains("android") || textValue.contains("server") -> AllIcons.General.Settings
                        else -> AllIcons.Nodes.Package
                    }
                    return label
                }
            })

            logTable.setShowGrid(false)
            logTable.intercellSpacing = Dimension(0, 0)
            logTable.background = Color(30, 30, 30)
            logTable.font = Font("Monospaced", Font.PLAIN, 14)
            logTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
            
            val columnModel = logTable.columnModel
            columnModel.getColumn(0).preferredWidth = 110
            columnModel.getColumn(1).preferredWidth = 60
            columnModel.getColumn(2).preferredWidth = 60
            columnModel.getColumn(3).preferredWidth = 35
            columnModel.getColumn(4).preferredWidth = 150
            columnModel.getColumn(5).preferredWidth = 1500
            
            logTable.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                    val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                    val level = table?.getValueAt(row, 3) as? String ?: ""
                    val message = table?.getValueAt(row, 5) as? String ?: ""
                    if (message == stalledMsg) {
                        c.foreground = Color.GRAY; c.background = Color(30, 30, 30)
                        return c
                    }
                    when (level) {
                        "E" -> { c.foreground = Color.WHITE; c.background = Color(180, 0, 0) }
                        "W" -> { c.foreground = Color.BLACK; c.background = Color(250, 200, 0) }
                        else -> {
                            val pidStr = table?.getValueAt(row, 1) as? String ?: ""
                            val pid = pidStr.toIntOrNull()
                            val pkg = if (pid != null) pidToPackage[pid] else null
                            if (pkg != null && (pkg.contains("android") || pkg.contains("system"))) {
                                c.foreground = Color(100, 150, 255); c.background = Color(40, 40, 60)
                            } else {
                                c.foreground = Color(100, 255, 100); c.background = Color(55, 55, 55)
                            }
                        }
                    }
                    if (isSelected) c.background = c.background.darker()
                    return c
                }
            })

            searchField.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = rebuildLogTable()
                override fun removeUpdate(e: DocumentEvent) = rebuildLogTable()
                override fun changedUpdate(e: DocumentEvent) = rebuildLogTable()
            })

            val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            leftToolbar.add(JLabel("Dev:"))
            leftToolbar.add(deviceComboBox)
            leftToolbar.add(resStatusPanel)
            leftToolbar.add(openDictionaryButton)
            leftToolbar.add(openDescriptionsButton)

            val filterGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            filterGroupPanel.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 1, Color.GRAY),
                BorderFactory.createEmptyBorder(0, 5, 0, 5)
            )
            
            listOf(
                createFilterToggleButton(Color(180, 0, 0), "E", "Ошибки"),
                createFilterToggleButton(Color(250, 200, 0), "W", "Варнинги"),
                createFilterToggleButton(Color(100, 255, 100), "I", "Инфо"),
                createFilterToggleButton(Color(100, 150, 255), "S", "Система")
            ).forEach { colorButtons.add(it); filterGroupPanel.add(it) }

            val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
            allLogsButton.addActionListener { 
                if (allLogsButton.isSelected) { filterLevel = null; colorButtons.forEach { it.isSelected = false } }
                updateButtonBorders(); rebuildLogTable() 
            }
            resolutionCombo.addActionListener {
                val mode = resolutionCombo.selectedItem as? ResolutionMode ?: ResolutionMode.NONE
                stopResolution("Смена режима")
                if (mode == ResolutionMode.NONE) {
                    packageToLabel.clear(); reloadTreeSafely(); setStatusText("", false)
                } else {
                    val device = currentDevice
                    if (device != null) startNameResolution(device, pidToPackage.values.toSet())
                }
            }
            autoscrollButton.addActionListener { autoscroll = autoscrollButton.isSelected; if (autoscroll) scrollTableToBottom() }
            clearButton.addActionListener { synchronized(allLogs) { allLogs.clear() }; rebuildLogTable() }
            saveButton.addActionListener { saveLogsToFile() }

            rightToolbar.add(JLabel(AllIcons.Actions.Search)); rightToolbar.add(searchField)
            rightToolbar.add(allLogsButton); rightToolbar.add(resolutionCombo)
            rightToolbar.add(filterGroupPanel); rightToolbar.add(autoscrollButton)
            rightToolbar.add(clearButton); rightToolbar.add(saveButton)
            
            val topPanel = JPanel(BorderLayout())
            topPanel.add(leftToolbar, BorderLayout.WEST); topPanel.add(rightToolbar, BorderLayout.EAST)
            panel.add(topPanel, BorderLayout.NORTH)

            val tableMouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = checkAndHideBalloon(e)
                override fun mouseClicked(e: MouseEvent) {
                    val row = logTable.rowAtPoint(e.point)
                    if (row == -1 || logTable.getValueAt(row, 5) == stalledMsg) return
                    if (SwingUtilities.isRightMouseButton(e)) {
                        logTable.setRowSelectionInterval(row, row)
                        val message = logTable.getValueAt(row, 5) as? String ?: ""
                        val tag = logTable.getValueAt(row, 4) as? String ?: ""
                        val pidStr = logTable.getValueAt(row, 1) as? String ?: ""
                        val level = logTable.getValueAt(row, 3) as? String ?: ""
                        val menu = JPopupMenu()
                        val copyItem = JMenuItem("Копировать", AllIcons.Actions.Copy)
                        copyItem.addActionListener { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(message), null) }
                        menu.add(copyItem)
                        val infoItem = JMenuItem("Что это?", AllIcons.Actions.Help)
                        infoItem.addActionListener {
                            val pid = pidStr.toIntOrNull()
                            val pkgName = if (pid != null) pidToPackage[pid] else null
                            showHint(LogExplanationProvider.getSmartLogExplanation(pkgName, tag, message, level), e, logTable, true, message)
                        }
                        menu.add(infoItem)
                        menu.show(logTable, e.x, e.y)
                    }
                }
            }
            logTable.addMouseListener(tableMouseListener); logTable.addMouseMotionListener(tableMouseListener)

            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), JBScrollPane(logTable))
            splitPane.dividerLocation = 280
            panel.add(splitPane, BorderLayout.CENTER)
            
            processTree.addTreeSelectionListener { 
                val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (node != null && node.isLeaf) {
                    val selectedPackage = node.userObject as? String
                    if (selectedPackage != null && selectedPackage != lastSelectedPackage) {
                        rebuildLogTable(); lastSelectedPackage = selectedPackage
                    }
                }
            }
            deviceComboBox.addActionListener {
                val selected = deviceComboBox.selectedItem as? IDevice
                if (selected != null && (currentDevice == null || selected.serialNumber != currentDevice?.serialNumber)) {
                    currentDevice = selected; packageToLabel.clear(); loadInitialData(); startLogcatCapture(selected)
                }
            }
        }

        private fun reloadTreeSafely() {
            if (isDisposed) return
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                val expandedPaths = mutableListOf<TreePath>()
                for (i in 0 until processTree.rowCount) if (processTree.isExpanded(i)) expandedPaths.add(processTree.getPathForRow(i))
                val selectionPath = processTree.selectionPath
                treeModel.reload()
                expandedPaths.forEach { processTree.expandPath(it) }
                if (selectionPath != null) processTree.selectionPath = selectionPath
            }
        }

        private fun createToolbarButton(icon: Icon, tip: String) = JButton(icon).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createToggleButton(icon: Icon, tip: String, initial: Boolean) = JToggleButton(icon, initial).apply { preferredSize = Dimension(28, 28); toolTipText = tip }

        private fun createFilterToggleButton(color: Color, level: String, tip: String): JToggleButton {
            return JToggleButton().apply {
                preferredSize = Dimension(28, 28); background = color; isOpaque = true; isContentAreaFilled = true; border = BorderFactory.createLineBorder(Color.GRAY, 1); toolTipText = tip
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

        private fun checkAndHideBalloon(e: MouseEvent) { if (activeBalloon != null && !isStickyBalloon) hideActiveBalloon() }
        private fun hideActiveBalloon() { activeBalloon?.hide(); activeBalloon = null }

        private fun saveLogsToFile() {
            val selectedPackage = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String ?: "all"
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

        private fun showHint(text: String, e: MouseEvent, component: Component, isSticky: Boolean, originalMessage: String = "") {
            hideActiveBalloon(); isStickyBalloon = isSticky
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), object : HyperlinkListener {
                    override fun hyperlinkUpdate(event: HyperlinkEvent) {
                        if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "show_stacktrace") {
                            Messages.showInfoMessage(LogExplanationProvider.getDetailedStackTraceExplanation(originalMessage), "Информация")
                        }
                    }
                })
                .setFadeoutTime(0).setHideOnClickOutside(true).createBalloon()
            balloon.show(RelativePoint(component, e.point), Balloon.Position.above); activeBalloon = balloon
        }

        private fun parseLogLine(line: String): Array<String> {
            val parts = line.trim().split(Regex("\\s+"), 6)
            if (parts.size < 6) return arrayOf("", "", "", "", "", line)
            val rest = parts[5].split(":", limit = 2)
            return arrayOf(parts[1], parts[2], parts[3], parts[4], rest.getOrNull(0) ?: "", rest.getOrNull(1)?.trim() ?: "")
        }

        private fun refreshProcesses() {
            val device = currentDevice ?: return
            if (isDisposed) return
            ApplicationManager.getApplication().executeOnPooledThread {
                if (isDisposed) return@executeOnPooledThread
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
                val allKnown = pidToPackage.values.toSet().filter { it != "Unknown" }.toSet()
                if (allKnown == lastKnownPackages && rootNode.childCount > 0) return@executeOnPooledThread
                lastKnownPackages = allKnown
                startNameResolution(device, allKnown)

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    val expandedPaths = mutableListOf<TreePath>()
                    for (i in 0 until processTree.rowCount) if (processTree.isExpanded(i)) expandedPaths.add(processTree.getPathForRow(i))
                    val selectedValue = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String
                    rootNode.removeAllChildren()
                    
                    val sortedList = allKnown.toList().sorted()
                    addGroupToTree("⭐ МОЙ ПРОЕКТ", sortedList.filter { it.contains("example") || (projectPkg != null && it == projectPkg) })
                    addGroupToTree("👤 ПРИЛОЖЕНИЯ", sortedList.filter { !it.contains("android") && !it.contains("google") && it != projectPkg && !it.contains("example") })
                    addGroupToTree("☁️ GOOGLE", sortedList.filter { it.contains("google") })
                    addGroupToTree("🖼️ ИНТЕРФЕЙС", sortedList.filter { it.contains("systemui") || it.contains("launcher") })
                    addGroupToTree("🛠️ СИСТЕМНЫЕ", sortedList.filter { it.contains("android") && !it.contains("systemui") })
                    treeModel.reload()
                    expandedPaths.forEach { processTree.expandPath(it) }
                    if (selectedValue != null) findAndSelectNode(rootNode, selectedValue)
                }
            }
        }

        private fun startNameResolution(device: IDevice, packages: Set<String>) {
            val mode = resolutionCombo.selectedItem as? ResolutionMode ?: ResolutionMode.NONE
            if (mode == ResolutionMode.NONE || isDisposed) return
            val runId = currentResolutionId.incrementAndGet() 
            ApplicationManager.getApplication().executeOnPooledThread {
                if (isDisposed) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater { if (!isDisposed) { resStatusPanel.isVisible = true; stopResolutionButton.isVisible = true } }
                
                // Сначала обрабатываем СВОЁ приложение
                if (projectPkg != null && packages.contains(projectPkg)) {
                    val myAppName = LogExplanationProvider.getProjectAppName(project)
                    if (myAppName != null) {
                        packageToLabel[projectPkg!!] = myAppName
                        reloadTreeSafely()
                    }
                }

                val others = packages.filter { it != projectPkg }
                others.forEach { pkg ->
                    if (runId != currentResolutionId.get() || isDisposed) return@executeOnPooledThread
                    if (packageToLabel.containsKey(pkg)) return@forEach
                    
                    val dictionary = LogExplanationProvider.loadDictionary(project.basePath)
                    val cachedLabel = dictionary[pkg]
                    if (cachedLabel != null) { 
                        packageToLabel[pkg] = cachedLabel
                        reloadTreeSafely()
                        return@forEach 
                    }

                    val isSystem = pkg.contains("android") || pkg.contains("system")
                    val shouldResolve = when (mode) {
                        ResolutionMode.NONE -> false
                        ResolutionMode.TEST_ONLY -> pkg.contains("example")
                        ResolutionMode.INSTALLED_ONLY -> !isSystem
                        ResolutionMode.ALL -> true
                    }
                    if (!shouldResolve) return@forEach

                    setStatusText("🔍 $pkg", true)
                    try {
                        val receiver = object : MultiLineReceiver() {
                            var label: String? = null
                            override fun processNewLines(lines: Array<out String>) {
                                lines.forEach { line ->
                                    val t = line.trim()
                                    if (t.contains("label=") || t.contains("application-label:")) {
                                        val raw = t.substringAfter(":").substringAfter("=").trim().removeSurrounding("'").removeSurrounding("\"").substringBefore(" ")
                                        if (raw.isNotEmpty() && !raw.startsWith("@0x")) label = raw
                                    }
                                }
                            }
                            override fun isCancelled() = (runId != currentResolutionId.get() || isDisposed)
                        }
                        device.executeShellCommand("dumpsys package $pkg", receiver, 0, TimeUnit.MILLISECONDS)
                        if (runId != currentResolutionId.get() || isDisposed) return@executeOnPooledThread
                        
                        val finalLabel = receiver.label
                        if (finalLabel != null) {
                            packageToLabel[pkg] = finalLabel
                        }
                        LogExplanationProvider.writeToDictionary(project.basePath, pkg, finalLabel, projectPkg)
                        reloadTreeSafely()
                        Thread.sleep(1500) 
                    } catch (e: Exception) {}
                }
                if (runId == currentResolutionId.get() && !isDisposed) setStatusText("Готово", false)
            }
        }

        private fun addGroupToTree(title: String, items: List<String>) {
            if (items.isEmpty()) return
            val groupNode = DefaultMutableTreeNode(title)
            items.forEach { groupNode.add(DefaultMutableTreeNode(it)) }
            rootNode.add(groupNode)
        }

        private fun findAndSelectNode(root: DefaultMutableTreeNode, target: String) {
            val e = root.breadthFirstEnumeration()
            while (e.hasMoreElements()) {
                val node = e.nextElement() as DefaultMutableTreeNode
                if (node.userObject == target) { processTree.selectionPath = TreePath(node.path); return }
            }
        }

        private fun startLogcatCapture(device: IDevice) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (isDisposed) return@executeOnPooledThread
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
                                        val selectedPackage = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String
                                        if (selectedPackage != null && isLineRelatedToPackage(line, selectedPackage) && isLinePassingFilter(line)) {
                                            if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) logTableModel.removeRow(0)
                                            logTableModel.addRow(parseLogLine(cleanLine))
                                            if (logTableModel.rowCount > 1500) logTableModel.removeRow(0)
                                            if (autoscroll) scrollTableToBottom()
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

        private fun isLinePassingFilter(line: String): Boolean {
            val query = searchField.text.trim()
            if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false
            if (allLogsButton.isSelected) return true
            val parts = parseLogLine(line); val level = parts[3]
            return when (filterLevel) {
                "E" -> level == "E"; "W" -> level == "W"
                "I" -> level == "I" || level == "V" || level == "D"
                "S" -> { val pid = parts[1].toIntOrNull(); val pkg = if (pid != null) pidToPackage[pid] else null; pkg != null && (pkg.contains("android") || pkg.contains("system")) }
                else -> true
            }
        }

        private fun isLineRelatedToPackage(line: String, packageName: String): Boolean {
            val parts = parseLogLine(line); val pidFromLine = parts[1].toIntOrNull()
            if (pidFromLine != null && pidToPackage[pidFromLine] == packageName) return true
            return line.contains(packageName, ignoreCase = true) || (line.contains("ActivityManager") && line.contains(packageName))
        }

        private fun rebuildLogTable() {
            if (isDisposed) return
            val selectedPackage = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String ?: return
            logTableModel.rowCount = 0; val snapshot = synchronized(allLogs) { allLogs.toList() }
            val filtered = snapshot.filter { isLineRelatedToPackage(it, selectedPackage) && isLinePassingFilter(it) }
            if (filtered.isEmpty()) logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg))
            else filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }
            if (autoscroll) scrollTableToBottom()
        }

        private fun scrollTableToBottom() { if (logTableModel.rowCount > 0 && !isDisposed) logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true)) }

        private fun refreshDevices() {
            if (isDisposed) return
            val adb = AndroidDebugBridge.getBridge(); val devices = adb?.devices?.toList() ?: emptyList()
            ApplicationManager.getApplication().invokeLater {
                if (isDisposed) return@invokeLater
                val current = deviceComboBox.selectedItem as? IDevice
                if (deviceComboBox.model.size != devices.size) {
                    deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray())
                    if (current != null) deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber }
                }
                if (devices.isEmpty()) { pidToPackage.clear(); lastKnownPackages = emptySet(); rootNode.removeAllChildren(); treeModel.reload(); allLogs.clear(); logTableModel.rowCount = 0 }
            }
        }

        fun getContent() = panel
    }
}

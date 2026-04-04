package com.example.logkatplugin

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class LogkatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = LogkatToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(project, myToolWindow)
    }

    class LogkatToolWindow(internal val project: Project) : Disposable {
        internal val panel = JPanel(BorderLayout())
        internal val rootNode = DefaultMutableTreeNode("Processes")
        internal val treeModel = DefaultTreeModel(rootNode)
        internal val processTree = Tree(treeModel)
        
        private val columnNames = arrayOf("Время", "PID", "TID", "Ур.", "Тег", "Сообщение")
        internal val logTableModel = LogkatTableModel(columnNames)
        internal val logTable = JBTable(logTableModel)
        
        internal val allLogs = mutableListOf<String>() 
        internal val pidToPackage = ConcurrentHashMap<Int, String>()
        internal val packageToLabel = ConcurrentHashMap<String, String>()
        internal val iconCache = ConcurrentHashMap<String, ImageIcon>()
        internal val fileLocationCache = ConcurrentHashMap<String, Boolean>()
        internal val pendingLogs = mutableListOf<Array<String>>()
        
        internal var currentDevice: IDevice? = null
        internal var autoscroll = true
        internal var lastSelectedPackage: String? = null
        internal var lastKnownPids: Map<Int, String> = emptyMap()
        internal var projectPkg: String? = null
        
        private var activeBalloon: Balloon? = null
        private var isStickyBalloon = false
        internal val stalledMsg = "Простаивает / нет логов"

        internal val deviceComboBox = ComboBox<IDevice>()
        internal val searchField = SearchTextField().apply { textEditor.preferredSize = Dimension(150, 28) }
        internal val statusField = JTextField().apply {
            isEditable = false; font = Font("Monospaced", Font.PLAIN, 11); preferredSize = Dimension(180, 26)
            background = JBColor(Color(230, 230, 230), Color(45, 45, 45)); foreground = JBColor.GRAY; border = BorderFactory.createLineBorder(JBColor.border())
        }
        internal val stopResolutionButton = JButton(AllIcons.Actions.Suspend).apply {
            preferredSize = Dimension(26, 26); toolTipText = "Остановить поиск"; isBorderPainted = false; isContentAreaFilled = false
        }
        internal val resStatusPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(statusField); add(stopResolutionButton); isVisible = false }

        private val openDictionaryButton = createToolbarButton(AllIcons.Actions.EditSource, "Словарь")
        private val openDescriptionsButton = createToolbarButton(AllIcons.Actions.Help, "База знаний")
        private val resetToDefaultButton = createToolbarButton(AllIcons.Actions.Rollback, "Сброс")
        internal val bulkUpdateLabelsButton = createToolbarButton(AllIcons.Actions.Refresh, "Синхронизация")
        
        internal val enableTraceButton = createToolbarButton(AllIcons.Actions.Execute, "Включить трассировку")
        internal val disableTraceButton = createToolbarButton(AllIcons.Actions.Suspend, "Выключить трассировку")

        private val clearButton = createToolbarButton(AllIcons.Actions.GC, "Очистить")
        private val saveButton = createToolbarButton(AllIcons.Actions.MenuSaveall, "Сохранить")
        private val autoscrollButton = createToggleButton(AllIcons.RunConfigurations.Scroll_down, "Автопрокрутка", true)
        internal val allLogsButton = createToggleButton(AllIcons.General.Filter, "Все логи", true)

        internal var filterLevel: String? = null
        internal val colorButtons = mutableListOf<JToggleButton>()
        internal val currentResolutionId = AtomicInteger(0)
        internal var isDisposed = false
        private val timer: javax.swing.Timer

        init {
            loadInitialData()
            setupTableMouseListener()
            setupUI()
            updateTraceButtonsState()
            setupExecutionListener()
            
            bulkUpdateLabelsButton.addActionListener { runDeepSync() }
            enableTraceButton.addActionListener { runTracePreparation() }
            disableTraceButton.addActionListener { runTraceRemoval() }
            
            openDictionaryButton.addActionListener { openFileInEditor(LogExplanationProvider.DICTIONARY_FILENAME) }
            openDescriptionsButton.addActionListener { openFileInEditor(LogExplanationProvider.DESCRIPTIONS_FILENAME) }
            clearButton.addActionListener { clearLogs() }
            saveButton.addActionListener { saveLogsToFile() }
            
            autoscrollButton.addActionListener { 
                autoscroll = autoscrollButton.isSelected
                updateButtonBorders()
                if (autoscroll) {
                    flushPendingLogs()
                }
            }

            resetToDefaultButton.addActionListener {
                if (Messages.showYesNoDialog(project, "Восстановить настройки?", "Сброс", Messages.getWarningIcon()) == Messages.YES) {
                    LogExplanationProvider.resetToDefault(project.basePath); packageToLabel.clear(); loadInitialData(); reloadTreeSafely()
                }
            }
            timer = javax.swing.Timer(3000) { if (!isDisposed) { refreshProcesses(); refreshDevices() } }
            timer.start(); refreshDevices()
        }

        private fun flushPendingLogs() {
            synchronized(pendingLogs) {
                if (pendingLogs.isNotEmpty()) {
                    logTableModel.addRows(ArrayList(pendingLogs))
                    pendingLogs.clear()
                    scrollTableToBottom()
                }
            }
        }

        private fun setupExecutionListener() {
            project.messageBus.connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                    ApplicationManager.getApplication().invokeLater {
                        if (isDisposed) return@invokeLater
                        
                        // 1. Очищаем все старые логи при любом запуске
                        clearLogs()
                        
                        // 2. Получаем реальный applicationId запущенного модуля через Android плагин ИДЕ
                        val module = (env.runProfile as? com.intellij.execution.configurations.ModuleRunProfile)?.modules?.firstOrNull()
                        val detectedPkg = module?.let { m ->
                            try {
                                val facet = org.jetbrains.android.facet.AndroidFacet.getInstance(m)
                                facet?.let { f ->
                                    val model = com.android.tools.idea.model.AndroidModel.get(f)
                                    model?.applicationId
                                }
                            } catch (e: Exception) { null }
                        } ?: env.runProfile.name.takeIf { it.contains(".") }

                        if (detectedPkg != null) {
                            projectPkg = detectedPkg
                            // Сразу обновляем дерево процессов, чтобы папка "Мой проект" обновилась
                            refreshProcesses()
                            if (lastSelectedPackage == projectPkg) {
                                rebuildLogTable()
                            }
                        }
                    }
                }
            })
        }

        internal fun clearLogs() {
            logTableModel.clear()
            synchronized(allLogs) { allLogs.clear() }
            synchronized(pendingLogs) { pendingLogs.clear() }
        }

        private fun setupTableMouseListener() {
            val mouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = checkAndHideBalloon(e)
                override fun mouseClicked(e: MouseEvent) {
                    val row = logTable.rowAtPoint(e.point)
                    if (row == -1) return
                    val messageValue = logTable.getValueAt(row, 5)?.toString() ?: ""
                    if (messageValue == stalledMsg) return

                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        navigateToCode(messageValue)
                        return
                    }
                    
                    if (SwingUtilities.isRightMouseButton(e)) {
                        logTable.setRowSelectionInterval(row, row)
                        val menu = JPopupMenu()
                        val copyItem = JMenuItem("Копировать", AllIcons.Actions.Copy)
                        copyItem.addActionListener {
                            val rowData = logTableModel.getRow(row)
                            if (rowData != null) {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(rowData.joinToString(" ")), null)
                            }
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

        override fun dispose() { isDisposed = true; timer.stop(); currentResolutionId.incrementAndGet(); hideActiveBalloon() }

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
                        label.foreground = getGroupColor(userObject); label.icon = getGroupIcon(userObject)
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
                    val tag = table?.getValueAt(row, 4) as? String ?: ""
                    
                    if (table?.getValueAt(row, 5) == stalledMsg) { c.foreground = Color.GRAY; return c }
                    
                    if (tag.trim().equals("LOGKAT_TRACE", ignoreCase = true)) {
                        val rowData = (table?.model as? LogkatTableModel)?.getRow(row)
                        val isAppCode = rowData?.getOrNull(6) == "APP"
                        
                        if (isAppCode) {
                            c.background = Color(0, 191, 255)
                        } else {
                            c.background = Color(180, 150, 255)
                        }
                        c.foreground = Color.BLACK
                    } else {
                        when (level) {
                            "E" -> { c.foreground = Color.WHITE; c.background = Color(180, 0, 0) }
                            "W" -> { c.foreground = Color.BLACK; c.background = Color(250, 200, 0) }
                            else -> {
                                val pid = (table?.getValueAt(row, 1) as? String)?.toIntOrNull()
                                val pkg = pid?.let { pidToPackage[it] }
                                if (pkg != null && (pkg.contains("android") || pkg.contains("system"))) { 
                                    c.foreground = Color(100, 150, 255); c.background = Color(40, 40, 60) 
                                } else { 
                                    c.foreground = Color(100, 255, 100); c.background = Color(55, 55, 55) 
                                }
                            }
                        }
                    }
                    
                    if (isSelected) c.background = c.background.darker()
                    return c
                }
            })

            val leftToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2))
            leftToolbar.add(deviceComboBox); leftToolbar.add(resStatusPanel); leftToolbar.add(openDictionaryButton); leftToolbar.add(openDescriptionsButton); leftToolbar.add(resetToDefaultButton); leftToolbar.add(bulkUpdateLabelsButton); 
            leftToolbar.add(enableTraceButton); leftToolbar.add(disableTraceButton)

            val filterGroupPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            listOf(createFilterToggleButton(Color(180, 0, 0), "E", "Ошибки"), createFilterToggleButton(Color(250, 200, 0), "W", "Варнинги"), createFilterToggleButton(Color(100, 255, 100), "I", "Инфо"), createFilterToggleButton(Color(100, 150, 255), "S", "Система")).forEach { colorButtons.add(it); filterGroupPanel.add(it) }
            val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
            rightToolbar.add(searchField); rightToolbar.add(allLogsButton); rightToolbar.add(filterGroupPanel); rightToolbar.add(autoscrollButton); rightToolbar.add(clearButton); rightToolbar.add(saveButton)
            val topPanel = JPanel(BorderLayout()); topPanel.add(leftToolbar, BorderLayout.WEST); topPanel.add(rightToolbar, BorderLayout.EAST); panel.add(topPanel, BorderLayout.NORTH)
            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), JBScrollPane(logTable)); splitPane.dividerLocation = 280; panel.add(splitPane, BorderLayout.CENTER)
            processTree.addTreeSelectionListener { val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode; val selectedValue = node?.userObject as? String; if (selectedValue != null && selectedValue != lastSelectedPackage) { val parent = node.parent as? DefaultMutableTreeNode; val pkgName = if (parent != null && parent != rootNode && (parent.parent as? DefaultMutableTreeNode) == rootNode) parent.userObject as? String else selectedValue; if (pkgName != lastSelectedPackage) { lastSelectedPackage = pkgName; rebuildLogTable() } } }
            deviceComboBox.addActionListener { val selected = deviceComboBox.selectedItem as? IDevice; if (selected != null && selected.serialNumber != currentDevice?.serialNumber) { currentDevice = selected; startLogcatCapture(selected) } }
            updateButtonBorders()
        }

        internal fun reloadTreeSafely() { ApplicationManager.getApplication().invokeLater { if (!isDisposed) treeModel.reload() } }
        internal fun loadInitialData() { 
            LogExplanationProvider.loadAllDescriptions(project.basePath)
            packageToLabel.putAll(LogExplanationProvider.loadDictionary(project.basePath))
            projectPkg = null 
            iconCache.clear() 
            fileLocationCache.clear()
        }
        private fun createToolbarButton(icon: Icon, tip: String) = JButton(icon).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createToggleButton(icon: Icon, tip: String, initial: Boolean) = JToggleButton(icon, initial).apply { preferredSize = Dimension(28, 28); toolTipText = tip }
        private fun createFilterToggleButton(color: Color, level: String, tip: String) = JToggleButton().apply {
            preferredSize = Dimension(28, 28); background = color; isOpaque = true; isContentAreaFilled = true; border = BorderFactory.createLineBorder(Color.GRAY, 1); toolTipText = tip; addActionListener { if (isSelected) { filterLevel = level; allLogsButton.isSelected = false; colorButtons.filter { it != this }.forEach { it.isSelected = false } } else if (filterLevel == level) filterLevel = null; updateButtonBorders(); rebuildLogTable() } }
        
        internal fun updateButtonBorders() { 
            val activeBorder = BorderFactory.createLineBorder(JBColor.namedColor("Label.foreground", Color.BLACK), 3)
            val redActiveBorder = BorderFactory.createLineBorder(Color.RED, 3)
            val inactiveBorder = BorderFactory.createLineBorder(Color.GRAY, 1)
            val noneBorder = BorderFactory.createEmptyBorder(1, 1, 1, 1)
            
            allLogsButton.border = if (allLogsButton.isSelected) activeBorder else inactiveBorder
            autoscrollButton.border = if (autoscrollButton.isSelected) redActiveBorder else noneBorder
            
            colorButtons.forEach { it.border = if (it.isSelected) activeBorder else inactiveBorder } 
        }

        private fun showHint(text: String, e: MouseEvent, component: Component, isSticky: Boolean, originalMessage: String = "") { hideActiveBalloon(); isStickyBalloon = isSticky; val balloon = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), object : HyperlinkListener { override fun hyperlinkUpdate(event: HyperlinkEvent) { if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "show_stacktrace") Messages.showInfoMessage(LogExplanationProvider.getDetailedStackTraceExplanation(originalMessage), "Информация") } }).setFadeoutTime(0).setHideOnClickOutside(true).createBalloon(); balloon.show(RelativePoint(component, e.point), Balloon.Position.above); activeBalloon = balloon }
        private fun checkAndHideBalloon(e: MouseEvent) { if (activeBalloon != null && !isStickyBalloon) hideActiveBalloon() }
        private fun hideActiveBalloon() { activeBalloon?.hide(); activeBalloon = null }
        
        internal fun refreshDevices() { 
            val adb = AndroidDebugBridge.getBridge()
            val devices = adb?.devices?.toList() ?: emptyList()
            ApplicationManager.getApplication().invokeLater { 
                if (isDisposed) return@invokeLater
                val current = deviceComboBox.selectedItem as? IDevice
                if (deviceComboBox.model.size != devices.size) { 
                    deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray())
                    if (current != null) deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber } 
                } 
            } 
        }
        
        internal fun setStatusText(text: String, active: Boolean) { 
            ApplicationManager.getApplication().invokeLater { 
                if (!isDisposed) { 
                    statusField.text = text; resStatusPanel.isVisible = active || text.isNotEmpty(); stopResolutionButton.isVisible = active 
                } 
            } 
        }
        
        private fun openFileInEditor(relativeName: String) { 
            val file = File(project.basePath, relativeName)
            if (file.exists()) { 
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                if (vf != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(vf, true) 
            } 
        }

        fun getContent() = panel
    }
}

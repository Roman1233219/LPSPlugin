package com.example.logkatplugin

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
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
import kotlin.math.sqrt

class LogkatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = LogkatToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    class LogkatToolWindow(private val project: Project) {
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
        
        private var currentDevice: IDevice? = null
        private var autoscroll = true
        private var hoveredPath: TreePath? = null
        private var lastSelectedPackage: String? = null
        private var lastKnownPackages: Set<String> = emptySet()
        
        private var activeBalloon: Balloon? = null
        private var lastHintPoint: Point? = null
        private var isStickyBalloon = false
        private val stalledMsg = "Простаивает / нет логов"

        private val deviceComboBox = ComboBox<IDevice>()
        private val searchField = SearchTextField().apply {
            textEditor.preferredSize = Dimension(150, 28)
        }
        
        private val clearButton = createToolbarButton(AllIcons.Actions.GC, "Очистить все логи")
        private val saveButton = createToolbarButton(AllIcons.Actions.MenuSaveall, "Сохранить логи в файл")
        private val autoscrollButton = createToggleButton(AllIcons.RunConfigurations.Scroll_down, "Автопрокрутка", true)
        private val allLogsButton = createToggleButton(AllIcons.General.Filter, "Все логи процесса", true)
        
        private var filterLevel: String? = null

        init {
            setupUI()
            ToolTipManager.sharedInstance().initialDelay = 100 
            
            // Сразу очищаем списки при запуске
            allLogs.clear()
            pidToPackage.clear()
            
            val timer = Timer(3000) { 
                refreshDevices()
                refreshProcesses() 
            }
            timer.start()
        }

        private fun createToolbarButton(icon: Icon, tip: String): JButton {
            return JButton(icon).apply {
                preferredSize = Dimension(28, 28)
                toolTipText = tip
            }
        }

        private fun createToggleButton(icon: Icon, tip: String, initial: Boolean): JToggleButton {
            return JToggleButton(icon, initial).apply {
                preferredSize = Dimension(28, 28)
                toolTipText = tip
            }
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

                    label.foreground = if (sel) Color.WHITE else groupColor
                    val pid = pidToPackage.entries.find { it.value == textValue }?.key
                    label.text = if (pid != null) "$pid | $textValue" else textValue
                    
                    var icon = when {
                        textValue.contains("example") -> AllIcons.Nodes.HomeFolder
                        textValue.contains("google") -> AllIcons.Nodes.PpWeb
                        textValue.contains("systemui") || textValue.contains("launcher") -> AllIcons.Nodes.Artifact
                        textValue.contains("android") || textValue.contains("server") -> AllIcons.General.Settings
                        else -> AllIcons.Nodes.Package
                    }
                    if (tree?.getPathForRow(row) == hoveredPath) icon = IconUtil.scale(icon, null, 2.0f)
                    label.icon = icon
                    return label
                }
            })

            val treeMouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    checkAndHideBalloon(e)
                    val path = processTree.getPathForLocation(e.x, e.y)
                    if (path != hoveredPath) {
                        hoveredPath = path
                        processTree.repaint()
                    }
                }
                override fun mouseClicked(e: MouseEvent) {
                    val path = processTree.getPathForLocation(e.x, e.y)
                    val node = path?.lastPathComponent as? DefaultMutableTreeNode
                    if (node != null && node.isLeaf) {
                        val pkg = node.userObject as? String
                        if (pkg != null) showHint(LogExplanationProvider.getRussianProcessDescription(pkg), e, processTree, false)
                    }
                }
            }
            processTree.addMouseMotionListener(treeMouseListener)
            processTree.addMouseListener(treeMouseListener)

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
                        c.foreground = Color.GRAY
                        c.background = Color(30, 30, 30)
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

            val filterGroup = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
            filterGroup.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 1, Color.GRAY),
                BorderFactory.createEmptyBorder(0, 5, 0, 5)
            )
            
            val btnRed = createFilterToggleButton(Color(180, 0, 0), "E", "Критические ошибки")
            val btnYellow = createFilterToggleButton(Color(250, 200, 0), "W", "Предупреждения")
            val btnGreen = createFilterToggleButton(Color(100, 255, 100), "I", "Нормальные логи")
            val btnBlue = createFilterToggleButton(Color(100, 150, 255), "S", "Системные логи")
            
            filterGroup.add(btnRed)
            filterGroup.add(btnYellow)
            filterGroup.add(btnGreen)
            filterGroup.add(btnBlue)

            val rightToolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 2))
            allLogsButton.addActionListener { rebuildLogTable() }
            autoscrollButton.addActionListener { 
                autoscroll = autoscrollButton.isSelected
                if (autoscroll) scrollTableToBottom()
            }
            clearButton.addActionListener { 
                synchronized(allLogs) { allLogs.clear() }
                rebuildLogTable()
            }
            saveButton.addActionListener { saveLogsToFile() }

            rightToolbar.add(JLabel(AllIcons.Actions.Search))
            rightToolbar.add(searchField)
            rightToolbar.add(allLogsButton)
            rightToolbar.add(filterGroup)
            rightToolbar.add(autoscrollButton)
            rightToolbar.add(clearButton)
            rightToolbar.add(saveButton)
            
            val topPanel = JPanel(BorderLayout())
            topPanel.add(leftToolbar, BorderLayout.WEST)
            topPanel.add(rightToolbar, BorderLayout.EAST)
            panel.add(topPanel, BorderLayout.NORTH)

            val tableMouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    checkAndHideBalloon(e)
                }
                override fun mouseClicked(e: MouseEvent) {
                    val row = logTable.rowAtPoint(e.point)
                    if (row == -1 || logTable.getValueAt(row, 5) == stalledMsg) return

                    // Меню на клик левой кнопкой
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        logTable.setRowSelectionInterval(row, row)
                        val message = logTable.getValueAt(row, 5) as? String ?: ""
                        val tag = logTable.getValueAt(row, 4) as? String ?: ""
                        val pidStr = logTable.getValueAt(row, 1) as? String ?: ""
                        val level = logTable.getValueAt(row, 3) as? String ?: ""
                        
                        val menu = JPopupMenu()
                        
                        // Кнопка Копировать
                        val copyItem = JMenuItem("Копировать сообщение", AllIcons.Actions.Copy)
                        copyItem.addActionListener {
                            val selection = StringSelection(message)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                        }
                        menu.add(copyItem)

                        // Поиск ссылки на код (FileName.kt:123)
                        val sourceMatch = Regex("([\\w]+\\.(?:kt|java)):(\\d+)").find(message)
                        val navigateItem = JMenuItem("Перейти к коду", AllIcons.Actions.EditSource)
                        if (sourceMatch != null) {
                            navigateItem.addActionListener {
                                val fileName = sourceMatch.groupValues[1]
                                val line = sourceMatch.groupValues[2].toIntOrNull() ?: 1
                                navigateToSource(fileName, line)
                            }
                        } else {
                            navigateItem.isEnabled = false
                        }
                        menu.add(navigateItem)
                        
                        // Кнопка Описания
                        val infoItem = JMenuItem("Что это за лог?", AllIcons.Actions.Help)
                        infoItem.addActionListener {
                            val pid = pidStr.toIntOrNull()
                            val pkgName = if (pid != null) pidToPackage[pid] else null
                            val isError = level == "E" || message.contains("Exception", ignoreCase = true)
                            showHint(LogExplanationProvider.getSmartLogExplanation(pkgName, tag, message, level), e, logTable, isError, message)
                        }
                        menu.add(infoItem)

                        menu.show(logTable, e.x, e.y)
                    }
                }
            }
            logTable.addMouseListener(tableMouseListener)
            logTable.addMouseMotionListener(tableMouseListener)

            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), JBScrollPane(logTable))
            splitPane.dividerLocation = 280
            panel.add(splitPane, BorderLayout.CENTER)
            
            processTree.addTreeSelectionListener { 
                val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                if (node != null && node.isLeaf) {
                    val selectedPackage = node.userObject as? String
                    if (selectedPackage != null && selectedPackage != lastSelectedPackage) {
                        rebuildLogTable()
                        lastSelectedPackage = selectedPackage
                    }
                }
            }
            
            deviceComboBox.addActionListener {
                val selected = deviceComboBox.selectedItem as? IDevice
                if (selected != null && (currentDevice == null || selected.serialNumber != currentDevice?.serialNumber)) {
                    currentDevice = selected
                    startLogcatCapture(selected)
                }
            }
        }

        private fun navigateToSource(fileName: String, line: Int) {
            ApplicationManager.getApplication().invokeLater {
                val files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project))
                if (files.isNotEmpty()) {
                    val descriptor = OpenFileDescriptor(project, files[0].virtualFile, line - 1, 0)
                    if (descriptor.canNavigate()) {
                        descriptor.navigate(true)
                    }
                } else {
                    Messages.showInfoMessage("Файл $fileName не найден в проекте.", "Навигация")
                }
            }
        }

        private fun createFilterToggleButton(color: Color, level: String, tip: String): JToggleButton {
            return JToggleButton().apply {
                preferredSize = Dimension(28, 28)
                background = color
                isOpaque = true
                isContentAreaFilled = false
                border = BorderFactory.createLineBorder(Color.GRAY)
                toolTipText = tip
                addActionListener {
                    if (isSelected) filterLevel = level else if (filterLevel == level) filterLevel = null
                    rebuildLogTable()
                }
            }
        }

        private fun checkAndHideBalloon(e: MouseEvent) {
            val point = lastHintPoint
            if (activeBalloon != null && point != null) {
                if (isStickyBalloon) {
                    val currentPoint = e.locationOnScreen
                    val distance = sqrt(((currentPoint.x - point.x) * (currentPoint.x - point.x) + (currentPoint.y - point.y) * (currentPoint.y - point.y)).toDouble())
                    if (distance > 30) hideActiveBalloon()
                } else {
                    hideActiveBalloon()
                }
            }
        }

        private fun hideActiveBalloon() {
            activeBalloon?.hide()
            activeBalloon = null
            lastHintPoint = null
        }

        private fun saveLogsToFile() {
            val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val selectedPackage = node?.userObject as? String ?: "all_processes"
            val fileName = "logs_${selectedPackage.replace(".", "_")}_${System.currentTimeMillis()}.txt"
            val descriptor = FileSaverDescriptor("Сохранить логи", "Выберите место", "txt")
            val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val fileWrapper = saveDialog.save(LocalFileSystem.getInstance().findFileByPath(project.basePath ?: ""), fileName)
            if (fileWrapper != null) {
                try {
                    val content = StringBuilder()
                    for (row in 0 until logTableModel.rowCount) {
                        content.append((0 until logTable.columnCount).joinToString(" ") { logTable.getValueAt(row, it).toString() }).append("\n")
                    }
                    fileWrapper.file.writeText(content.toString())
                    Messages.showInfoMessage("Сохранено в: ${fileWrapper.file.absolutePath}", "Успех")
                } catch (e: Exception) {
                    Messages.showErrorDialog("Ошибка: ${e.message}", "Ошибка")
                }
            }
        }

        private fun showHint(text: String, e: MouseEvent, component: Component, isSticky: Boolean, originalMessage: String = "") {
            hideActiveBalloon()
            isStickyBalloon = isSticky
            lastHintPoint = e.locationOnScreen
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), object : HyperlinkListener {
                    override fun hyperlinkUpdate(event: HyperlinkEvent) {
                        if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "show_stacktrace") {
                            Messages.showInfoMessage(LogExplanationProvider.getDetailedStackTraceExplanation(originalMessage), "Описание ошибки")
                        }
                    }
                })
                .setFadeoutTime(0)
                .setHideOnClickOutside(true)
                .createBalloon()
            balloon.show(RelativePoint(component, e.point), Balloon.Position.above)
            activeBalloon = balloon
        }

        private fun parseLogLine(line: String): Array<String> {
            val parts = line.trim().split(Regex("\\s+"), 6)
            if (parts.size < 6) return arrayOf("", "", "", "", "", line)
            val rest = parts[5].split(":", limit = 2)
            return arrayOf(parts[1], parts[2], parts[3], parts[4], rest.getOrNull(0) ?: "", rest.getOrNull(1)?.trim() ?: "")
        }

        private fun refreshProcesses() {
            val adb = AndroidDebugBridge.getBridge()
            val devices = adb?.devices?.toList() ?: emptyList()
            
            if (devices.isEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    pidToPackage.clear()
                    lastKnownPackages = emptySet()
                    rootNode.removeAllChildren()
                    treeModel.reload()
                    allLogs.clear()
                    logTableModel.rowCount = 0
                }
                return
            }

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
                    override fun isCancelled() = false
                }
                try { device.executeShellCommand("ps -A", processReceiver) } catch (e: Exception) {}
                val allKnown = pidToPackage.values.toSet().filter { it != "Unknown" }.toSet()
                if (allKnown == lastKnownPackages && rootNode.childCount > 0) return@executeOnPooledThread
                lastKnownPackages = allKnown
                ApplicationManager.getApplication().invokeLater {
                    val selectedValue = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String
                    rootNode.removeAllChildren()
                    val sortedList = allKnown.toList().sorted()
                    addGroupToTree("⭐ МОЙ ПРОЕКТ", sortedList.filter { it.contains("example") || it.contains("my") })
                    addGroupToTree("👤 ПРИЛОЖЕНИЯ", sortedList.filter { !it.contains("android") && !it.contains("google") && !it.contains("example") })
                    addGroupToTree("☁️ GOOGLE", sortedList.filter { it.contains("google") })
                    addGroupToTree("🖼️ ИНТЕРФЕЙС", sortedList.filter { it.contains("systemui") || it.contains("launcher") })
                    addGroupToTree("🛠️ СИСТЕМНЫЕ", sortedList.filter { it.contains("android") && !it.contains("systemui") })
                    treeModel.reload()
                    // Дерево всегда свернуто ( reload это делает автоматически)
                    if (selectedValue != null) findAndSelectNode(rootNode, selectedValue)
                }
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
                if (node.userObject == target) {
                    processTree.selectionPath = TreePath(node.path)
                    return
                }
            }
        }

        private fun startLogcatCapture(device: IDevice) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    synchronized(allLogs) { allLogs.clear() }
                    device.executeShellCommand("logcat -c", object : MultiLineReceiver() { override fun processNewLines(lines: Array<out String>) {}; override fun isCancelled() = false })
                    device.executeShellCommand("logcat -v threadtime", object : MultiLineReceiver() {
                        override fun processNewLines(lines: Array<out String>) {
                            lines.forEach { line ->
                                if (line.length > 20) {
                                    val cleanLine = if (line.length > 500) line.substring(0, 500) else line
                                    synchronized(allLogs) { allLogs.add(cleanLine); if (allLogs.size > 5000) allLogs.removeAt(0) }
                                    ApplicationManager.getApplication().invokeLater { 
                                        val selectedPackage = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String
                                        if (selectedPackage != null && isLineRelatedToPackage(line, selectedPackage) && isLinePassingFilter(line)) {
                                            if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) logTableModel.removeRow(0)
                                            logTableModel.addRow(parseLogLine(cleanLine))
                                            if (logTableModel.rowCount > 1000) logTableModel.removeRow(0)
                                            if (autoscroll) scrollTableToBottom()
                                        }
                                    }
                                }
                            }
                        }
                        override fun isCancelled() = (currentDevice != null && device.serialNumber != currentDevice?.serialNumber)
                    }, 0)
                } catch (e: Exception) {}
            }
        }

        private fun isLinePassingFilter(line: String): Boolean {
            val query = searchField.text.trim()
            if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false
            if (allLogsButton.isSelected) return true
            val parts = parseLogLine(line)
            val level = parts[3]
            return when (filterLevel) {
                "E" -> level == "E"
                "W" -> level == "W"
                "I" -> level == "I" || level == "V" || level == "D"
                "S" -> {
                    val pid = parts[1].toIntOrNull()
                    val pkg = if (pid != null) pidToPackage[pid] else null
                    pkg != null && (pkg.contains("android") || pkg.contains("system"))
                }
                else -> true
            }
        }

        private fun isLineRelatedToPackage(line: String, packageName: String): Boolean {
            val pidFromLine = line.trim().split(Regex("\\s+")).getOrNull(2)?.toIntOrNull()
            return pidFromLine != null && pidToPackage[pidFromLine] == packageName || line.contains(packageName)
        }

        private fun rebuildLogTable() {
            val selectedPackage = (processTree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? String ?: return
            logTableModel.rowCount = 0
            val snapshot = synchronized(allLogs) { allLogs.toList() }
            val filtered = snapshot.filter { isLineRelatedToPackage(it, selectedPackage) && isLinePassingFilter(it) }
            if (filtered.isEmpty()) logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg))
            else filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }
            if (autoscroll) scrollTableToBottom()
        }

        private fun scrollTableToBottom() {
            if (logTableModel.rowCount > 0) logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true))
        }

        private fun refreshDevices() {
            val adb = AndroidDebugBridge.getBridge()
            val devices = adb?.devices?.toList() ?: emptyList()
            ApplicationManager.getApplication().invokeLater {
                val current = deviceComboBox.selectedItem as? IDevice
                if (deviceComboBox.model.size != devices.size) {
                    deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray())
                    if (current != null) deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber }
                }
                if (devices.isEmpty()) {
                    pidToPackage.clear()
                    lastKnownPackages = emptySet()
                    rootNode.removeAllChildren()
                    treeModel.reload()
                    allLogs.clear()
                    logTableModel.rowCount = 0
                }
            }
        }

        fun getContent() = panel
    }
}

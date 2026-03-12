package com.example.logkatplugin

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
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
        private val packageHasError = ConcurrentHashMap<String, Boolean>()
        
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
        private val crashButton = JButton("CRASH", AllIcons.Actions.Suspend)
        private val clearButton = JButton(AllIcons.Actions.GC)
        private val saveButton = JButton(AllIcons.Actions.MenuSaveall)

        init {
            setupUI()
            ToolTipManager.sharedInstance().initialDelay = 100 
            
            val timer = Timer(3000) { 
                refreshDevices()
                refreshProcesses() 
            }
            timer.start()
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
                        textValue.startsWith("PID:") -> AllIcons.Debugger.Console
                        else -> AllIcons.Nodes.Package
                    }
                    
                    if (tree?.getPathForRow(row) == hoveredPath) {
                        icon = IconUtil.scale(icon, null, 2.0f)
                    }
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
                        if (pkg != null) showHint(getRussianProcessDescription(pkg), e, processTree, false)
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
                        else -> { c.foreground = Color(100, 255, 100); c.background = Color(55, 55, 55) }
                    }
                    if (isSelected) c.background = c.background.darker()
                    return c
                }
            })

            val tableMouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    checkAndHideBalloon(e)
                }
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        val row = logTable.rowAtPoint(e.point)
                        if (row != -1 && logTable.getValueAt(row, 5) != stalledMsg) {
                            logTable.setRowSelectionInterval(row, row)
                            val menu = JPopupMenu()
                            val copyItem = JMenuItem("Копировать строку", AllIcons.Actions.Copy)
                            copyItem.addActionListener {
                                val rowValues = (0 until logTable.columnCount).map { logTable.getValueAt(row, it).toString() }
                                val value = rowValues.joinToString(" ")
                                val selection = StringSelection(value)
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                            }
                            menu.add(copyItem)
                            menu.show(logTable, e.x, e.y)
                        }
                    }
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        val row = logTable.rowAtPoint(e.point)
                        if (row != -1 && logTable.getValueAt(row, 5) != stalledMsg) {
                            val pidStr = logTable.getValueAt(row, 1) as? String ?: ""
                            val level = logTable.getValueAt(row, 3) as? String ?: ""
                            val tag = logTable.getValueAt(row, 4) as? String ?: ""
                            val message = logTable.getValueAt(row, 5) as? String ?: ""
                            val pid = pidStr.toIntOrNull()
                            val pkgName = if (pid != null) pidToPackage[pid] else null
                            
                            val isError = level == "E" || message.contains("Exception", ignoreCase = true)
                            val hintText = getSmartLogExplanation(pkgName, tag, message, level)
                            showHint(hintText, e, logTable, isError, message)
                        }
                    }
                }
            }
            logTable.addMouseListener(tableMouseListener)
            logTable.addMouseMotionListener(tableMouseListener)

            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
            toolbar.add(JLabel("Dev:"))
            toolbar.add(deviceComboBox)
            val autoscrollBox = JCheckBox("Auto", true).apply { 
                addActionListener { 
                    autoscroll = isSelected 
                    if (autoscroll) scrollTableToBottom()
                } 
            }
            toolbar.add(autoscrollBox)
            toolbar.add(crashButton)
            crashButton.addActionListener { jumpToLatestError() }
            
            clearButton.toolTipText = "Очистить все логи"
            clearButton.addActionListener { 
                synchronized(allLogs) { allLogs.clear() }
                rebuildLogTable()
            }
            toolbar.add(clearButton)

            saveButton.toolTipText = "Сохранить логи в файл"
            saveButton.addActionListener { saveLogsToFile() }
            toolbar.add(saveButton)
            
            panel.add(toolbar, BorderLayout.NORTH)

            val scrollPane = JBScrollPane(logTable)
            scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            
            val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(processTree), scrollPane)
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
                    updateCrashButtonState()
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

        private fun saveLogsToFile() {
            val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val selectedPackage = node?.userObject as? String ?: "all_processes"
            val fileName = "logs_${selectedPackage.replace(".", "_")}_${System.currentTimeMillis()}.txt"
            
            val descriptor = FileSaverDescriptor("Сохранить логи", "Выберите место для сохранения файла", "txt")
            val baseDir = project.basePath?.let { java.io.File(it) }
            val saveDialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            val baseDirVirtualFile = baseDir?.let { LocalFileSystem.getInstance().findFileByIoFile(it) }
            val fileWrapper = saveDialog.save(baseDirVirtualFile, fileName)
            
            if (fileWrapper != null) {
                try {
                    val content = StringBuilder()
                    for (row in 0 until logTableModel.rowCount) {
                        val rowData = (0 until logTable.columnCount).joinToString(" ") { logTable.getValueAt(row, it).toString() }
                        content.append(rowData).append("\n")
                    }
                    fileWrapper.file.writeText(content.toString())
                    Messages.showInfoMessage("Логи успешно сохранены в:\n${fileWrapper.file.absolutePath}", "Успех")
                } catch (e: Exception) {
                    Messages.showErrorDialog("Ошибка при сохранении файла: ${e.message}", "Ошибка")
                }
            }
        }

        private fun showHint(text: String, e: MouseEvent, component: Component, isSticky: Boolean, originalMessage: String = "") {
            hideActiveBalloon()
            isStickyBalloon = isSticky
            lastHintPoint = e.locationOnScreen
            
            val listener = object : HyperlinkListener {
                override fun hyperlinkUpdate(event: HyperlinkEvent) {
                    if (event.eventType == HyperlinkEvent.EventType.ACTIVATED && event.description == "show_stacktrace") {
                        showDetailedStackTraceDialog(originalMessage)
                    }
                }
            }
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), listener)
                .setFadeoutTime(0)
                .setHideOnClickOutside(true)
                .setClickHandler({ hideActiveBalloon() }, true)
                .createBalloon()
            
            balloon.show(RelativePoint(component, e.point), Balloon.Position.above)
            activeBalloon = balloon
        }

        private fun showDetailedStackTraceDialog(message: String) {
            val explanation = getDetailedStackTraceExplanation(message)
            Messages.showInfoMessage(explanation, "Описание ошибки")
        }

        private fun getDetailedStackTraceExplanation(message: String): String {
            return when {
                message.contains("NullPointerException") -> "NullPointerException: Попытка обратиться к объекту, который равен null.\n\nСовет: Проверьте, инициализирована ли переменная перед использованием."
                message.contains("IndexOutOfBoundsException") -> "IndexOutOfBoundsException: Обращение к несуществующему индексу в массиве или списке.\n\nСовет: Проверьте размер коллекции перед обращением."
                message.contains("NetworkOnMainThreadException") -> "NetworkOnMainThreadException: Попытка выполнить сетевой запрос в главном UI-потоке.\n\nСовет: Используйте корутины или фоновые потоки для работы с сетью."
                message.contains("OutOfMemoryError") -> "OutOfMemoryError: Приложению не хватило оперативной памяти (RAM).\n\nСовет: Оптимизируйте работу с изображениями и очищайте кэш."
                message.contains("ANR") -> "ANR (Application Not Responding): Главный поток заблокирован более чем на 5 секунд.\n\nСовет: Вынесите тяжелые вычисления из Main Thread."
                message.contains("ClassCastException") -> "ClassCastException: Неверное приведение типов объектов.\n\nСовет: Проверьте логику работы с интерфейсами и наследованием."
                else -> "Подробности ошибки:\n$message\n\nСовет: Проанализируйте StackTrace для поиска номера строки в вашем коде."
            }
        }

        private fun hideActiveBalloon() {
            activeBalloon?.hide()
            activeBalloon = null
            lastHintPoint = null
        }

        private fun parseLogLine(line: String): Array<String> {
            val parts = line.trim().split(Regex("\\s+"), 6)
            if (parts.size < 6) return arrayOf("", "", "", "", "", line)
            val time = parts[1]
            val pid = parts[2]
            val tid = parts[3]
            val level = parts[4]
            val rest = parts[5].split(":", limit = 2)
            val tag = rest.getOrNull(0) ?: ""
            val msg = rest.getOrNull(1)?.trim() ?: ""
            return arrayOf(time, pid, tid, level, tag, msg)
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
                                val name = parts.last()
                                if (pid != null && (name.contains(".") || name.length > 2)) {
                                    pidToPackage.putIfAbsent(pid, name)
                                }
                            }
                        }
                    }
                    override fun isCancelled() = false
                }

                try {
                    device.executeShellCommand("ps -A", processReceiver)
                    if (pidToPackage.size < 5) {
                        device.executeShellCommand("ps", processReceiver)
                    }
                } catch (e: Exception) {}

                val allKnown = (pidToPackage.values.toSet() + (lastSelectedPackage?.let { setOf(it) } ?: emptySet())).filter { it != "Unknown" }.toSet()
                
                if (allKnown == lastKnownPackages && rootNode.childCount > 0) return@executeOnPooledThread
                lastKnownPackages = allKnown

                ApplicationManager.getApplication().invokeLater {
                    val selectedNode = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                    val selectedValue = selectedNode?.userObject as? String
                    
                    val expandedGroups = mutableSetOf<String>()
                    for (i in 0 until rootNode.childCount) {
                        val groupNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
                        if (processTree.isExpanded(TreePath(groupNode.path))) expandedGroups.add(groupNode.userObject as String)
                    }
                    
                    val isFirstRun = rootNode.childCount == 0
                    rootNode.removeAllChildren()
                    
                    val sortedList = allKnown.toList().sorted()
                    addGroupToTree("⭐ МОЙ ПРОЕКТ", sortedList.filter { it.contains("example") || it.contains("my") })
                    addGroupToTree("👤 ПРИЛОЖЕНИЯ", sortedList.filter { !it.contains("android") && !it.contains("google") && !it.contains("example") && !it.startsWith("PID:") })
                    addGroupToTree("☁️ GOOGLE", sortedList.filter { it.contains("google") })
                    addGroupToTree("🖼️ ИНТЕРФЕЙС", sortedList.filter { it.contains("systemui") || it.contains("launcher") })
                    addGroupToTree("🛠️ СИСТЕМНЫЕ", sortedList.filter { it.contains("android") && !it.contains("systemui") && !it.contains("google") })
                    addGroupToTree("📟 ПРОЦЕССЫ", sortedList.filter { it.startsWith("PID:") })
                    
                    treeModel.reload()
                    
                    for (i in 0 until rootNode.childCount) {
                        val groupNode = rootNode.getChildAt(i) as DefaultMutableTreeNode
                        val title = groupNode.userObject as String
                        if (expandedGroups.contains(title) || (isFirstRun && (title.contains("ПРОЕКТ") || title.contains("ПРИЛОЖЕНИЯ")))) {
                            processTree.expandPath(TreePath(groupNode.path))
                        }
                    }
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
                    val path = TreePath(node.path)
                    processTree.selectionPath = path
                    processTree.makeVisible(path)
                    return
                }
            }
        }

        private fun startLogcatCapture(device: IDevice) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    synchronized(allLogs) { allLogs.clear() }
                    packageHasError.clear()
                    device.executeShellCommand("logcat -c", object : MultiLineReceiver() {
                        override fun processNewLines(lines: Array<out String>) {}
                        override fun isCancelled() = false
                    })
                    
                    device.executeShellCommand("logcat -v threadtime", object : MultiLineReceiver() {
                        override fun processNewLines(lines: Array<out String>) {
                            lines.forEach { line ->
                                if (line.isNotBlank() && line.length > 20) {
                                    val cleanLine = if (line.length > 500) line.substring(0, 500) else line
                                    synchronized(allLogs) {
                                        allLogs.add(cleanLine)
                                        if (allLogs.size > 5000) allLogs.removeAt(0)
                                    }
                                    
                                    if (line.contains(" E/") || line.contains("Exception", ignoreCase = true)) {
                                        val pkg = getPackageFromLine(line)
                                        if (pkg != "Unknown") packageHasError[pkg] = true
                                    }

                                    val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
                                    val selectedPackage = node?.userObject as? String
                                    if (selectedPackage != null && isLineRelatedToPackage(line, selectedPackage)) {
                                        ApplicationManager.getApplication().invokeLater { 
                                            if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) {
                                                logTableModel.removeRow(0)
                                            }
                                            logTableModel.addRow(parseLogLine(cleanLine))
                                            if (logTableModel.rowCount > 1000) logTableModel.removeRow(0)
                                            if (autoscroll) scrollTableToBottom()
                                        }
                                    }
                                }
                            }
                            ApplicationManager.getApplication().invokeLater { updateCrashButtonState() }
                        }
                        override fun isCancelled() = (currentDevice != null && device.serialNumber != currentDevice?.serialNumber)
                    }, 0)
                } catch (e: Exception) {}
            }
        }

        private fun isLineRelatedToPackage(line: String, packageName: String): Boolean {
            val pidFromLine = line.trim().split(Regex("\\s+")).getOrNull(2)?.toIntOrNull()
            if (pidFromLine != null) {
                val pkgAtPid = pidToPackage[pidFromLine]
                if (pkgAtPid == packageName) return true
                if (packageName.startsWith("PID:") && packageName.contains(pidFromLine.toString())) return true
            }
            return line.contains(packageName) || (pidFromLine != null && pidFromLine < 1000 && (line.contains("InputDispatcher") || line.contains("WindowManager")))
        }

        private fun getPackageFromLine(line: String): String {
            val parts = line.trim().split(Regex("\\s+"))
            val pid = parts.getOrNull(2)?.toIntOrNull() ?: return "Unknown"
            return pidToPackage[pid] ?: "PID: $pid"
        }

        private fun rebuildLogTable() {
            val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val selectedPackage = node?.userObject as? String ?: return
            
            logTableModel.rowCount = 0
            val snapshot = synchronized(allLogs) { allLogs.toList() }
            val filtered = snapshot.filter { isLineRelatedToPackage(it, selectedPackage) }
            
            if (filtered.isEmpty()) {
                logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg))
            } else {
                filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }
            }
            
            if (autoscroll) scrollTableToBottom()
        }

        private fun scrollTableToBottom() {
            if (logTableModel.rowCount > 0) {
                logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true))
            }
        }

        private fun refreshDevices() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val adb = AndroidDebugBridge.getBridge() ?: return@executeOnPooledThread
                val devices = adb.devices.toList()
                ApplicationManager.getApplication().invokeLater {
                    val current = deviceComboBox.selectedItem as? IDevice
                    val currentModel = deviceComboBox.model
                    val currentSerials = (0 until currentModel.size).map { currentModel.getElementAt(it).serialNumber }
                    val newSerials = devices.map { it.serialNumber }

                    if (currentSerials != newSerials) {
                        deviceComboBox.model = DefaultComboBoxModel(devices.toTypedArray())
                        if (current != null && devices.any { it.serialNumber == current.serialNumber }) {
                            deviceComboBox.selectedItem = devices.find { it.serialNumber == current.serialNumber }
                        } else if (devices.isNotEmpty()) {
                            deviceComboBox.selectedIndex = 0
                        }
                    }
                }
            }
        }

        private fun getRussianProcessDescription(pkg: String): String {
            val description = when {
                pkg == "init" -> "<b>Прародитель (Init).</b> Первый процесс в системе, запускаемый ядром. Порождает все остальные системные демоны."
                pkg == "keystore2" || pkg == "keystore" -> "<b>Хранилище ключей (Keystore).</b> Защищенное хранилище криптографических ключей, паролей и сертификатов."
                pkg == "gatekeeperd" -> "<b>Сторож экрана (Gatekeeper).</b> Проверяет PIN-коды, пароли и графические ключи при разблокировке."
                pkg == "statsd" -> "<b>Сборщик статистики (Statsd).</b> Собирает анонимную диагностику и метрики использования системы."
                pkg == "dumpstate" -> "<b>Сборщик отчета (Dumpstate).</b> Собирает все логи и информацию при создании bug-репорта."
                pkg == "tombstoned" -> "<b>Регистратор падений (Tombstoned).</b> Записывает дампы памяти (tombstone) при падении нативных процессов."
                pkg == "incidentd" -> "<b>Сборщик инцидентов.</b> Собирает структурированные отчеты о проблемах для отправки разработчикам."
                pkg == "apexd" -> "<b>Менеджер APEX.</b> Управляет модульными системными компонентами (APEX-пакетами) для обновлений Android."
                pkg == "logd" -> "<b>Демон логирования (Logd).</b> Принимает и буферизирует все логи от всех процессов. Именно от него читает Logcat."
                pkg == "servicemanager" -> "<b>Диспетчер служб.</b> Реестр всех Binder-сервисов. Помогает процессам находить друг друга."
                pkg == "hwservicemanager" -> "<b>Диспетчер аппаратных служб.</b> Реестр для HAL-сервисов (аппаратного уровня)."
                pkg == "cameraserver" -> "<b>Сервер камеры.</b> Управляет доступом к камере, обработкой изображений и передачей данных приложениям."
                pkg == "drmserver" -> "<b>Защита контента (DRM).</b> Управляет лицензиями на защищенный медиаконтент (Netflix, etc)."
                pkg == "mediaextractor" -> "<b>Извлечение медиа.</b> Разбирает медиафайлы на дорожки (аудио, видео, субтитры)."
                pkg == "media.codec" -> "<b>Аппаратный кодек.</b> Процесс для аппаратного кодирования/декодирования видео."
                pkg == "media.swcodec" -> "<b>Программный кодек.</b> Процесс для программного кодирования/декодирования видео (если нет аппаратного)."
                pkg == "netd" -> "<b>Сетевой демон (Netd).</b> Управляет сетевыми интерфейсами, правилами брандмауэра и VPN."
                pkg == "mdnsd" -> "<b>Сетевое обнаружение (mDNS).</b> Позволяет находить устройства в локальной сети (Chromecast, принтеры)."
                pkg == "clatd" -> "<b>Переход на IPv6 (CLAT).</b> Помогает приложениям, работающим только с IPv4, работать в IPv6-сетях."
                pkg == "wificond" -> "<b>Демон Wi-Fi (Wificond).</b> Низкоуровневое взаимодействие с драйвером Wi-Fi."
                pkg == "hostapd" -> "<b>Точка доступа.</b> Запускает режим модема (Wi-Fi точки доступа)."
                pkg == "time_daemon" -> "<b>Демон времени.</b> Синхронизирует системное время с аппаратными часами и сетью."
                pkg == "thermald" -> "<b>Термальный демон.</b> Следит за температурой и снижает частоты при перегреве."
                pkg == "perfd" -> "<b>Демон производительности.</b> Оптимизирует частоты CPU/GPU для плавности работы."
                pkg == "storaged" -> "<b>Монитор хранилища.</b> Следит за скоростью работы и состоянием внутренней памяти."
                pkg == "iorapd" -> "<b>Предиктор ввода/вывода.</b> Предсказывает, какие файлы понадобятся приложению, и подгружает их заранее для ускорения запуска."
                pkg == "webview_zygote" -> "<b>Процесс-шаблон WebView.</b> Отдельный Zygote для WebView, чтобы изолировать и ускорить рендеринг веб-страниц."
                pkg.contains("webview") -> "<b>WebView.</b> Компонент для отображения веб-страниц внутри приложений."
                pkg == "statscompanion" -> "<b>Спутник статистики.</b> Помогает statsd обрабатывать сложные метрики."
                pkg == "networkstack" -> "<b>Сетевой стек.</b> Управляет IP-адресацией, DHCP и DNS-запросами."
                pkg == "ipacm" -> "<b>Менеджер IP-адресов.</b> Распределяет IP-адреса при использовании модема."
                pkg == "system_server" -> "<b>Ядро системы (System Server).</b> Управляет всеми окнами, питанием, уведомлениями, датчиками и безопасностью."
                pkg == "surfaceflinger" -> "<b>Графический композитор (SurfaceFlinger).</b> Собирает кадры от всех приложений и выводит их на экран через GPU."
                pkg == "audioserver" -> "<b>Звуковая служба (AudioServer).</b> Управляет всеми аудио-потоками."
                pkg == "mediaserver" -> "<b>Медиа-движок (MediaServer).</b> Работа камеры, видео и кодеков."
                pkg == "zygote" || pkg == "zygote64" -> "<b>Материнский процесс (Zygote).</b> Процесс-шаблон для запуска приложений."
                pkg.contains("systemui") -> "<b>Интерфейс системы (SystemUI).</b> Шторка, кнопки навигации и часы."
                pkg.contains("example") -> "<b>Твой проект.</b> Твое приложение, которое ты сейчас отлаживаешь."
                pkg.contains("google") -> "<b>Службы Google.</b> Play Store, Карты и синхронизация (GMS)."
                else -> "<b>Процесс приложения: $pkg.</b> Работает в своей изолированной среде (Sandbox)."
            }
            return "<html><body style='width: 300px;'>$description</body></html>"
        }

        private fun getProcessColor(pkgName: String?): String {
            return when {
                pkgName == null -> "gray"
                pkgName in listOf("system_server", "surfaceflinger", "zygote", "init") -> "purple"
                pkgName in listOf("audioserver", "cameraserver", "mediaserver") -> "blue"
                pkgName in listOf("netd", "wificond", "wpa_supplicant", "networkstack") -> "teal"
                pkgName in listOf("lmkd", "storaged", "keystore", "keystore2") -> "orange"
                pkgName.contains("google") -> "red"
                pkgName.contains("android") -> "green"
                pkgName.contains("example") -> "gold"
                else -> "gray"
            }
        }

        private fun getSmartLogExplanation(pkgName: String?, tag: String, message: String, level: String): String {
            val pColor = getProcessColor(pkgName)
            val processInfo = if (pkgName != null) "<b>Отправитель:</b> <font color='$pColor'>$pkgName</font><br>" else ""
            val rawDesc = if (pkgName != null) getRussianProcessDescription(pkgName) else ""
            val cleanDesc = rawDesc.replace("<html><body style='width: 300px;'>", "").replace("</body></html>", "")
            val processDesc = if (cleanDesc.isNotEmpty()) "<i>$cleanDesc</i><br><hr>" else ""
            
            val errorLink = if (level == "E" || message.contains("Exception", ignoreCase = true)) {
                "<br><br><a href='show_stacktrace'>[ОПИСАНИЕ ОШИБКИ]</a>"
            } else ""

            val actionDesc = when {
                // Системные сервисы
                tag == "SystemServer" -> "<b>Запуск системы:</b> Инициализация всех системных служб при включении телефона."
                tag == "Zygote" -> "<b>Рождение процесса:</b> Zygote создает новый процесс для приложения методом форка."
                tag == "ZygoteInit" -> "<b>Инициализация:</b> Загрузка базовых классов Java в новый процесс."

                // Память и производительность
                tag == "MemoryPressure" || message.contains("low memory") -> "<font color='orange'><b>Нехватка памяти:</b> Система испытывает дефицит оперативной памяти. Фоновые приложения будут закрыты.</font>"
                tag == "lmkd" -> "<b>Убийца процессов:</b> LMKD анализирует давление памяти и выбирает кандидатов на закрытие."
                tag == "perfprofiler" -> "<b>Профилировщик:</b> Сбор данных о производительности для Android Studio."

                // Сеть
                tag == "TrafficController" -> "<b>Контроль трафика:</b> Управление сетевыми очередями и приоритетами пакетов."
                tag == "NetworkPolicyManager" -> "<b>Политики сети:</b> Ограничение фонового трафика, режим экономии трафика."
                tag == "Vpn" -> "<b>VPN-соединение:</b> Установка или разрыв защищенного туннеля."
                tag == "Ethernet" -> "<b>Проводная сеть:</b> Подключение через USB-сеть или Ethernet-адаптер."

                // Bluetooth
                tag == "BluetoothMapService" -> "<b>Bluetooth MAP:</b> Доступ к сообщениям (SMS) через Bluetooth (например, в машине)."
                tag == "BluetoothPbap" -> "<b>Bluetooth PBAP:</b> Доступ к контактам через Bluetooth."
                tag == "BluetoothA2dp" -> "<b>Bluetooth A2DP:</b> Передача качественного звука на наушники или колонку."
                tag == "BluetoothAvrcp" -> "<b>Bluetooth AVRCP:</b> Управление воспроизведением (пауза/трек) с гарнитуры."

                // USB
                tag == "UsbHostManager" -> "<b>USB-host:</b> Подключение внешних устройств (флешки, мыши) к телефону через OTG."
                tag == "UsbAlsaManager" -> "<b>USB-звук:</b> Управление звуковыми USB-устройствами (внешние ЦАП)."
                tag == "MtpServer" -> "<b>MTP-сервер:</b> Передача файлов при подключении к компьютеру в режиме MTP."

                // Экран и графика
                tag == "DisplayManager" -> "<b>Управление дисплеем:</b> Подключение внешних мониторов, изменение разрешения."
                tag == "DisplayPowerController" -> "<b>Питание экрана:</b> Автоматическая регулировка яркости, таймаут выключения."
                tag == "HardwareRenderer" -> "<b>Аппаратное ускорение:</b> Отрисовка интерфейса с использованием GPU."
                tag == "OpenGLRenderer" -> "<b>OpenGL-рендерер:</b> Команды отрисовки, отправляемые на видеокарту."
                tag == "GPUAUX" || message.contains("GuiExtAux") -> "<b>Вспомогательная графика (GPU AUX):</b> Ошибка обращения к нативному буферу Android (ANB)."

                // Приложения и компоненты
                tag == "Launcher" -> "<b>Рабочий стол:</b> Процесс лаунчера управляет иконками, виджетами и папками."
                tag == "RecentsAnimation" -> "<b>Анимация недавних:</b> Отрисовка анимации при открытии меню многозадачности."
                tag == "PipManager" -> "<b>Картинка-в-картинке:</b> Управление режимом PiP для видео."
                tag == "SplitScreen" -> "<b>Разделенный экран:</b> Режим работы двух приложений одновременно."

                // Система и безопасность
                tag == "SELinux" || message.contains("avc: denied") -> "<font color='orange'><b>Безопасность SELinux:</b> Заблокировано действие, нарушающее политику безопасности.</font>"
                tag == "Audit" -> "<b>Аудит безопасности:</b> Запись событий безопасности в системный журнал."
                tag == "PermController" -> "<b>Контроль разрешений:</b> Проверка и управление правами приложений (камера, контакты)."
                tag == "AppPredictionService" -> "<b>Предиктор приложений:</b> Предсказывает, какое приложение вы откроете следующим."

                // NFC и бесконтактные технологии
                tag == "NfcService" -> "<b>NFC-сервис:</b> Управление бесконтактной связью для оплаты и меток."
                tag == "SecureElement" -> "<b>Защищенный элемент:</b> Взаимодействие с SIM-картой для оплаты."

                // Телефония
                tag == "RILJ" -> "<b>Radio Interface Layer:</b> Мост между Android и модемом для звонков и данных."
                tag == "GsmCdmaPhone" -> "<b>Телефонный стек:</b> Управление состоянием мобильной сети."
                tag == "MccTracker" -> "<b>Код страны/оператора:</b> Определение региона для настройки времени и данных."

                // Системные утилиты
                tag == "BackupManagerService" -> "<b>Резервное копирование:</b> Сохранение данных приложений в облако."
                tag == "RestoreSession" -> "<b>Восстановление:</b> Восстановление данных при первом запуске."
                tag == "SearchManager" -> "<b>Поиск:</b> Глобальный поиск по телефону и приложениям."

                // Потоки выполнения
                tag.startsWith("Binder:") -> "<b>Binder-поток:</b> Служебный поток для межпроцессного взаимодействия."
                tag == "FinalizerDaemon" || tag == "FinalizerWatchdogDaemon" -> "<b>Сборщик мусора:</b> Фоновые потоки для очистки памяти."
                tag == "HeapTaskDaemon" -> "<b>Управление кучей:</b> Оптимизация памяти в Dalvik/ART."

                // Конкретные сообщения
                message.contains("skip frames") -> "<font color='orange'><b>Пропуск кадров:</b> Интерфейс работает с задержками. Главный поток перегружен.</font>"
                message.contains("Slow Operation") -> "<b>Медленная операция:</b> Выполнение задачи заняло слишком много времени."
                message.contains("WaitForGcToComplete") -> "<b>Ожидание GC:</b> Приложение ждет завершения сборки мусора."
                message.contains("Lock contention") -> "<font color='orange'><b>Конкуренция блокировок:</b> Потоки конфликтуют за доступ к ресурсу.</font>"
                message.contains("Thread blocked") -> "<font color='red'><b>Блокировка потока:</b> Поток остановлен. Возможная причина зависания (ANR).</font>"
                message.contains("dex2oat") || message.contains("Compilation") -> "<b>Компиляция:</b> Оптимизация кода приложения в фоне."
                message.contains("Watchdog") -> "<b>Сторожевой таймер:</b> Проверка зависания системных потоков."
                message.contains("Native crash") -> "<font color='red'><b>Падение нативного кода:</b> Ошибка в C++ компоненте.</font>"
                message.contains("DEBUG") && message.contains("pid") -> "<b>Отладчик падений:</b> Запись информации об упавшем процессе."
                message.contains("ANR") -> "<font color='red'><b>Зависание (ANR):</b> Приложение перестало отвечать.</font>"
                
                else -> "<b>Событие системы:</b> Сообщение от компонента '$tag'. Сообщает о завершении внутренней операции."
            }

            return "<html><body style='width: 350px;'>$processInfo$processDesc$actionDesc$errorLink</body></html>"
        }

        private fun updateCrashButtonState() {
            val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val selectedPackage = node?.userObject as? String
            crashButton.isEnabled = selectedPackage != null && packageHasError[selectedPackage] == true
            crashButton.foreground = if (crashButton.isEnabled) Color.RED else Color.GRAY
        }

        private fun jumpToLatestError() {
            for (i in logTableModel.rowCount - 1 downTo 0) {
                if (logTableModel.getValueAt(i, 3) == "E") {
                    logTable.setRowSelectionInterval(i, i)
                    logTable.scrollRectToVisible(logTable.getCellRect(i, 0, true))
                    break
                }
            }
        }

        fun getContent() = panel
    }
}

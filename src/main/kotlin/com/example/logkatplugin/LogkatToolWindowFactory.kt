package com.example.logkatplugin

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
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
        private val pidToPpid = ConcurrentHashMap<Int, Int>()
        private val packageHasError = ConcurrentHashMap<String, Boolean>()
        
        private var currentDevice: IDevice? = null
        private var autoscroll = true
        private var hoveredPath: TreePath? = null
        private var lastSelectedPackage: String? = null
        private var lastKnownPackages: Set<String> = emptySet()
        
        private var activeBalloon: Balloon? = null
        private val STALLED_MSG = "Простаивает / нет логов"

        private val deviceComboBox = ComboBox<IDevice>()
        private val crashButton = JButton("CRASH", AllIcons.Actions.Suspend)
        private val clearButton = JButton(AllIcons.Actions.GC)

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
                        label.icon = if (expanded) AllIcons.Nodes.Folder else AllIcons.Nodes.Folder
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
                    label.toolTipText = null
                    return label
                }
            })

            val treeMouseListener = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    hideActiveBalloon()
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
                        if (pkg != null) showHint(getRussianProcessDescription(pkg), e, processTree)
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
                    
                    if (c is JComponent) {
                        c.toolTipText = null 
                    }

                    if (message == STALLED_MSG) {
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
                    hideActiveBalloon()
                }
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        val row = logTable.rowAtPoint(e.point)
                        if (row != -1 && logTable.getValueAt(row, 5) != STALLED_MSG) {
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
                        if (row != -1 && logTable.getValueAt(row, 5) != STALLED_MSG) {
                            val pidStr = logTable.getValueAt(row, 1) as? String ?: ""
                            val level = logTable.getValueAt(row, 3) as? String ?: ""
                            val tag = logTable.getValueAt(row, 4) as? String ?: ""
                            val message = logTable.getValueAt(row, 5) as? String ?: ""
                            val pid = pidStr.toIntOrNull()
                            val pkgName = if (pid != null) pidToPackage[pid] else null
                            
                            val hintText = getSmartLogExplanation(pkgName, tag, message, level)
                            showHint(hintText, e, logTable)
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
                rebuildLogTable(force = true)
            }
            toolbar.add(clearButton)
            
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

        private fun showHint(text: String, e: MouseEvent, component: Component) {
            hideActiveBalloon()
            val balloon = JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(text, null, JBColor(Color(255, 255, 220), Color(60, 60, 60)), null)
                .setFadeoutTime(0)
                .setHideOnClickOutside(true)
                .createBalloon()
            
            balloon.show(RelativePoint(component, e.point), Balloon.Position.above)
            activeBalloon = balloon
        }

        private fun hideActiveBalloon() {
            activeBalloon?.hide()
            activeBalloon = null
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
                                if (pid != null && name.contains(".")) {
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
                                            if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == STALLED_MSG) {
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

        private fun rebuildLogTable(force: Boolean = false) {
            val node = processTree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val selectedPackage = node?.userObject as? String ?: return
            
            logTableModel.rowCount = 0
            val snapshot = synchronized(allLogs) { allLogs.toList() }
            val filtered = snapshot.filter { isLineRelatedToPackage(it, selectedPackage) }
            
            if (filtered.isEmpty()) {
                logTableModel.addRow(arrayOf("", "", "", "I", "INFO", STALLED_MSG))
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
                pkg == "system_server" -> "<b>Ядро системы (System Server).</b> Управляет всеми окнами, питанием, уведомлениями, датчиками и безопасностью."
                pkg == "surfaceflinger" -> "<b>Графический композитор (SurfaceFlinger).</b> Собирает кадры от всех приложений и выводит их на экран через GPU."
                pkg == "audioserver" -> "<b>Звуковая служба (AudioServer).</b> Управляет микрофоном, динамиками, громкостью и всеми аудио-потоками."
                pkg == "mediaserver" -> "<b>Медиа-движок (MediaServer).</b> Отвечает за проигрывание видео, музыку, работу камеры и кодеки."
                pkg == "installd" -> "<b>Установщик (Installd).</b> Выполняет установку, удаление приложений и очистку их кеша."
                pkg == "vold" -> "<b>Менеджер дисков (Vold).</b> Управляет файловой системой, SD-картами и шифрованием данных."
                pkg == "netd" -> "<b>Сетевой демон (Netd).</b> Управляет Wi-Fi, мобильным интернетом, точкой доступа и сетевым экраном."
                pkg == "zygote" || pkg == "zygote64" -> "<b>Материнский процесс (Zygote).</b> Процесс-шаблон, из которого рождаются все остальные приложения."
                pkg.contains("systemui") -> "<b>Интерфейс системы (SystemUI).</b> Шторка уведомлений, кнопки навигации, часы и экран блокировки."
                pkg.contains("example") -> "<b>Твой проект.</b> Твое приложение, которое ты сейчас отлаживаешь в Android Studio."
                pkg.contains("google") -> "<b>Службы Google.</b> Play Store, Карты, синхронизация контактов и пуш-уведомления (GMS)."
                pkg == "adbd" -> "<b>Отладчик (ADBD).</b> Мост между телефоном и компьютером для передачи команд и логов."
                pkg == "lmkd" -> "<b>Сторож памяти (LMKD).</b> Убивает фоновые приложения, если оперативная память (RAM) заканчивается."
                pkg.startsWith("PID:") -> "<b>Системная служба.</b> Низкоуровневый демон, выполняющий специфические задачи ОС."
                else -> "<b>Процесс приложения: $pkg.</b> Работает в своей изолированной среде (Sandbox)."
            }
            return "<html><body style='width: 300px;'>$description</body></html>"
        }

        private fun getSmartLogExplanation(pkgName: String?, tag: String, message: String, level: String): String {
            val processInfo = if (pkgName != null) "<b>Отправитель:</b> $pkgName<br>" else ""
            val rawDesc = if (pkgName != null) getRussianProcessDescription(pkgName) else ""
            val cleanDesc = rawDesc.replace("<html><body style='width: 300px;'>", "").replace("</body></html>", "")
            val processDesc = if (cleanDesc.isNotEmpty()) "<i>$cleanDesc</i><br><hr>" else ""
            
            val actionDesc = when {
                // ПИТАНИЕ И БАТАРЕЯ (1-5)
                tag == "PowerManagerService" -> "<b>Управление питанием:</b> Изменение яркости экрана, переход в спящий режим или пробуждение устройства."
                tag == "BatteryService" -> "<b>Служба батареи:</b> Изменение уровня заряда, температуры или статуса подключения зарядного устройства."
                tag == "BatteryStatsService" -> "<b>Анализ энергии:</b> Система собирает данные о том, какие приложения тратят заряд батареи."
                tag == "libPowerHal" || message.contains("perfNotifyAppState") -> "<b>Разгон железа:</b> Уведомление Power HAL о смене состояния. Система поднимает частоты CPU/GPU для плавности."
                tag == "ThermalManagerService" || message.contains("thermal") -> "<b>Температурный контроль:</b> Система следит за нагревом. Если телефон горячий — производительность будет снижена."

                // СЕТИ (6-12)
                tag == "WifiService" || tag == "WifiConfigManager" -> "<b>Wi-Fi:</b> Поиск сетей, процесс подключения или изменение качества сигнала Wi-Fi."
                tag == "ConnectivityService" -> "<b>Сеть:</b> Контроль за передачей данных. Переключение между Wi-Fi и мобильным интернетом."
                tag == "BluetoothAdapter" || tag == "BluetoothDevice" -> "<b>Bluetooth:</b> Поиск устройств, сопряжение или передача данных по протоколу Bluetooth."
                tag == "DnsResolver" || message.contains("DNS") -> "<b>Интернет:</b> Преобразование имен сайтов (google.com) в IP-адреса для установки соединения."
                tag == "DhcpClient" -> "<b>Сетевой адрес:</b> Запрос IP-адреса у роутера при подключении к сети."
                tag == "TelephonyManager" || tag == "ImsResolver" -> "<b>Телефония:</b> Состояние SIM-карты, звонки через интернет (VoLTE) или регистрация в сети."
                tag == "StatusBarSignalPolicy" -> "<b>Связь:</b> Обновление значков уровня сигнала и типа сети (4G/5G) в статус-баре."

                // ГРАФИКА И ИНТЕРФЕЙС (13-25)
                tag == "BufferQueueDebug" || tag == "BufferQueue" || tag == "BufferQueueProducer" -> {
                    if (message.contains("Splash Screen")) "<font color='red'><b>Ошибка Splash Screen:</b> Окно заставки закрылось быстрее, чем система успела его отрисовать.</font>"
                    else "<b>Графический конвейер:</b> Передача кадра между приложением и экраном. Ошибка здесь ведет к мерцанию."
                }
                tag == "WindowManager" || tag == "DisplayContent" -> "<b>Менеджер окон:</b> Управление слоями интерфейса. Решает, какое окно должно быть сверху."
                tag == "ViewRootImpl" || message.contains("relayout") -> "<b>Перерисовка:</b> Окно меняет свой размер, положение или содержимое элементов."
                tag == "Choreographer" -> "<b>Синхронизация:</b> Система сообщает, что пора рисовать следующий кадр. Пропуски вызывают 'лаги'."
                tag == "SurfaceControl" -> "<b>Слои экрана:</b> Создание или уничтожение 'поверхностей' (например, при появлении диалогов)."
                tag == "Skia" -> "<b>Движок рисования:</b> Библиотека, которая рисует все 2D элементы (текст, иконки, тени)."
                tag == "Mali" || tag == "Adreno" || tag == "GLConsumer" -> "<b>Видеокарта (GPU):</b> Сообщения от драйверов графического процессора об отрисовке."
                tag == "WallpaperService" -> "<b>Обои:</b> Отрисовка рабочего стола. Если здесь ошибки — фон может пропасть."
                tag == "DreamManager" -> "<b>Заставка:</b> Работа режима ожидания (Daydream) при зарядке устройства."
                tag == "StatusBar" -> "<b>Статус-бар:</b> Управление верхней панелью (часы, уведомления, иконки)."
                tag == "Game_Utils" || message.contains("getTopPackageName") -> "<b>Игровой режим:</b> Определение активного приложения для приоритезации ресурсов."

                // ЖИЗНЕННЫЙ ЦИКЛ И ПРИЛОЖЕНИЯ (26-35)
                tag == "ActivityTaskManager" || tag == "ActivityManager" -> {
                    if (message.contains("START")) "<b>Навигация:</b> Запуск нового экрана (Activity). Система создает процесс."
                    else "<b>Управление задачами:</b> Переключение между приложениями, остановка фоновых процессов или сохранение состояния."
                }
                tag == "ActivityThread" -> "<b>Главный поток:</b> Main Thread приложения. Здесь выполняется основной код и инициализация UI."
                tag == "Fragment" -> "<b>Интерфейс:</b> Управление частями экрана (фрагментами). Загрузка или смена блоков интерфейса."
                tag == "PackageManager" || tag == "PackageParser" -> "<b>Менеджер пакетов:</b> Проверка прав, поиск установленных программ или установка обновлений."
                tag == "OatFileManager" || tag == "BackgroundDexOptService" -> "<b>Оптимизация:</b> Работа с компилированным кодом приложения для ускорения его запуска."
                tag == "AppOps" || message.contains("Permission") -> "<b>Контроль доступа:</b> Проверка, есть ли у приложения право использовать камеру, микрофон или GPS."
                tag == "Resources" || tag == "AssetManager" -> "<b>Ресурсы:</b> Загрузка картинок, строк или макетов из папки res/assets."
                tag == "JobScheduler" || tag == "JobService" -> "<b>Планировщик:</b> Запуск фоновых задач (индексация, синхронизация) по расписанию."
                tag == "AlarmManager" -> "<b>Таймеры:</b> Выполнение действий в точное время (будильники, уведомления)."
                tag == "WorkManager" -> "<b>Фоновые работы:</b> Библиотека Jetpack управляет гарантированным выполнением задач."

                // БЕЗОПАСНОСТЬ И ВВОД (36-45)
                tag == "KeyguardViewMediator" || tag == "KeyguardUpdateMonitor" -> "<b>Экран блокировки:</b> Процесс разблокировки, проверка пароля или биометрии."
                tag == "FingerprintService" || tag == "FaceService" || tag == "BiometricService" -> "<b>Биометрия:</b> Процесс сканирования отпечатка пальца или распознавания лица."
                tag == "InputDispatcher" || message.contains("MotionEvent") -> "<b>Взаимодействие:</b> Система зафиксировала нажатие пальцем или жест и передает его приложению."
                tag == "InputMethodManagerService" || tag == "SoftKeyboard" -> "<b>Клавиатура:</b> Появление, скрытие или переключение языков экранной клавиатуры."
                tag == "SensorService" || tag == "SensorManager" -> "<b>Датчики:</b> Чтение данных с акселерометра, гироскопа, датчика приближения или освещенности."
                tag == "VibratorService" -> "<b>Вибрация:</b> Управление вибромотором для уведомлений или тактильной отдачи."
                tag == "AccountManagerService" -> "<b>Аккаунты:</b> Синхронизация учетных записей Google, почты и других сервисов."
                tag == "SettingsProvider" || message.contains("Settings") -> "<b>Настройки:</b> Чтение или запись системных параметров (яркость, звук, режим полета)."
                tag == "NotificationService" -> "<b>Уведомления:</b> Создание, отображение или удаление пуш-уведомлений. Проверка режима 'Не беспокоить'."

                // ПАМЯТЬ, ОШИБКИ И ХРАНИЛИЩЕ (46-60)
                tag == "SQLite" || tag == "SQLiteDatabase" || message.contains("database") -> "<b>База данных:</b> Приложение читает или записывает информацию в локальный файл данных."
                tag == "SharedPreferences" -> "<b>Настройки приложения:</b> Работа с простыми данными 'ключ-значение' (XML в памяти)."
                tag == "ContentResolver" || tag == "ContentProvider" -> "<b>Доступ к данным:</b> Приложение запрашивает информацию у другого приложения (например, контакты)."
                tag == "StorageManagerService" || tag == "Vold" -> "<b>Хранилище:</b> Управление файловой системой, SD-картами или проверкой целостности диска."
                tag == "Art" || tag == "Dalvik" -> "<b>Среда исполнения:</b> Сообщения от виртуальной машины Android о работе или компиляции Java/Kotlin кода."
                tag == "StrictMode" -> "<b>Контроль кода:</b> Обнаружена 'тяжелая' операция (сеть/диск) в главном потоке. Это ведет к фризам UI."
                tag == "ProcessStats" -> "<b>Статистика:</b> Сбор данных о потреблении оперативной памяти всеми приложениями."
                tag == "UsbDeviceManager" -> "<b>USB:</b> Определение типа подключения кабеля (зарядка, передача файлов, ADB)."
                message.contains("Exception") || level == "E" -> "<font color='red'><b>Критическая ошибка:</b> Произошел программный сбой. Проверьте 'Stack Trace' для поиска строки в коде.</font>"
                message.contains("ANR") -> "<font color='red'><b>Зависание (ANR):</b> Приложение перестало отвечать. Система готовит отчет о блокировке потока.</font>"
                message.contains("Kill") -> "<b>Завершение процесса:</b> Система закрыла приложение, чтобы освободить RAM для других задач."
                message.contains("OOM") -> "<font color='red'><b>Нехватка памяти:</b> Процесс потребляет слишком много ресурсов (RAM). Возможен вылет приложения.</font>"
                message.contains("Displayed") -> "<b>Экран готов:</b> Полное время запуска и отрисовки окна приложения в миллисекундах."
                message.contains("GC") -> "<b>Очистка памяти:</b> Сборщик мусора (Garbage Collector) освобождает память. Возможны микро-паузы в UI."
                message.contains("Leak") -> "<font color='red'><b>Утечка памяти:</b> Объект не был удален после использования. Это ведет к замедлению телефона.</font>"

                // ПРОЧЕЕ (61-70)
                tag == "Firebase" || message.contains("GCM") -> "<b>Сервисы Firebase:</b> Облачные уведомления, аналитика или работа с БД от Google."
                tag == "SyncManager" -> "<b>Синхронизация:</b> Передача данных между облаком и телефоном (календарь, контакты)."
                tag == "LightsService" -> "<b>Светодиоды:</b> Управление индикатором уведомлений или подсветкой кнопок."
                tag == "AppStandby" -> "<b>Экономия:</b> Система ограничила работу приложения, так как вы им давно не пользовались."
                message.contains("Slow") -> "<b>Медленная работа:</b> Обнаружена задержка в выполнении операции. Возможны пропуски кадров."
                message.contains("Timeout") -> "<b>Таймаут:</b> Ожидание операции превысило лимит времени. Возможно, сервер не ответил."
                message.contains("Blocked") -> "<b>Блокировка:</b> Задача мешает выполнению других. Проверьте нагрузку на Main Thread."
                message.contains("Starting") -> "<b>Холодный старт:</b> Приложение запускается 'с нуля', что требует больше времени и ресурсов."
                tag == "BatterySaver" -> "<b>Экономия заряда:</b> Режим ограничения яркости и анимаций для продления жизни батареи."
                else -> "<b>Событие системы:</b> Штатное уведомление от компонента '$tag'. Сообщает о завершении внутренней операции."
            }

            return "<html><body style='width: 350px;'>$processInfo$processDesc$actionDesc</body></html>"
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

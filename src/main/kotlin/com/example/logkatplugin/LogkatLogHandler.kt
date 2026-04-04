package com.example.logkatplugin

import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.concurrent.TimeUnit

fun LogkatToolWindowFactory.LogkatToolWindow.startLogcatCapture(device: IDevice) {
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            synchronized(allLogs) { allLogs.clear() }
            device.executeShellCommand("logcat -v threadtime", object : MultiLineReceiver() {
                override fun processNewLines(lines: Array<out String>) {
                    lines.forEach { line ->
                        if (line.length > 10) {
                            val cleanLine = if (line.length > 700) line.substring(0, 700) else line
                            synchronized(allLogs) {
                                allLogs.add(cleanLine)
                                if (allLogs.size > 5000) allLogs.removeAt(0)
                            }
                            ApplicationManager.getApplication().invokeLater {
                                if (isDisposed) return@invokeLater
                                if (lastSelectedPackage != null && isLineRelatedToPackage(line, lastSelectedPackage!!) && isLinePassingFilter(line)) {
                                    if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) {
                                        logTableModel.removeRow(0)
                                    }
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

fun LogkatToolWindowFactory.LogkatToolWindow.parseLogLine(line: String): Array<String> {
    // threadtime: 04-04 07:47:02.595 17888 17948 D TAG: Message
    val parts = line.trim().split(Regex("\\s+"), 6)
    if (parts.size < 6) return arrayOf("", "", "", "", "", line)
    
    val time = parts[1] // Берем только время (без даты 04-04)
    val pid = parts[2]
    val tid = parts[3]
    val level = parts[4]
    
    // В шестой части лежит "TAG: Message"
    val rest = parts[5].split(":", limit = 2)
    val tag = rest.getOrNull(0)?.trim() ?: ""
    val message = rest.getOrNull(1)?.trim() ?: ""
    
    return arrayOf(time, pid, tid, level, tag, message)
}

fun LogkatToolWindowFactory.LogkatToolWindow.isLinePassingFilter(line: String): Boolean {
    val query = searchField.text.trim()
    if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false
    if (allLogsButton.isSelected) return true
    
    val parsed = parseLogLine(line)
    val level = parsed[3]
    val pid = parsed[1].toIntOrNull()
    
    return when (filterLevel) {
        "E" -> level == "E"
        "W" -> level == "W"
        "I" -> level == "I" || level == "V" || level == "D"
        "S" -> {
            val pkg = pid?.let { pidToPackage[it] }
            pkg != null && (pkg.contains("android") || pkg.contains("system"))
        }
        else -> true
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.isLineRelatedToPackage(line: String, packageName: String): Boolean {
    val parsed = parseLogLine(line)
    val pidFromLine = parsed[1].toIntOrNull()
    if (pidFromLine != null && pidToPackage[pidFromLine] == packageName) return true
    return line.contains(packageName, ignoreCase = true) || (line.contains("ActivityManager") && line.contains(packageName))
}

fun LogkatToolWindowFactory.LogkatToolWindow.rebuildLogTable() {
    if (isDisposed || lastSelectedPackage == null) return
    logTableModel.rowCount = 0
    val snapshot = synchronized(allLogs) { allLogs.toList() }
    val filtered = snapshot.filter { isLineRelatedToPackage(it, lastSelectedPackage!!) && isLinePassingFilter(it) }
    if (filtered.isEmpty()) {
        logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg))
    } else {
        filtered.takeLast(1000).forEach { logTableModel.addRow(parseLogLine(it)) }
    }
    if (autoscroll) scrollTableToBottom()
}

fun LogkatToolWindowFactory.LogkatToolWindow.scrollTableToBottom() {
    if (logTableModel.rowCount > 0 && !isDisposed) {
        logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true))
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.saveLogsToFile() {
    val selectedPackage = lastSelectedPackage ?: "all"
    val fileName = "logs_${selectedPackage.replace(".", "_")}_${System.currentTimeMillis()}.txt"
    val fileWrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Сохранить логи", "Выберите место", "txt"), project)
        .save(LocalFileSystem.getInstance().findFileByPath(project.basePath ?: ""), fileName)
    if (fileWrapper != null) {
        try {
            val content = StringBuilder()
            for (row in 0 until logTableModel.rowCount) {
                content.append((0 until logTable.columnCount).joinToString(" ") { logTable.getValueAt(row, it).toString() }).append("\n")
            }
            fileWrapper.file.writeText(content.toString())
        } catch (e: Exception) {
            Messages.showErrorDialog("Ошибка: ${e.message}", "Ошибка")
        }
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.navigateToCode(message: String) {
    // Регулярка теперь ищет формат (File.java:123) или (File.kt:123) внутри сообщения
    val traceRegex = Regex("""\(([\w\d_-]+\.(?:kt|java)):(\d+)\)""")
    traceRegex.find(message)?.let { match ->
        val fileName = match.groupValues[1]
        val lineNumber = (match.groupValues[2].toIntOrNull() ?: 1) - 1
        if (doNavigate(fileName, lineNumber)) return
    }
    
    val componentRegex = Regex("""(?:act:[\w\.]+\.|[\s\.])(\w+(?:Activity|Fragment|Service|Receiver|Provider))""")
    componentRegex.find(message)?.let { match ->
        val className = match.groupValues[1]
        if (doNavigate("$className.kt", 0)) return
        if (doNavigate("$className.java", 0)) return
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.doNavigate(fileName: String, line: Int): Boolean {
    val scope = GlobalSearchScope.allScope(project)
    val files = FilenameIndex.getFilesByName(project, fileName, scope)
    val psiFile = files.firstOrNull() ?: return false
    
    ApplicationManager.getApplication().invokeLater {
        val descriptor = OpenFileDescriptor(project, psiFile.virtualFile, line, 0)
        if (descriptor.canNavigate()) {
            descriptor.navigate(true)
        }
    }
    return true
}

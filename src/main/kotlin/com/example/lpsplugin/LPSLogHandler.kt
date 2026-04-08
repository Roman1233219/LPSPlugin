package com.example.lpsplugin

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

fun LPSToolWindowFactory.LPSToolWindow.startLogcatCapture(device: IDevice) {
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
                                if (allLogs.size > 10000) allLogs.removeAt(0)
                            }
                            
                            ApplicationManager.getApplication().invokeLater {
                                if (isDisposed) return@invokeLater
                                if (lastSelectedPackage != null && isLineRelatedToPackage(line, lastSelectedPackage!!) && isLinePassingFilter(line)) {
                                    val parsed = parseLogLine(cleanLine)
                                    
                                    if (autoscroll) {
                                        if (logTableModel.rowCount == 1 && logTableModel.getValueAt(0, 5) == stalledMsg) {
                                            logTableModel.clear()
                                        }
                                        logTableModel.addRow(parsed)
                                        scrollTableToBottom()
                                    } else {
                                        synchronized(pendingLogs) {
                                            pendingLogs.add(parsed)
                                            if (pendingLogs.size > 5000) pendingLogs.removeAt(0)
                                        }
                                    }
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

fun LPSToolWindowFactory.LPSToolWindow.parseLogLine(line: String): Array<String> {
    val parts = line.trim().split(Regex("\\s+"), 6)
    if (parts.size < 6) return arrayOf("", "", "", "", "", line, "")
    
    val time = parts[1]
    val pid = parts[2]
    val tid = parts[3]
    val level = parts[4]
    
    val rest = parts[5].split(":", limit = 2)
    val tag = rest.getOrNull(0)?.trim() ?: ""
    val message = rest.getOrNull(1)?.trim() ?: ""
    
    var type = ""
    if (tag.equals("LPS_TRACE", ignoreCase = true)) {
        val match = Regex("""\(([\w\d_-]+\.(?:kt|java)):(\d+)\)""").find(message)
        if (match != null) {
            val fileName = match.groupValues[1]
            type = getFileLocationType(fileName)
        }
    }
    
    return arrayOf(time, pid, tid, level, tag, message, type)
}

fun LPSToolWindowFactory.LPSToolWindow.getFileLocationType(fileName: String): String {
    return fileLocationCache.getOrPut(fileName) {
        var found = false
        ApplicationManager.getApplication().runReadAction {
            val projectFiles = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project))
            found = projectFiles.isNotEmpty()
        }
        found
    }.let { if (it) "APP" else "LIB" }
}

fun LPSToolWindowFactory.LPSToolWindow.isLinePassingFilter(line: String): Boolean {
    val query = searchField.text.trim()
    if (query.isNotEmpty() && !line.contains(query, ignoreCase = true)) return false
    
    val parsed = parseLogLine(line)
    val tag = parsed[4]
    val type = parsed[6]
    
    if (allTraceButton.isSelected) {
        return tag.equals("LPS_TRACE", ignoreCase = true)
    }
    if (appTraceButton.isSelected) {
        return tag.equals("LPS_TRACE", ignoreCase = true) && type == "APP"
    }

    if (allLogsButton.isSelected) return true

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

fun LPSToolWindowFactory.LPSToolWindow.isLineRelatedToPackage(line: String, packageName: String): Boolean {
    val parsed = parseLogLine(line)
    val pidFromLine = parsed[1].toIntOrNull()
    if (pidFromLine != null && pidToPackage[pidFromLine] == packageName) return true
    return line.contains(packageName, ignoreCase = true) || (line.contains("ActivityManager") && line.contains(packageName))
}

fun LPSToolWindowFactory.LPSToolWindow.rebuildLogTable() {
    if (isDisposed || lastSelectedPackage == null) return
    logTableModel.clear()
    val snapshot = synchronized(allLogs) { allLogs.toList() }
    val filtered = snapshot.filter { isLineRelatedToPackage(it, lastSelectedPackage!!) && isLinePassingFilter(it) }
    if (filtered.isEmpty()) {
        logTableModel.addRow(arrayOf("", "", "", "I", "INFO", stalledMsg, ""))
    } else {
        filtered.takeLast(2000).forEach { logTableModel.addRow(parseLogLine(it)) }
    }
    if (autoscroll) scrollTableToBottom()
}

fun LPSToolWindowFactory.LPSToolWindow.scrollTableToBottom() {
    if (logTableModel.rowCount > 0 && !isDisposed) {
        logTable.scrollRectToVisible(logTable.getCellRect(logTableModel.rowCount - 1, 0, true))
    }
}

fun LPSToolWindowFactory.LPSToolWindow.saveLogsToFile() {
    val selectedPackage = lastSelectedPackage ?: "all"
    val fileName = "logs_${selectedPackage.replace(".", "_")}_${System.currentTimeMillis()}.txt"
    val fileWrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Сохранить логи", "Выберите место", "txt"), project)
        .save(LocalFileSystem.getInstance().findFileByPath(project.basePath ?: ""), fileName)
    if (fileWrapper != null) {
        try {
            val content = StringBuilder()
            for (row in 0 until logTableModel.rowCount) {
                val rowData = logTableModel.getRow(row)
                if (rowData != null) {
                    content.append(rowData.sliceArray(0..5).joinToString(" ")).append("\n")
                }
            }
            fileWrapper.file.writeText(content.toString())
        } catch (e: Exception) {
            Messages.showErrorDialog("Ошибка: ${e.message}", "Ошибка")
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.navigateToCode(message: String) {
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

fun LPSToolWindowFactory.LPSToolWindow.doNavigate(fileName: String, line: Int): Boolean {
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

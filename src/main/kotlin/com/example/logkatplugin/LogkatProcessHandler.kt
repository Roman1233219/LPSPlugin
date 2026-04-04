package com.example.logkatplugin

import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Color
import java.awt.Font
import java.awt.Image
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

fun LogkatToolWindowFactory.LogkatToolWindow.updateTraceButtonsState() {
    val enabled = isTraceInjected()
    enableTraceButton.isEnabled = !enabled
    disableTraceButton.isEnabled = enabled
}

fun LogkatToolWindowFactory.LogkatToolWindow.findTargetGradleFile(): File? {
    val paths = listOf("app/build.gradle", "build.gradle", "app/build.gradle.kts", "build.gradle.kts")
    for (p in paths) {
        val f = File(project.basePath, p)
        if (f.exists()) return f
    }
    return null
}

fun LogkatToolWindowFactory.LogkatToolWindow.isTraceInjected(): Boolean {
    val target = findTargetGradleFile()
    return target?.exists() == true && target.readText().contains("com.example.logkat")
}

fun LogkatToolWindowFactory.LogkatToolWindow.findPackageByPid(pid: String): String? {
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

fun LogkatToolWindowFactory.LogkatToolWindow.ensureScriptsExist(): File? {
    val targetFile = findTargetGradleFile() ?: return null
    val baseDir = File(targetFile.parentFile, "on-device-server/src")
    if (!baseDir.exists()) baseDir.mkdirs()

    val psFile = File(baseDir, "run_resolver.ps1")
    val javaFile = File(baseDir, "LabelResolver.java")
    val tracerFile = File(baseDir, "LogkatTracer.java")

    fun extractResource(resName: String, target: File) {
        val stream = javaClass.getResourceAsStream("/scripts/$resName")
        if (stream != null) {
            target.writeBytes(stream.readBytes())
        }
    }

    try {
        extractResource("run_resolver.ps1", psFile)
        extractResource("LabelResolver.java", javaFile)
        extractResource("LogkatTracer.java", tracerFile)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(psFile, javaFile, tracerFile))
        return psFile
    } catch (e: Exception) {
        return null
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.injectGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        val content = target.readText()
        if (content.contains("com.example.logkat")) return true

        val isKts = target.name.endsWith(".kts")
        val injection = if (isKts) {
            """
            // --- Logkat Instrumentation Start ---
            buildscript {
                repositories {
                    mavenLocal()
                }
                dependencies {
                    classpath("com.example.logkatplugin:LogkatPlugin:2.0.0")
                }
            }
            apply(plugin = "com.example.logkat")
            android.sourceSets.getByName("main").java.srcDir("on-device-server/src")
            // --- Logkat Instrumentation End ---
            """.trimIndent()
        } else {
            """
            // --- Logkat Instrumentation Start ---
            buildscript {
                repositories {
                    mavenLocal()
                }
                dependencies {
                    classpath "com.example.logkatplugin:LogkatPlugin:2.0.0"
                }
            }
            apply plugin: 'com.example.logkat'
            android.sourceSets.main.java.srcDirs += 'on-device-server/src'
            // --- Logkat Instrumentation End ---
            """.trimIndent()
        }

        target.writeText(content + "\n\n" + injection)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(target))
        return true
    } catch (e: Exception) {
        return false
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.removeGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        val content = target.readText()
        val startMarker = "// --- Logkat Instrumentation Start ---"
        val endMarker = "// --- Logkat Instrumentation End ---"
        
        if (content.contains(startMarker)) {
            val startIdx = content.indexOf(startMarker)
            val endIdx = content.indexOf(endMarker)
            if (endIdx != -1) {
                val newContent = content.substring(0, startIdx) + content.substring(endIdx + endMarker.length)
                target.writeText(newContent.trimEnd())
                LocalFileSystem.getInstance().refreshIoFiles(listOf(target))
            }
        }
        return true
    } catch (e: Exception) {
        return false
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.runTracePreparation() {
    if (Messages.showYesNoDialog(project, "Включить трассировку проекта?\nЭто активирует плагин инструментации.\nУбедитесь, что вы выполнили 'publishToMavenLocal' для плагина.", "Трассировка", Messages.getQuestionIcon()) == Messages.YES) {
        val scriptFile = ensureScriptsExist()
        if (scriptFile != null) {
            if (injectGradleApply(project)) {
                updateTraceButtonsState()
                Messages.showInfoMessage(project, "Трассировка включена!\nВыполните Rebuild Project для активации.", "Трассировка")
            } else {
                Messages.showErrorDialog(project, "Не удалось обновить build.gradle.", "Ошибка")
            }
        }
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.runTraceRemoval() {
    if (Messages.showYesNoDialog(project, "Выключить трассировку проекта?\nНастройки будут удалены из build.gradle.", "Трассировка", Messages.getQuestionIcon()) == Messages.YES) {
        if (removeGradleApply(project)) {
            // Очищаем файл настроек при выключении
            val settingsFile = File(project.basePath, ".idea/logkat_trace_settings.txt")
            if (settingsFile.exists()) {
                settingsFile.delete()
                LocalFileSystem.getInstance().refreshIoFiles(listOf(settingsFile))
            }
            
            // Сбрасываем UI трассировки
            resetTraceUI()
            
            updateTraceButtonsState()
            Messages.showInfoMessage(project, "Трассировка выключена.", "Трассировка")
        } else {
            Messages.showErrorDialog(project, "Не удалось очистить build.gradle.", "Ошибка")
        }
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.runDeepSync() {
    if (Messages.showYesNoDialog(project, "Синхронизировать имена и иконки? Старые иконки будут удалены.", "Синхронизация", Messages.getQuestionIcon()) == Messages.YES) {
        val targetFile = findTargetGradleFile() ?: return
        val scriptFile = ensureScriptsExist()
        if (scriptFile == null || !scriptFile.exists()) {
            Messages.showErrorDialog(project, "Не удалось подготовить скрипты синхронизации!", "Ошибка")
            return
        }

        val iconsDir = File(targetFile.parentFile, "on-device-server/src/icons")
        if (iconsDir.exists()) {
            iconsDir.deleteRecursively()
        }

        bulkUpdateLabelsButton.isEnabled = false; setStatusText("🔍 Синхронизация...", true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
                    .directory(targetFile.parentFile)
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
                
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(iconsDir)
                vf?.refresh(false, true)

                ApplicationManager.getApplication().invokeLater { if (!isDisposed) { loadInitialData(); reloadTreeSafely() ; bulkUpdateLabelsButton.isEnabled = true; setStatusText("Готово", false) } }
            } catch (e: Exception) { 
                ApplicationManager.getApplication().invokeLater { 
                    Messages.showErrorDialog(project, "Ошибка запуска: ${e.message}", "Ошибка")
                    setStatusText("Ошибка", false); bulkUpdateLabelsButton.isEnabled = true 
                } 
            }
        }
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.getGroupIcon(groupName: String): Icon = when {
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

fun LogkatToolWindowFactory.LogkatToolWindow.getGroupColor(groupName: String): Color = when {
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

fun LogkatToolWindowFactory.LogkatToolWindow.getAppIcon(pkgName: String): Icon { val cached = iconCache[pkgName]; if (cached != null) return cached; val iconFile = File("${project.basePath}/on-device-server/src/icons/$pkgName.png"); if (iconFile.exists()) { try { val img = ImageIcon(iconFile.absolutePath); val scaled = ImageIcon(img.image.getScaledInstance(16, 16, Image.SCALE_SMOOTH)); iconCache[pkgName] = scaled; return scaled } catch (e: Exception) {} }; return when { pkgName.contains("example") || pkgName == projectPkg -> AllIcons.Nodes.HomeFolder; pkgName.contains("google") -> AllIcons.Nodes.PpWeb; pkgName.contains("android") -> AllIcons.General.Settings; else -> AllIcons.Nodes.Package } }

fun LogkatToolWindowFactory.LogkatToolWindow.refreshProcesses() {
    val device = currentDevice ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
        pidToPackage.clear()
        device.clients.forEach { it.clientData.packageName?.let { pkg -> pidToPackage[it.clientData.pid] = pkg } }
        val processReceiver = object : MultiLineReceiver() { override fun processNewLines(lines: Array<out String>) { lines.forEach { line -> val parts = line.trim().split(Regex("\\s+")); if (parts.size >= 8) { val pid = parts[1].toIntOrNull() ?: parts[0].toIntOrNull(); if (pid != null) pidToPackage[pid] = parts.last() } } } override fun isCancelled() = isDisposed }
        try { device.executeShellCommand("ps -A", processReceiver, 0, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        
        val currentPids = pidToPackage.toMap()
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

            // ⭐ МОЙ ПРОЕКТ - только текущий запущенный проект, без лишних "example"
            val myProjectPackages = mutableListOf<String>()
            projectPkg?.let { pkg ->
                myProjectPackages.add(pkg)
                usedPackages.add(pkg)
            }
            addGroupWithChildren("⭐ МОЙ ПРОЕКТ", myProjectPackages, pkgToPids, true, "приложение не найдено")
            
            // Остальные группы
            addGroupWithChildren("🔍 ПРИЛОЖЕНИЯ ГУГЛ", filterPackages { it.startsWith("com.google.android.") && (it.contains("youtube") || it.contains("maps") || it.contains("chrome") || it.contains("gm") || it.contains("calendar") || it.contains("photos") || it.contains("vending")) }, pkgToPids)
            addGroupWithChildren("☁️ СЛУЖБЫ ГУГЛ", filterPackages { it.contains("google") }, pkgToPids)
            addGroupWithChildren("🖼️ ИНТЕРФЕЙС И ГРАФИКА", filterPackages { it.contains("systemui") || it.contains("launcher") || it.contains("surfaceflinger") || it.contains("wm.") || it.contains("wallpaper") || it.contains("renderengine") || it.contains("gpu") || it.contains("composer") }, pkgToPids)
            addGroupWithChildren("📡 СЕТЬ И СВЯЗЬ", filterPackages { it.contains("wifi") || it.contains("bluetooth") || it.contains("telephony") || it.contains("nfc") || it.contains("netd") || it.contains("networkstack") || it.contains("wpa_supplicant") || it.contains("phone") || it.contains("iptables") || it.contains("ipsec") || it.contains("modem") }, pkgToPids)
            addGroupWithChildren("🔊 МЕДИА И ЗВУК", filterPackages { it.contains("audio") || it.contains("media") || it.contains("codec") || it.contains("drm") || it.contains("sound") || it.contains("video") || it.contains("camera") }, pkgToPids)
            addGroupWithChildren("🛠️ ЖЕЛЕЗО И ДРАЙВЕРЫ", filterPackages { it.contains("hal") || it.contains("hardware") || it.contains("sensor") || it.contains("gps") || it.contains("fingerprint") || it.contains("thermal") || it.contains("light") || it.contains("power") || it.contains("usb") || it.contains("battery") }, pkgToPids)
            addGroupWithChildren("⚙️ СИСТЕМНЫЕ СЛУЖБЫ", filterPackages { it.startsWith("com.android.") || it.contains("providers") || it.contains("settings") || it.contains("system_server") || it.contains("permission") || it.contains("keystore") || it.contains("credstore") || it.contains("gatekeeper") || it.contains("security") }, pkgToPids)
            addGroupWithChildren("🧱 НИЗКОУРОВНЕВЫЕ", filterPackages { it.startsWith("[") || !it.contains(".") || it == "init" || it == "zygote" || it == "adbd" || it == "logd" || it.contains("logger") || it.contains("incident") || it.contains("crash") || it.contains("stats") || it == "sh" || it == "magisk" }, pkgToPids)
            addGroupWithChildren("👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ", filterPackages { it.contains(".") }, pkgToPids)
            addGroupWithChildren("📂 ПРОЧЕЕ", allPackages.filter { it !in usedPackages }, pkgToPids)

            treeModel.reload(); restoreExpansionState(rootNode, TreePath(rootNode), expandedNames)
        }
    }
}

fun LogkatToolWindowFactory.LogkatToolWindow.restoreExpansionState(node: DefaultMutableTreeNode, path: TreePath, expandedNames: Set<List<String>>) { 
    if (expandedNames.contains(path.path.map { (it as DefaultMutableTreeNode).userObject.toString() })) processTree.expandPath(path)
    for (i in 0 until node.childCount) { 
        val child = node.getChildAt(i) as DefaultMutableTreeNode
        restoreExpansionState(child, path.pathByAddingChild(child), expandedNames) 
    } 
}

fun LogkatToolWindowFactory.LogkatToolWindow.addGroupWithChildren(
    title: String, 
    packages: List<String>, 
    pkgMap: Map<String, List<Int>>,
    showIfEmpty: Boolean = false,
    emptyMessage: String = "нет активных процессов"
) { 
    if (packages.isEmpty() && !showIfEmpty) return
    
    val groupNode = DefaultMutableTreeNode(title)
    if (packages.isEmpty()) {
        groupNode.add(DefaultMutableTreeNode(emptyMessage))
    } else {
        packages.forEach { pkg -> 
            val pkgNode = DefaultMutableTreeNode(pkg)
            pkgMap[pkg]?.sorted()?.forEach { pid -> pkgNode.add(DefaultMutableTreeNode(pid.toString())) }
            groupNode.add(pkgNode) 
        }
    }
    rootNode.add(groupNode) 
}

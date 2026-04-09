package com.example.lpsplugin

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

fun LPSToolWindowFactory.LPSToolWindow.updateTraceButtonsState() {
    val enabled = isTraceInjected()
    enableTraceButton.isEnabled = !enabled
    disableTraceButton.isEnabled = enabled
    updateButtonBorders()
}

fun LPSToolWindowFactory.LPSToolWindow.findTargetGradleFile(): File? {
    val paths = listOf("app/build.gradle", "build.gradle", "app/build.gradle.kts", "build.gradle.kts")
    for (p in paths) {
        val f = File(project.basePath, p)
        if (f.exists()) return f
    }
    return null
}

fun LPSToolWindowFactory.LPSToolWindow.isTraceInjected(): Boolean {
    val target = findTargetGradleFile()
    return target?.exists() == true && target.readText().contains("io.github.Roman1233219")
}

fun LPSToolWindowFactory.LPSToolWindow.findPackageByPid(pid: String): String? {
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

fun LPSToolWindowFactory.LPSToolWindow.ensureScriptsExist(): File? {
    val targetFile = findTargetGradleFile() ?: return null
    val baseDir = File(targetFile.parentFile, "on-device-server/src")
    if (!baseDir.exists()) baseDir.mkdirs()

    val psFile = File(baseDir, "run_resolver.ps1")
    val javaFile = File(baseDir, "LabelResolver.java")
    val tracerFile = File(baseDir, "LPSTracer.java")

    fun extractResource(resName: String, target: File) {
        val stream = javaClass.getResourceAsStream("/scripts/$resName")
        if (stream != null) {
            target.writeBytes(stream.readBytes())
        }
    }

    try {
        extractResource("run_resolver.ps1", psFile)
        extractResource("LabelResolver.java", javaFile)
        extractResource("LPSTracer.java", tracerFile)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(psFile, javaFile, tracerFile))
        return psFile
    } catch (e: Exception) {
        return null
    }
}

fun LPSToolWindowFactory.LPSToolWindow.injectGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        val content = target.readText()
        if (content.contains("io.github.Roman1233219")) return true

        val isKts = target.name.endsWith(".kts")
        val injection = if (isKts) {
            """
            // --- LPS Instrumentation Start ---
            buildscript {
                repositories {
                    gradlePluginPortal()
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath("io.github.Roman1233219:LPSPlugin:2.0.0")
                }
            }
            apply(plugin = "io.github.Roman1233219.lps")
            android.sourceSets.getByName("main").java.srcDir("on-device-server/src")
            // --- LPS Instrumentation End ---
            """.trimIndent()
        } else {
            """
            // --- LPS Instrumentation Start ---
            buildscript {
                repositories {
                    gradlePluginPortal()
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath "io.github.Roman1233219:LPSPlugin:2.0.0"
                }
            }
            apply plugin: 'io.github.Roman1233219.lps'
            android.sourceSets.main.java.srcDirs += 'on-device-server/src'
            // --- LPS Instrumentation End ---
            """.trimIndent()
        }

        target.writeText(content + "\n\n" + injection)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(target))
        return true
    } catch (e: Exception) {
        return false
    }
}

fun LPSToolWindowFactory.LPSToolWindow.removeGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        val content = target.readText()
        val startMarker = "// --- LPS Instrumentation Start ---"
        val endMarker = "// --- LPS Instrumentation End ---"
        
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

fun LPSToolWindowFactory.LPSToolWindow.runTracePreparation() {
    val isRu = currentLang == "RU"
    val msg = if (isRu) 
        "Включить трассировку проекта?\nЭто активирует плагин инструментации.\nУбедитесь, что вы выполнили 'publishPlugins' для плагина.\n\nВНИМАНИЕ: После активации необходимо выполнить Sync Project (Gradle Sync)!"
    else 
        "Enable project tracing?\nThis activates the instrumentation plugin.\nMake sure you have executed 'publishPlugins' for the plugin.\n\nWARNING: Sync Project (Gradle Sync) is required after enabling!"
    
    val title = if (isRu) "Трассировка" else "Tracing"

    if (Messages.showYesNoDialog(project, msg, title, Messages.getQuestionIcon()) == Messages.YES) {
        val scriptFile = ensureScriptsExist()
        if (scriptFile != null) {
            if (injectGradleApply(project)) {
                ApplicationManager.getApplication().invokeLater {
                    updateTraceButtonsState()
                }
                val successMsg = if (isRu) "Трассировка включена!\nВыполните Rebuild Project для активации." else "Tracing enabled!\nPerform Rebuild Project to activate."
                Messages.showInfoMessage(project, successMsg, title)
            } else {
                val errorMsg = if (isRu) "Не удалось обновить build.gradle." else "Failed to update build.gradle."
                Messages.showErrorDialog(project, errorMsg, if (isRu) "Ошибка" else "Error")
            }
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.runTraceRemoval() {
    val isRu = currentLang == "RU"
    val msg = if (isRu) 
        "Выключить трассировку проекта?\nНастройки будут удалены из build.gradle.\n\nВНИМАНИЕ: После выключения необходимо выполнить Sync Project (Gradle Sync)!"
    else 
        "Disable project tracing?\nSettings will be removed from build.gradle.\n\nWARNING: Sync Project (Gradle Sync) is required after disabling!"
    
    val title = if (isRu) "Трассировка" else "Tracing"

    if (Messages.showYesNoDialog(project, msg, title, Messages.getQuestionIcon()) == Messages.YES) {
        if (removeGradleApply(project)) {
            val settingsFile = File(project.basePath, ".idea/lps_trace_settings.txt")
            if (settingsFile.exists()) {
                settingsFile.delete()
                LocalFileSystem.getInstance().refreshIoFiles(listOf(settingsFile))
            }
            resetTraceUI()
            val successMsg = if (isRu) "Трассировка выключена." else "Tracing disabled."
            Messages.showInfoMessage(project, successMsg, title)
        } else {
            val errorMsg = if (isRu) "Не удалось очистить build.gradle." else "Failed to clean build.gradle."
            Messages.showErrorDialog(project, errorMsg, if (isRu) "Ошибка" else "Error")
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.runDeepSync() {
    val isRu = currentLang == "RU"
    val msg = if (isRu) "Синхронизировать имена и иконки? Старые иконки будут удалены." else "Sync names and icons? Old icons will be deleted."
    val title = if (isRu) "Синхронизация" else "Sync"

    if (Messages.showYesNoDialog(project, msg, title, Messages.getQuestionIcon()) == Messages.YES) {
        val targetFile = findTargetGradleFile() ?: return
        val scriptFile = ensureScriptsExist()
        if (scriptFile == null || !scriptFile.exists()) {
            val errorMsg = if (isRu) "Не удалось подготовить скрипты синхронизации!" else "Failed to prepare sync scripts!"
            Messages.showErrorDialog(project, errorMsg, if (isRu) "Ошибка" else "Error")
            return
        }

        val iconsDir = File(targetFile.parentFile, "on-device-server/src/icons")
        if (iconsDir.exists()) {
            iconsDir.deleteRecursively()
        }

        bulkUpdateLabelsButton.isEnabled = false
        setStatusText(if (isRu) "🔍 Синхронизация..." else "🔍 Syncing...", true)
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath)
                    .directory(targetFile.parentFile)
                    .start()

                process.inputStream.bufferedReader().use { it.forEachLine { line ->
                    if (line.contains("|")) {
                        val parts = line.split("|"); if (parts.size >= 2) {
                            val pkg = parts[0].trim(); val lbl = parts[1].trim()
                            packageToLabel[pkg] = lbl; LPSLogExplanationProvider.writeToDictionary(project.basePath, pkg, lbl, projectPkg)
                        }
                    }
                }}
                process.waitFor()
                
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(iconsDir)
                vf?.refresh(false, true)

                ApplicationManager.getApplication().invokeLater { 
                    if (!isDisposed) { 
                        loadInitialData()
                        reloadTreeSafely()
                        bulkUpdateLabelsButton.isEnabled = true
                        setStatusText(if (isRu) "Готово" else "Done", false) 
                    } 
                }
            } catch (e: Exception) { 
                ApplicationManager.getApplication().invokeLater { 
                    val errorLaunchMsg = if (isRu) "Ошибка запуска: ${e.message}" else "Launch error: ${e.message}"
                    Messages.showErrorDialog(project, errorLaunchMsg, if (isRu) "Ошибка" else "Error")
                    setStatusText(if (isRu) "Ошибка" else "Error", false)
                    bulkUpdateLabelsButton.isEnabled = true 
                } 
            }
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.getGroupIcon(groupName: String): Icon = when {
    groupName.contains("ПРОЕКТ") || groupName.contains("PROJECT") -> AllIcons.Nodes.HomeFolder
    groupName.contains("ПОЛЬЗОВАТЕЛЬСКИЕ") || groupName.contains("USER") -> AllIcons.Nodes.Package
    groupName.contains("ПРИЛОЖЕНИЯ ГУГЛ") || groupName.contains("GOOGLE APPS") -> AllIcons.Nodes.PpWeb
    groupName.contains("СЛУЖБЫ ГУГЛ") || groupName.contains("GOOGLE SERVICES") -> AllIcons.Nodes.PpLib
    groupName.contains("ИНТЕРФЕЙС") || groupName.contains("INTERFACE") -> AllIcons.Nodes.Editorconfig
    groupName.contains("СИСТЕМНЫЕ") || groupName.contains("SYSTEM") -> AllIcons.Nodes.ConfigFolder
    groupName.contains("НИЗКОУРОВНЕВЫЕ") || groupName.contains("LOW-LEVEL") -> AllIcons.Nodes.ResourceBundle
    groupName.contains("ЖЕЛЕЗО") || groupName.contains("HARDWARE") -> AllIcons.General.Settings
    groupName.contains("СЕТЬ") || groupName.contains("NETWORK") -> AllIcons.General.Web
    groupName.contains("МЕДИА") || groupName.contains("MEDIA") -> AllIcons.Nodes.Class
    else -> AllIcons.Nodes.Folder
}

fun LPSToolWindowFactory.LPSToolWindow.getGroupColor(groupName: String): Color = when {
    groupName.contains("ПРОЕКТ") || groupName.contains("PROJECT") -> Color(180, 150, 255)
    groupName.contains("ПОЛЬЗОВАТЕЛЬСКИЕ") || groupName.contains("USER") -> Color(150, 255, 150)
    groupName.contains("ГУГЛ") || groupName.contains("GOOGLE") -> Color(255, 255, 150)
    groupName.contains("ИНТЕРФЕЙС") || groupName.contains("INTERFACE") -> Color(150, 255, 255)
    groupName.contains("СИСТЕМНЫЕ") || groupName.contains("SYSTEM") -> Color(100, 150, 255)
    groupName.contains("НИЗКОУРОВНЕВЫЕ") || groupName.contains("LOW-LEVEL") -> Color(200, 200, 200)
    groupName.contains("ЖЕЛЕЗО") || groupName.contains("HARDWARE") -> Color(255, 180, 150)
    groupName.contains("СЕТЬ") || groupName.contains("NETWORK") -> Color(150, 200, 255)
    groupName.contains("МЕДИА") || groupName.contains("MEDIA") -> Color(255, 150, 255)
    else -> Color.LIGHT_GRAY
}

fun LPSToolWindowFactory.LPSToolWindow.getAppIcon(pkgName: String): Icon { val cached = iconCache[pkgName]; if (cached != null) return cached; val iconFile = File("${project.basePath}/on-device-server/src/icons/$pkgName.png"); if (iconFile.exists()) { try { val img = ImageIcon(iconFile.absolutePath); val scaled = ImageIcon(img.image.getScaledInstance(16, 16, Image.SCALE_SMOOTH)); iconCache[pkgName] = scaled; return scaled } catch (e: Exception) {} }; return when { pkgName.contains("example") || pkgName == projectPkg -> AllIcons.Nodes.HomeFolder; pkgName.contains("google") -> AllIcons.Nodes.PpWeb; pkgName.contains("android") -> AllIcons.General.Settings; else -> AllIcons.Nodes.Package } }

fun LPSToolWindowFactory.LPSToolWindow.refreshProcesses() {
    val device = currentDevice ?: return
    val isRu = currentLang == "RU"
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

            val myProjectPackages = mutableListOf<String>()
            projectPkg?.let { pkg ->
                myProjectPackages.add(pkg)
                usedPackages.add(pkg)
            }
            addGroupWithChildren(if (isRu) "⭐ МОЙ ПРОЕКТ" else "⭐ MY PROJECT", myProjectPackages, pkgToPids, true, if (isRu) "приложение не найдено" else "app not found")
            
            addGroupWithChildren(if (isRu) "🔍 ПРИЛОЖЕНИЯ ГУГЛ" else "🔍 GOOGLE APPS", filterPackages { it.startsWith("com.google.android.") && (it.contains("youtube") || it.contains("maps") || it.contains("chrome") || it.contains("gm") || it.contains("calendar") || it.contains("photos") || it.contains("vending")) }, pkgToPids)
            addGroupWithChildren(if (isRu) "☁️ СЛУЖБЫ ГУГЛ" else "☁️ GOOGLE SERVICES", filterPackages { it.contains("google") }, pkgToPids)
            addGroupWithChildren(if (isRu) "🖼️ ИНТЕРФЕЙС И ГРАФИКА" else "🖼️ INTERFACE & GRAPHICS", filterPackages { it.contains("systemui") || it.contains("launcher") || it.contains("surfaceflinger") || it.contains("wm.") || it.contains("wallpaper") || it.contains("renderengine") || it.contains("gpu") || it.contains("composer") }, pkgToPids)
            addGroupWithChildren(if (isRu) "📡 СЕТЬ И СВЯЗЬ" else "📡 NETWORK & CONNECTIVITY", filterPackages { it.contains("wifi") || it.contains("bluetooth") || it.contains("telephony") || it.contains("nfc") || it.contains("netd") || it.contains("networkstack") || it.contains("wpa_supplicant") || it.contains("phone") || it.contains("iptables") || it.contains("ipsec") || it.contains("modem") }, pkgToPids)
            addGroupWithChildren(if (isRu) "🔊 МЕДИА И ЗВУК" else "🔊 MEDIA & SOUND", filterPackages { it.contains("audio") || it.contains("media") || it.contains("codec") || it.contains("drm") || it.contains("sound") || it.contains("video") || it.contains("camera") }, pkgToPids)
            addGroupWithChildren(if (isRu) "🛠️ ЖЕЛЕЗО И ДРАЙВЕРЫ" else "🛠️ HARDWARE & DRIVERS", filterPackages { it.contains("hal") || it.contains("hardware") || it.contains("sensor") || it.contains("gps") || it.contains("fingerprint") || it.contains("thermal") || it.contains("light") || it.contains("power") || it.contains("usb") || it.contains("battery") }, pkgToPids)
            addGroupWithChildren(if (isRu) "⚙️ СИСТЕМНЫЕ СЛУЖБЫ" else "⚙️ SYSTEM SERVICES", filterPackages { it.startsWith("com.android.") || it.contains("providers") || it.contains("settings") || it.contains("system_server") || it.contains("permission") || it.contains("keystore") || it.contains("credstore") || it.contains("gatekeeper") || it.contains("security") }, pkgToPids)
            addGroupWithChildren(if (isRu) "🧱 НИЗКОУРОВНЕВЫЕ" else "🧱 LOW-LEVEL", filterPackages { it.startsWith("[") || !it.contains(".") || it == "init" || it == "zygote" || it == "adbd" || it == "logd" || it.contains("logger") || it.contains("incident") || it.contains("crash") || it.contains("stats") || it == "sh" || it == "magisk" }, pkgToPids)
            addGroupWithChildren(if (isRu) "👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ" else "👤 USER APPLICATIONS", filterPackages { it.contains(".") }, pkgToPids)
            addGroupWithChildren(if (isRu) "📂 ПРОЧЕЕ" else "📂 OTHER", allPackages.filter { it !in usedPackages }, pkgToPids)

            treeModel.reload(); restoreExpansionState(rootNode, TreePath(rootNode), expandedNames)
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.restoreExpansionState(node: DefaultMutableTreeNode, path: TreePath, expandedNames: Set<List<String>>) { 
    if (expandedNames.contains(path.path.map { (it as DefaultMutableTreeNode).userObject.toString() })) processTree.expandPath(path)
    for (i in 0 until node.childCount) { 
        val child = node.getChildAt(i) as DefaultMutableTreeNode
        restoreExpansionState(child, path.pathByAddingChild(child), expandedNames) 
    } 
}

fun LPSToolWindowFactory.LPSToolWindow.addGroupWithChildren(
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

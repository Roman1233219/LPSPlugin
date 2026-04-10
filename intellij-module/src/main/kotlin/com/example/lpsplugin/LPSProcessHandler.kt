package com.example.lpsplugin

import com.android.ddmlib.IDevice
import com.android.ddmlib.MultiLineReceiver
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
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
    val base = project.basePath ?: return null
    val paths = listOf("app/build.gradle.kts", "app/build.gradle", "build.gradle.kts", "build.gradle")
    for (p in paths) {
        val f = File(base, p)
        if (f.exists()) return f
    }
    return null
}

fun LPSToolWindowFactory.LPSToolWindow.isTraceInjected(): Boolean {
    val target = findTargetGradleFile()
    return target?.exists() == true && target.readText().contains("lps-plugin.jar")
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
    
    val lpsDir = File(project.basePath ?: return null, ".idea/lps")
    if (!lpsDir.exists()) lpsDir.mkdirs()

    val psFile = File(baseDir, "run_resolver.ps1")
    val javaFile = File(baseDir, "LabelResolver.java")
    val jarFile = File(lpsDir, "lps-plugin.jar")

    fun extractResource(resName: String, target: File): Boolean {
        val stream = javaClass.getResourceAsStream("/scripts/$resName")
        if (stream != null) {
            try {
                target.writeBytes(stream.readBytes())
                return true
            } catch (e: Exception) { return false }
        }
        return false
    }

    try {
        extractResource("run_resolver.ps1", psFile)
        extractResource("LabelResolver.java", javaFile)
        extractResource("resolver.dex", File(baseDir, "resolver.dex"))
        extractResource("LPSInstrumentation.gradle", File(baseDir, "LPSInstrumentation.gradle"))
        extractResource("LPSTracer.java", File(baseDir, "LPSTracer.java"))
        
        val jarExtracted = extractResource("lps-plugin.jar", jarFile)
        if (!jarExtracted) return null
        
        LocalFileSystem.getInstance().refreshIoFiles(listOf(psFile, javaFile, jarFile))
        return psFile
    } catch (e: Exception) { return null }
}

fun LPSToolWindowFactory.LPSToolWindow.injectGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        var content = target.readText()
        if (content.contains("lps-plugin.jar")) return true

        val isKts = target.name.endsWith(".kts")
        val injection = if (isKts) {
            """
            // --- LPS Instrumentation Start ---
            buildscript {
                repositories { mavenCentral(); google() }
                dependencies {
                    classpath(files("${'$'}{project.rootDir}/.idea/lps/lps-plugin.jar"))
                    classpath("org.ow2.asm:asm:9.6")
                    classpath("org.ow2.asm:asm-commons:9.6")
                }
            }
            apply(plugin = "io.github.Roman1233219.lps")
            // --- LPS Instrumentation End ---
            """.trimIndent()
        } else {
            """
            // --- LPS Instrumentation Start ---
            buildscript {
                repositories { mavenCentral(); google() }
                dependencies {
                    classpath files("${'$'}{project.getRootDir()}/.idea/lps/lps-plugin.jar")
                    classpath 'org.ow2.asm:asm:9.6'
                    classpath 'org.ow2.asm:asm-commons:9.6'
                }
            }
            apply plugin: 'io.github.Roman1233219.lps'
            // --- LPS Instrumentation End ---
            """.trimIndent()
        }

        val insertIdx = if (content.contains("plugins {")) {
            content.indexOf("}", content.indexOf("plugins {")) + 1
        } else 0

        val newContent = content.substring(0, insertIdx) + "\n" + injection + "\n" + content.substring(insertIdx)
        target.writeText(newContent)
        LocalFileSystem.getInstance().refreshIoFiles(listOf(target))
        return true
    } catch (e: Exception) { return false }
}

fun LPSToolWindowFactory.LPSToolWindow.removeGradleApply(project: Project): Boolean {
    val target = findTargetGradleFile() ?: return false
    try {
        var content = target.readText()
        val startMarker = "// --- LPS Instrumentation Start ---"
        val endMarker = "// --- LPS Instrumentation End ---"
        if (content.contains(startMarker)) {
            val startIdx = content.indexOf(startMarker)
            val endIdx = content.indexOf(endMarker)
            if (endIdx != -1) {
                val newContent = content.substring(0, startIdx) + content.substring(endIdx + endMarker.length)
                target.writeText(newContent.trim())
                LocalFileSystem.getInstance().refreshIoFiles(listOf(target))
            }
        }
        return true
    } catch (e: Exception) { return false }
}

fun LPSToolWindowFactory.LPSToolWindow.runTracePreparation() {
    val isRu = currentLang == "RU"
    val title = if (isRu) "Трассировка" else "Tracing"
    val targetFile = findTargetGradleFile()
    if (targetFile == null) {
        Messages.showErrorDialog(project, if (isRu) "Файл build.gradle не найден!" else "build.gradle not found!", title)
        return
    }
    if (Messages.showYesNoDialog(project, if (isRu) "Включить трассировку проекта?" else "Enable project tracing?", title, Messages.getQuestionIcon()) == Messages.YES) {
        val scriptResult = ensureScriptsExist()
        if (scriptResult != null) {
            if (injectGradleApply(project)) {
                ApplicationManager.getApplication().invokeLater { updateTraceButtonsState() }
                Messages.showInfoMessage(project, if (isRu) "Трассировка включена!\nНажмите 'Sync Project' для активации." else "Tracing enabled!\nClick 'Sync Project' to activate.", title)
            }
        } else {
             Messages.showErrorDialog(project, if (isRu) "Ошибка: lps-plugin.jar не найден в ресурсах!" else "Error: lps-plugin.jar not found in resources!", title)
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.runTraceRemoval() {
    val isRu = currentLang == "RU"
    val title = if (isRu) "Трассировка" else "Tracing"
    if (Messages.showYesNoDialog(project, if (isRu) "Выключить трассировку?" else "Disable tracing?", title, Messages.getQuestionIcon()) == Messages.YES) {
        if (removeGradleApply(project)) {
            val lpsDir = File(project.basePath ?: "", ".idea/lps")
            if (lpsDir.exists()) lpsDir.deleteRecursively()
            val settingsFile = File(project.basePath ?: "", ".idea/lps_trace_settings.txt")
            if (settingsFile.exists()) settingsFile.delete()
            LocalFileSystem.getInstance().refreshIoFiles(listOf(lpsDir, settingsFile))
            resetTraceUI()
            Messages.showInfoMessage(project, if (isRu) "Трассировка выключена." else "Tracing disabled.", title)
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.runDeepSync() {
    val isRu = currentLang == "RU"
    if (Messages.showYesNoDialog(project, if (isRu) "Синхронизировать иконки?" else "Sync icons?", if (isRu) "Синхронизация" else "Sync", Messages.getQuestionIcon()) == Messages.YES) {
        val scriptFile = ensureScriptsExist() ?: return
        bulkUpdateLabelsButton.isEnabled = false
        setStatusText(if (isRu) "🔍 Синхронизация..." else "🔍 Syncing...", true)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptFile.absolutePath).directory(scriptFile.parentFile.parentFile.parentFile).start()
                process.inputStream.bufferedReader().use { it.forEachLine { line ->
                    if (line.contains("|")) {
                        val parts = line.split("|"); if (parts.size >= 2) {
                            packageToLabel[parts[0].trim()] = parts[1].trim()
                            LPSLogExplanationProvider.writeToDictionary(project.basePath, parts[0].trim(), parts[1].trim(), projectPkg)
                        }
                    }
                }}
                process.waitFor()
                ApplicationManager.getApplication().invokeLater { 
                    loadInitialData(); reloadTreeSafely(); bulkUpdateLabelsButton.isEnabled = true; setStatusText(if (isRu) "Готово" else "Done", false) 
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { bulkUpdateLabelsButton.isEnabled = true; setStatusText("Error", false) }
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

fun LPSToolWindowFactory.LPSToolWindow.getGroupColor(groupName: String): java.awt.Color = when {
    groupName.contains("ПРОЕКТ") || groupName.contains("PROJECT") -> java.awt.Color(180, 150, 255)
    groupName.contains("ПОЛЬЗОВАТЕЛЬСКИЕ") || groupName.contains("USER") -> java.awt.Color(150, 255, 150)
    groupName.contains("ГУГЛ") || groupName.contains("GOOGLE") -> java.awt.Color(255, 255, 150)
    groupName.contains("ИНТЕРФЕЙС") || groupName.contains("INTERFACE") -> java.awt.Color(150, 255, 255)
    groupName.contains("СИСТЕМНЫЕ") || groupName.contains("SYSTEM") -> java.awt.Color(100, 150, 255)
    groupName.contains("НИЗКОУРОВНЕВЫЕ") || groupName.contains("LOW-LEVEL") -> java.awt.Color(200, 200, 200)
    groupName.contains("ЖЕЛЕЗО") || groupName.contains("HARDWARE") -> java.awt.Color(255, 180, 150)
    groupName.contains("СЕТЬ") || groupName.contains("NETWORK") -> java.awt.Color(150, 200, 255)
    groupName.contains("МЕДИА") || groupName.contains("MEDIA") -> java.awt.Color(255, 150, 255)
    else -> java.awt.Color.LIGHT_GRAY
}

fun LPSToolWindowFactory.LPSToolWindow.getAppIcon(pkgName: String): Icon {
    val cached = iconCache[pkgName]; if (cached != null) return cached
    val iconFile = File("${project.basePath}/on-device-server/src/icons/$pkgName.png")
    if (iconFile.exists()) {
        try {
            val img = ImageIcon(iconFile.absolutePath)
            val scaled = ImageIcon(img.image.getScaledInstance(16, 16, java.awt.Image.SCALE_SMOOTH))
            iconCache[pkgName] = scaled
            return scaled
        } catch (e: Exception) {}
    }
    return when {
        pkgName == projectPkg -> AllIcons.Nodes.HomeFolder
        pkgName.contains("google") -> AllIcons.Nodes.PpWeb
        pkgName.contains("android") -> AllIcons.General.Settings
        else -> AllIcons.Nodes.Package
    }
}

fun LPSToolWindowFactory.LPSToolWindow.refreshProcesses() {
    val device = currentDevice ?: return
    val isRu = currentLang == "RU"
    ApplicationManager.getApplication().executeOnPooledThread {
        pidToPackage.clear()
        device.clients.forEach { it.clientData.packageName?.let { pkg -> pidToPackage[it.clientData.pid] = pkg } }
        val processReceiver = object : MultiLineReceiver() { 
            override fun processNewLines(lines: Array<out String>) { 
                lines.forEach { line -> 
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8) { 
                        val pid = parts[1].toIntOrNull() ?: parts[0].toIntOrNull()
                        if (pid != null) pidToPackage[pid] = parts.last() 
                    } 
                } 
            } 
            override fun isCancelled() = isDisposed 
        }
        try { device.executeShellCommand("ps -A", processReceiver, 0, TimeUnit.MILLISECONDS) } catch (e: Exception) {}
        
        ApplicationManager.getApplication().invokeLater {
            if (isDisposed) return@invokeLater
            
            val expandedNames = mutableSetOf<List<String>>()
            for (i in 0 until processTree.rowCount) {
                if (processTree.isExpanded(i)) {
                    expandedNames.add(processTree.getPathForRow(i).path.map { (it as DefaultMutableTreeNode).userObject.toString() })
                }
            }

            rootNode.removeAllChildren()
            val pkgToPids = mutableMapOf<String, MutableList<Int>>()
            pidToPackage.forEach { (pid, pkg) -> if (pkg != "Unknown") pkgToPids.getOrPut(pkg) { mutableListOf() }.add(pid) }
            
            val allPackages = pkgToPids.keys.sorted()
            val usedPackages = mutableSetOf<String>()

            fun filterPackages(predicate: (String) -> Boolean): List<String> {
                val filtered = allPackages.filter { it !in usedPackages && predicate(it) }
                usedPackages.addAll(filtered)
                return filtered
            }

            // ⭐ МОЙ ПРОЕКТ
            val myProjectPackages = projectPkg?.let { listOf(it) } ?: emptyList()
            usedPackages.addAll(myProjectPackages)
            addGroupWithChildren(if (isRu) "⭐ МОЙ ПРОЕКТ" else "⭐ MY PROJECT", myProjectPackages, pkgToPids, true)
            
            // 🔍 ПРИЛОЖЕНИЯ ГУГЛ
            addGroupWithChildren(if (isRu) "🔍 ПРИЛОЖЕНИЯ ГУГЛ" else "🔍 GOOGLE APPS", filterPackages { it.startsWith("com.google.android.") && (it.contains("youtube") || it.contains("maps") || it.contains("chrome") || it.contains("gm") || it.contains("calendar") || it.contains("photos") || it.contains("vending")) }, pkgToPids)
            
            // ☁️ СЛУЖБЫ ГУГЛ
            addGroupWithChildren(if (isRu) "☁️ СЛУЖБЫ ГУГЛ" else "☁️ GOOGLE SERVICES", filterPackages { it.contains("google") }, pkgToPids)
            
            // 🖼️ ИНТЕРФЕЙС И ГРАФИКА
            addGroupWithChildren(if (isRu) "🖼️ ИНТЕРФЕЙС И ГРАФИКА" else "🖼️ INTERFACE & GRAPHICS", filterPackages { it.contains("systemui") || it.contains("launcher") || it.contains("surfaceflinger") || it.contains("wm.") || it.contains("wallpaper") || it.contains("renderengine") || it.contains("gpu") || it.contains("composer") }, pkgToPids)
            
            // 📡 СЕТЬ И СВЯЗЬ
            addGroupWithChildren(if (isRu) "📡 СЕТЬ И СВЯЗЬ" else "📡 NETWORK & CONNECTIVITY", filterPackages { it.contains("wifi") || it.contains("bluetooth") || it.contains("telephony") || it.contains("nfc") || it.contains("netd") || it.contains("networkstack") || it.contains("wpa_supplicant") || it.contains("phone") || it.contains("iptables") || it.contains("ipsec") || it.contains("modem") }, pkgToPids)
            
            // 🔊 МЕДИА И ЗВУК
            addGroupWithChildren(if (isRu) "🔊 МЕДИА И ЗВУК" else "🔊 MEDIA & SOUND", filterPackages { it.contains("audio") || it.contains("media") || it.contains("codec") || it.contains("drm") || it.contains("sound") || it.contains("video") || it.contains("camera") }, pkgToPids)
            
            // 🛠️ ЖЕЛЕЗО И ДРАЙВЕРЫ
            addGroupWithChildren(if (isRu) "🛠️ ЖЕЛЕЗО И ДРАЙВЕРЫ" else "🛠️ HARDWARE & DRIVERS", filterPackages { it.contains("hal") || it.contains("hardware") || it.contains("sensor") || it.contains("gps") || it.contains("fingerprint") || it.contains("thermal") || it.contains("light") || it.contains("power") || it.contains("usb") || it.contains("battery") }, pkgToPids)
            
            // ⚙️ СИСТЕМНЫЕ СЛУЖБЫ
            addGroupWithChildren(if (isRu) "⚙️ СИСТЕМНЫЕ СЛУЖБЫ" else "⚙️ SYSTEM SERVICES", filterPackages { it.startsWith("com.android.") || it.contains("providers") || it.contains("settings") || it.contains("system_server") || it.contains("permission") || it.contains("keystore") || it.contains("credstore") || it.contains("gatekeeper") || it.contains("security") }, pkgToPids)
            
            // 🧱 НИЗКОУРОВНЕВЫЕ
            addGroupWithChildren(if (isRu) "🧱 НИЗКОУРОВНЕВЫЕ" else "🧱 LOW-LEVEL", filterPackages { it.startsWith("[") || !it.contains(".") || it == "init" || it == "zygote" || it == "adbd" || it == "logd" || it.contains("logger") || it.contains("incident") || it.contains("crash") || it.contains("stats") || it == "sh" || it == "magisk" }, pkgToPids)
            
            // 👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ
            addGroupWithChildren(if (isRu) "👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ" else "👤 USER APPLICATIONS", filterPackages { it.contains(".") }, pkgToPids)
            
            // 📂 ПРОЧЕЕ
            addGroupWithChildren(if (isRu) "📂 ПРОЧЕЕ" else "📂 OTHER", allPackages.filter { it !in usedPackages }, pkgToPids)

            treeModel.reload()
            restoreExpansionState(rootNode, TreePath(rootNode), expandedNames)
        }
    }
}

fun LPSToolWindowFactory.LPSToolWindow.restoreExpansionState(node: DefaultMutableTreeNode, path: TreePath, expandedNames: Set<List<String>>) { 
    if (expandedNames.contains(path.path.map { (it as DefaultMutableTreeNode).userObject.toString() })) {
        processTree.expandPath(path)
    }
    for (i in 0 until node.childCount) { 
        val child = node.getChildAt(i) as DefaultMutableTreeNode
        restoreExpansionState(child, path.pathByAddingChild(child), expandedNames) 
    } 
}

fun LPSToolWindowFactory.LPSToolWindow.addGroupWithChildren(title: String, packages: List<String>, pkgMap: Map<String, List<Int>>, showIfEmpty: Boolean = false) { 
    if (packages.isEmpty() && !showIfEmpty) return
    val groupNode = DefaultMutableTreeNode(title)
    if (packages.isEmpty()) {
        groupNode.add(DefaultMutableTreeNode("нет активных процессов"))
    } else {
        packages.forEach { pkg -> 
            val pkgNode = DefaultMutableTreeNode(pkg)
            pkgMap[pkg]?.sorted()?.forEach { pid -> pkgNode.add(DefaultMutableTreeNode(pid.toString())) }
            groupNode.add(pkgNode)
        }
    }
    rootNode.add(groupNode) 
}

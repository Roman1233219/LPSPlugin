package com.example.logkatplugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

object LogExplanationProvider {

    const val DICTIONARY_FILENAME = ".idea/logkat_dictionary.txt"
    const val DESCRIPTIONS_FILENAME = ".idea/logkat_descriptions.txt"

    private val processDescriptions = mutableMapOf<String, String>()
    private val tagDescriptions = mutableMapOf<String, String>()
    private val errorDescriptions = mutableMapOf<String, String>()
    private var lastProjectPath: String? = null

    fun loadAllDescriptions(projectPath: String?) {
        lastProjectPath = projectPath
        if (projectPath == null) return
        val file = File(projectPath, DESCRIPTIONS_FILENAME)
        if (!file.exists()) initDefaultDescriptions(file)

        processDescriptions.clear()
        tagDescriptions.clear()
        errorDescriptions.clear()

        var currentSection = ""
        try {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        currentSection = trimmed.uppercase()
                    } else if (trimmed.contains("=")) {
                        val parts = trimmed.split("=", limit = 2)
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (currentSection) {
                            "[PROCESSES]" -> processDescriptions[key] = value
                            "[TAGS]" -> tagDescriptions[key] = value
                            "[ERRORS]" -> errorDescriptions[key] = value
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    fun getRussianProcessDescription(pkg: String): String {
        val raw = processDescriptions[pkg]
        val desc = if (raw == "!") "Данных нет" else raw ?: when {
            pkg.contains("google") -> "Службы и сервисы Google."
            pkg.contains("android") -> "Системный компонент ОС Android."
            else -> "Данных нет"
        }
        return "<html><body style='width: 300px;'>$desc</body></html>"
    }

    fun getDetailedStackTraceExplanation(message: String): String {
        for ((key, explanation) in errorDescriptions) {
            if (explanation != "!" && message.contains(key, ignoreCase = true)) return explanation
        }
        return "Подробности ошибки:\n$message\n\nСовет: Проанализируйте StackTrace."
    }

    fun getProjectPackageName(project: Project): String? {
        try {
            val manifests = FilenameIndex.getFilesByName(project, "AndroidManifest.xml", GlobalSearchScope.projectScope(project))
            for (file in manifests) {
                if (file is XmlFile) {
                    val pkg = file.rootTag?.getAttributeValue("package")
                    if (pkg != null) return pkg
                }
            }
        } catch (e: Exception) {}
        return null
    }

    fun getProjectAppName(project: Project): String? {
        try {
            val manifests = FilenameIndex.getFilesByName(project, "AndroidManifest.xml", GlobalSearchScope.projectScope(project))
            for (file in manifests) {
                if (file is XmlFile) {
                    val label = file.rootTag?.findFirstSubTag("application")?.getAttributeValue("android:label")
                    if (label != null) {
                        if (label.startsWith("@string/")) {
                            val resName = label.substringAfter("/")
                            return findStringResourceValue(project, resName) ?: "Project ($resName)"
                        }
                        return label
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun findStringResourceValue(project: Project, resName: String): String? {
        try {
            val stringsFiles = FilenameIndex.getFilesByName(project, "strings.xml", GlobalSearchScope.projectScope(project))
            for (psiFile in stringsFiles) {
                if (psiFile is XmlFile) {
                    val tags = psiFile.rootTag?.findSubTags("string") ?: continue
                    for (tag in tags) {
                        if (tag.getAttributeValue("name") == resName) return tag.value.text.trim()
                    }
                }
            }
        } catch (e: Exception) {}
        return null
    }

    fun loadDictionary(projectPath: String?): Map<String, String> {
        if (projectPath == null) return emptyMap()
        val file = File(projectPath, DICTIONARY_FILENAME)
        if (!file.exists()) initDefaultDictionary(file)
        val result = mutableMapOf<String, String>()
        try {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.contains("=") && !trimmed.startsWith("#") && !trimmed.startsWith("[")) {
                        val parts = trimmed.split("=", limit = 2)
                        val value = parts[1].trim()
                        if (value != "!") result[parts[0].trim()] = value
                    }
                }
            }
        } catch (e: Exception) {}
        return result
    }

    @Synchronized
    fun writeToDictionary(projectPath: String?, pkg: String, label: String?, projectPkg: String?) {
        if (projectPath == null || pkg == projectPkg || pkg.contains("example") || pkg.startsWith("[") || pkg.endsWith("]")) return
        
        val file = File(projectPath, DICTIONARY_FILENAME)
        if (!file.exists()) initDefaultDictionary(file)
        val lines = file.readLines().toMutableList()
        
        val finalLabel = if (label.isNullOrEmpty() || label == pkg) "!" else label
        if (finalLabel == "!") return // Не тратим время, если имени все равно нет

        // Проверяем, есть ли уже этот пакет
        val existingIndex = lines.indexOfFirst { it.startsWith("$pkg=") }
        
        if (existingIndex != -1) {
            val currentVal = lines[existingIndex].split("=")[1].trim()
            if (currentVal == "!") {
                // Если было "нет данных", заменяем на реальное имя
                lines[existingIndex] = "$pkg=$finalLabel"
            } else {
                // Если имя уже есть, не трогаем (бережем ручные правки)
                return
            }
        } else {
            // Новая запись
            val section = when {
                pkg.contains("google") -> "[GOOGLE]"
                pkg.contains("android") || pkg.contains("system") -> "[SYSTEM]"
                else -> "[EXTERNAL]"
            }
            val sectionIndex = lines.indexOfFirst { it.trim() == section }
            if (sectionIndex != -1) lines.add(sectionIndex + 1, "$pkg=$finalLabel")
            else lines.add("$pkg=$finalLabel")
        }
        
        file.writeText(lines.joinToString("\n"))
    }

    @Synchronized
    fun syncPackages(projectPath: String?, userPackages: List<String>, systemPackages: List<String>) {
        if (projectPath == null) return
        val file = File(projectPath, DICTIONARY_FILENAME)
        if (!file.exists()) initDefaultDictionary(file)
        
        val lines = file.readLines().toMutableList()
        val existingPackages = mutableSetOf<String>()
        
        lines.forEach { line ->
            if (line.contains("=") && !line.startsWith("#") && !line.startsWith("[")) {
                existingPackages.add(line.split("=")[0].trim())
            }
        }

        fun addNewPackages(packages: List<String>, sectionName: String) {
            val toAdd = packages.filter { it !in existingPackages }.map { "$it=!" }
            if (toAdd.isEmpty()) return
            
            var sectionIndex = lines.indexOfFirst { it.trim() == sectionName }
            if (sectionIndex == -1) {
                lines.add("")
                lines.add(sectionName)
                sectionIndex = lines.size - 1
            }
            lines.addAll(sectionIndex + 1, toAdd)
            existingPackages.addAll(packages)
        }

        addNewPackages(systemPackages, "[SYSTEM]")
        addNewPackages(userPackages, "[EXTERNAL]")

        file.writeText(lines.joinToString("\n"))
        LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
    }

    private fun refreshFiles(projectPath: String?) {
        if (projectPath == null) return
        val files = listOf(
            File(projectPath, DICTIONARY_FILENAME),
            File(projectPath, DESCRIPTIONS_FILENAME)
        )
        LocalFileSystem.getInstance().refreshIoFiles(files)
    }

    fun resetToDefault(projectPath: String?) {
        if (projectPath == null) return
        initDefaultDictionary(File(projectPath, DICTIONARY_FILENAME))
        initDefaultDescriptions(File(projectPath, DESCRIPTIONS_FILENAME))
        refreshFiles(projectPath)
        loadAllDescriptions(projectPath)
    }

    private fun initDefaultDescriptions(file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText("""
[PROCESSES]
system_server=Ядро системы Android. Управляет всеми окнами, питанием, уведомлениями и связью между приложениями.
surfaceflinger=Графика (SurfaceFlinger). Собирает кадры и выводит их на экран.
init=Первый процесс в системе, запускаемый ядром.
zygote=Материнский процесс для всех Android-приложений.
audioserver=Звуковая служба. Управляет аудио-потоками.
mediaserver=Работа камеры, видео и кодеков.
logd=Демон логирования. Принимает и буферизирует все логи.
servicemanager=Диспетчер служб. Реестр всех Binder-сервисов.
hwservicemanager=Диспетчер аппаратных служб (HAL).
netd=Сетевой демон. Управляет интерфейсами и брандмауэром.
wpa_supplicant=Управление Wi-Fi подключениями.
keystore=Хранилище ключей и сертификатов.
gatekeeperd=Проверка PIN-кодов и паролей.
vold=Менеджер памяти и внешних накопителей.
installd=Менеджер установки приложений.
statsd=Сборщик статистики системы.
dumpstate=Сборщик отчетов об ошибках.
tombstoned=Регистратор падений (дампов памяти).
incidentd=Сборщик инцидентов.
apexd=Менеджер системных модулей APEX.
drmserver=Защита авторских прав медиа.
mediaextractor=Извлечение метаданных медиа-файлов.
mdnsd=Сетевое обнаружение устройств.
wificond=Низкоуровневая служба Wi-Fi.
time_daemon=Синхронизация времени.
thermald=Контроль температуры устройства.
perfd=Оптимизация производительности.
storaged=Менеджер состояний памяти.
networkstack=Сетевой стек Android.
renderengine=Движок отрисовки интерфейса.
sensorservice=Управление датчиками (гироскоп, акселерометр).
adreno=Драйвер графического процессора Adreno.
mali=Драйвер графического процессора Mali.

[TAGS]
DisplayManagerService=Экран: Система меняет частоту обновления (например, 120Гц) или режим отображения для окна.
ActivityManager=Управление активностями и процессами приложений.
WindowManager=Управление окнами и их расположением на экране.
InputDispatcher=Обработка касаний и нажатий клавиш.

[ERRORS]
NullPointerException=Попытка обращения к объекту, который не был инициализирован (null).
IndexOutOfBoundsException=Индекс вне границ массива или списка.
NetworkOnMainThreadException=Попытка выполнить сетевой запрос в главном (UI) потоке.
OutOfMemoryError=Приложению не хватило оперативной памяти для работы.
SecurityException=Ошибка безопасности. Отсутствует необходимое разрешение (Permission).
        """.trimIndent())
    }

    private fun initDefaultDictionary(file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText("""
 # Своё приложение не записывается

 [GOOGLE]
 com.google.android.gms=Сервисы Google Play
 com.android.vending=Google Play Store
 com.google.android.googlequicksearchbox=Google Поиск
 com.google.android.apps.maps=Google Карты
 com.google.android.youtube=YouTube

 [SYSTEM_CORE]
 # СИСТЕМНЫЕ ПРОЦЕССЫ: ИМЕНА
 system_server=Система Android
 surfaceflinger=Graphics (SurfaceFlinger)
 init=Core (Init)
 zygote=App Launcher (Zygote)
 audioserver=Audio Service
 mediaserver=Media Server
 cameraserver=Camera Server
 logd=Log Service
 servicemanager=Binder Manager
 netd=Network (Netd)
 vold=Storage Manager
 installd=Install Manager
 hwservicemanager=HAL Manager
 wpa_supplicant=Wi-Fi Service
 keystore=Keystore
 gatekeeperd=Security Service
 statsd=Stats Collector

 [SYSTEM]
 # СИСТЕМНЫЕ ПАКЕТЫ: ИМЕНА
 com.android.systemui=System UI
 com.android.phone=Phone / Radio
 com.android.settings=Settings
 com.android.launcher3=Launcher
 com.android.providers.settings=Settings Storage
 com.android.vcalendar=Calendar (System)

 [EXTERNAL]
 org.videolan.vlc=VLC Player
 com.whatsapp=WhatsApp
 com.instagram.android=Instagram
 com.facebook.katana=Facebook

 [UNSORTED]

        """.trimIndent())
    }

    @Synchronized
    private fun writeToDescriptions(section: String, key: String, value: String) {
        val path = lastProjectPath ?: return
        val file = File(path, DESCRIPTIONS_FILENAME)
        if (!file.exists()) initDefaultDescriptions(file)
        
        val lines = file.readLines().toMutableList()
        if (lines.any { it.startsWith("$key=") }) return

        val sectionName = section.uppercase()
        val index = lines.indexOfFirst { it.trim().uppercase() == sectionName }
        if (index != -1) {
            lines.add(index + 1, "$key=$value")
        } else {
            lines.add("")
            lines.add(sectionName)
            lines.add("$key=$value")
        }
        file.writeText(lines.joinToString("\n"))
    }

    private fun extractErrorName(message: String): String? {
        val regex = Regex("([a-zA-Z0-9._]+(?:Exception|Error))")
        return regex.find(message)?.groupValues?.get(1)
    }

    fun getFallbackLabel(pkg: String): String? = null

    fun getSmartLogExplanation(pkgName: String?, tag: String, message: String, level: String): String {
        if (pkgName != null && !processDescriptions.containsKey(pkgName)) {
            writeToDescriptions("[PROCESSES]", pkgName, "!")
            processDescriptions[pkgName] = "!"
        }

        val rawProcessDesc = pkgName?.let { processDescriptions[it] }
        val processDesc = if (rawProcessDesc == null || rawProcessDesc == "!") "Данных нет" else rawProcessDesc
        
        val tagDesc = tagDescriptions[tag]
        val foundErrorEntry = errorDescriptions.entries.find { message.contains(it.key, ignoreCase = true) }
        
        val sb = StringBuilder("<html><body style='width: 450px; padding: 10px;'>")
        sb.append("<div style='font-size: 16px;'><b>Управляющий процесс:</b> ${pkgName ?: "Неизвестно"}</div>")
        sb.append("<div style='font-size: 15px; margin-top: 5px;'>$processDesc</div>")
        
        if (level == "E" || tagDesc != null) {
            sb.append("<hr style='margin: 12px 0;'>")
            
            if (tagDesc != null) {
                val displayTagDesc = if (tagDesc == "!") "Данных нет" else tagDesc
                sb.append("<div style='font-size: 15px;'>$displayTagDesc</div>")
            }
            
            if (level == "E") {
                if (tagDesc != null) sb.append("<div style='margin-top: 10px;'></div>")
                
                val extracted = extractErrorName(message)
                if (extracted != null && !errorDescriptions.containsKey(extracted)) {
                    writeToDescriptions("[ERRORS]", extracted, "!")
                    errorDescriptions[extracted] = "!"
                }

                val errorKey = foundErrorEntry?.key ?: extracted
                val errorVal = foundErrorEntry?.value ?: "!"
                val displayErrorVal = if (errorVal == "!") "Описание отсутствует." else errorVal
                
                if (errorKey != null) {
                    sb.append("<div style='font-size: 16px;'><b>Ошибка:</b> $errorKey</div>")
                    sb.append("<div style='font-size: 15px; margin-top: 5px;'>$displayErrorVal</div>")
                }
                sb.append("<div style='font-size: 14px; margin-top: 8px;'>Изучите <a href='show_stacktrace' style='color: #589df6;'>StackTrace</a> для деталей.</div>")
            }
        }
        
        sb.append("</body></html>")
        return sb.toString()
    }
}

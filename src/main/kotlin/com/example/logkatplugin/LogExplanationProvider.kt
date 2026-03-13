package com.example.logkatplugin

import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

object LogExplanationProvider {

    const val DICTIONARY_FILENAME = ".idea/logkat_dictionary.txt"
    const val DESCRIPTIONS_FILENAME = ".idea/logkat_descriptions.txt"

    private val processDescriptions = mutableMapOf<String, String>()
    private val errorDescriptions = mutableMapOf<String, String>()

    fun loadAllDescriptions(projectPath: String?) {
        if (projectPath == null) return
        val file = File(projectPath, DESCRIPTIONS_FILENAME)
        if (!file.exists()) initDefaultDescriptions(file)

        processDescriptions.clear()
        errorDescriptions.clear()

        var currentSection = ""
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.uppercase()
                } else if (trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (currentSection == "[PROCESSES]") processDescriptions[key] = value
                    else if (currentSection == "[ERRORS]") errorDescriptions[key] = value
                }
            }
        } catch (e: Exception) {}
    }

    fun getRussianProcessDescription(pkg: String): String {
        val desc = processDescriptions[pkg] ?: when {
            pkg.contains("google") -> "Службы и сервисы Google."
            pkg.contains("android") -> "Системный компонент ОС Android."
            else -> "Пакет: $pkg"
        }
        return "<html><body style='width: 250px;'>$desc</body></html>"
    }

    fun getDetailedStackTraceExplanation(message: String): String {
        for ((key, explanation) in errorDescriptions) {
            if (message.contains(key, ignoreCase = true)) return explanation
        }
        return "Подробности ошибки:\n$message\n\nСовет: Проанализируйте StackTrace."
    }

    fun getProjectPackageName(project: Project): String? {
        try {
            val manifests = FilenameIndex.getFilesByName(project, "AndroidManifest.xml", GlobalSearchScope.projectScope(project))
            for (file in manifests) {
                if (file is XmlFile) return file.rootTag?.getAttributeValue("package")
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
        val stringsFiles = FilenameIndex.getFilesByName(project, "strings.xml", GlobalSearchScope.projectScope(project))
        for (psiFile in stringsFiles) {
            if (psiFile is XmlFile) {
                val tags = psiFile.rootTag?.findSubTags("string") ?: continue
                for (tag in tags) {
                    if (tag.getAttributeValue("name") == resName) return tag.value.text.trim()
                }
            }
        }
        return null
    }

    fun loadDictionary(projectPath: String?): Map<String, String> {
        if (projectPath == null) return emptyMap()
        val file = File(projectPath, DICTIONARY_FILENAME)
        if (!file.exists()) initDefaultDictionary(file)
        val result = mutableMapOf<String, String>()
        try {
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.contains("=") && !trimmed.startsWith("#") && !trimmed.startsWith("[")) {
                    val parts = trimmed.split("=", limit = 2)
                    val value = parts[1].trim()
                    if (value != "!") result[parts[0].trim()] = value
                }
            }
        } catch (e: Exception) {}
        return result
    }

    @Synchronized
    fun writeToDictionary(projectPath: String?, pkg: String, label: String?, projectPkg: String?) {
        if (projectPath == null || pkg == projectPkg || pkg.startsWith("[") || pkg.endsWith("]")) return
        val file = File(projectPath, DICTIONARY_FILENAME)
        if (!file.exists()) initDefaultDictionary(file)
        val lines = file.readLines().toMutableList()
        if (lines.any { it.startsWith("$pkg=") }) return
        val finalLabel = if (label.isNullOrEmpty() || label == pkg) "!" else label
        val section = when {
            pkg.contains("google") -> "[GOOGLE]"
            pkg.contains("android") || pkg.contains("system") -> "[SYSTEM]"
            else -> "[EXTERNAL]"
        }
        val index = lines.indexOfFirst { it.trim() == section }
        if (index != -1) lines.add(index + 1, "$pkg=$finalLabel")
        else lines.add("$pkg=$finalLabel")
        file.writeText(lines.joinToString("\n"))
    }

    fun resetToDefault(projectPath: String?) {
        if (projectPath == null) return
        initDefaultDictionary(File(projectPath, DICTIONARY_FILENAME))
        initDefaultDescriptions(File(projectPath, DESCRIPTIONS_FILENAME))
    }

    private fun initDefaultDescriptions(file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText("""
[PROCESSES]
init=<b>Ядро (Init).</b> Первый процесс в системе, запускаемый ядром.
system_server=<b>Ядро системы (System Server).</b> Управляет окнами, питанием, датчиками.
surfaceflinger=<b>Графика (SurfaceFlinger).</b> Собирает кадры и выводит их на экран.
audioserver=<b>Звуковая служба (AudioServer).</b> Управляет аудио-потоками.
mediaserver=<b>Медиа-движок (MediaServer).</b> Работа камеры, видео и кодеков.
zygote=<b>Материнский процесс (Zygote).</b> Процесс-шаблон для запуска приложений.
logd=<b>Демон логирования (Logd).</b> Принимает и буферизирует все логи.
servicemanager=<b>Диспетчер служб.</b> Реестр всех Binder-сервисов.
hwservicemanager=<b>Диспетчер аппаратных служб.</b> Реестр для HAL-сервисов.
netd=<b>Сетевой демон (Netd).</b> Управляет интерфейсами и брандмауэром.
wpa_supplicant=<b>Wi-Fi Supplicant.</b> Управляет подключением к Wi-Fi сетям.
keystore=<b>Хранилище ключей.</b> Защищенное хранилище паролей и сертификатов.
gatekeeperd=<b>Сторож экрана (Gatekeeper).</b> Проверяет PIN-коды и пароли.
statsd=<b>Сборщик статистики (Statsd).</b> Собирает метрики использования.
dumpstate=<b>Сборщик отчета (Dumpstate).</b> Собирает логи для bug-репорта.
tombstoned=<b>Регистратор падений.</b> Записывает дампы памяти при падении.
incidentd=<b>Сборщик инцидентов.</b> Собирает отчеты о проблемах.
apexd=<b>Менеджер APEX.</b> Управляет модульными системными компонентами.
cameraserver=<b>Сервер камеры.</b> Управляет доступом к камере.
drmserver=<b>Защита контента (DRM).</b> Управляет лицензиями медиа.
vold=<b>Volume Daemon (Vold).</b> Управляет подключением дисков и SD-карт.
installd=<b>Install Daemon.</b> Выполняет установку и удаление приложений.
sensorservice=<b>Sensor Service.</b> Раздает данные с датчиков.
renderengine=<b>Движок отрисовки.</b> Отвечает за интерфейс.

[ERRORS]
NullPointerException=NullPointerException: Попытка обратиться к объекту null.
IndexOutOfBoundsException=IndexOutOfBoundsException: Неверный индекс в массиве или списке.
NetworkOnMainThreadException=NetworkOnMainThreadException: Сетевой запрос в UI-потоке!
OutOfMemoryError=OutOfMemoryError: Нехватка оперативной памяти.
Resources.NotFoundException=Resources.NotFoundException: Ресурс не найден. Проверьте ID.
ClassCastException=ClassCastException: Неверное приведение типов объектов.
SecurityException=SecurityException: Отсутствует разрешение. Проверьте Manifest.
ActivityNotFoundException=ActivityNotFoundException: Не найден компонент для запуска Activity.
CalledFromWrongThreadException=CalledFromWrongThreadException: Изменение UI не из главного потока.
ANR=ANR: Главный поток заблокирован более чем на 5 сек.
        """.trimIndent())
    }

    private fun initDefaultDictionary(file: File) {
        if (!file.parentFile.exists()) file.parentFile.mkdirs()
        file.writeText("""
[MY_PROJECT]
# Твое приложение берется напрямую из IDE и сюда не пишется

[GOOGLE]
com.google.android.gms=Сервисы Google Play
com.android.vending=Google Play Store
com.google.android.googlequicksearchbox=Google Поиск
com.google.android.apps.maps=Google Карты
com.google.android.youtube=YouTube

[SYSTEM]
system_server=Система Android
surfaceflinger=Графика (SurfaceFlinger)
init=Ядро (Init)
zygote=Запуск приложений (Zygote)
zygote64=Запуск приложений (Zygote64)
com.android.systemui=Интерфейс системы
com.android.phone=Телефон / Радио
com.android.settings=Настройки
com.android.launcher3=Рабочий стол
audioserver=Аудио-служба
mediaserver=Медиа-сервер
cameraserver=Сервер камеры
logd=Служба логов
servicemanager=Диспетчер Binder
hwservicemanager=Диспетчер HAL
netd=Сеть (Netd)
wpa_supplicant=Wi-Fi (WPA)
keystore=Хранилище ключей
keystore2=Хранилище ключей 2
gatekeeperd=Защита (Gatekeeper)
vold=Менеджер памяти (Vold)
installd=Менеджер установки
statsd=Сборщик статистики
dumpstate=Сборщик отчета
tombstoned=Регистратор падений
incidentd=Сборщик инцидентов
apexd=Менеджер APEX
drmserver=Защита контента (DRM)
mediaextractor=Извлечение медиа
mdnsd=Сетевое обнаружение (mDNS)
wificond=Демон Wi-Fi
time_daemon=Демон времени
thermald=Термальный демон
perfd=Производительность
storaged=Монитор хранилища
networkstack=Сетевой стек
renderengine=Движок отрисовки
sensorservice=Служба датчиков
adreno=Драйвер GPU Adreno
mali=Драйвер GPU Mali

[EXTERNAL]
org.videolan.vlc=VLC Player
com.whatsapp=WhatsApp
com.instagram.android=Instagram
com.facebook.katana=Facebook

[UNSORTED]
        """.trimIndent())
    }

    fun getFallbackLabel(pkg: String): String? = null

    fun getSmartLogExplanation(pkgName: String?, tag: String, message: String, level: String): String {
        return "<html><body><b>Процесс:</b> ${pkgName ?: "Неизвестно"}<br><b>Тег:</b> $tag<br><hr>$message</body></html>"
    }
}

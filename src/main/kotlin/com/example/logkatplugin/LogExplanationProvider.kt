package com.example.logkatplugin

object LogExplanationProvider {

    // 1. Описания процессов (O(1) доступ)
    private val PROCESS_MAP = mapOf(
        "init" to "<b>Прародитель (Init).</b> Первый процесс в системе, запускаемый ядром. Порождает все остальные системные демоны.",
        "system_server" to "<b>Ядро системы (System Server).</b> Управляет всеми окнами, питанием, уведомлениями, датчиками и безопасностью.",
        "surfaceflinger" to "<b>Графический композитор (SurfaceFlinger).</b> Собирает кадры от всех приложений и выводит их на экран через GPU.",
        "audioserver" to "<b>Звуковая служба (AudioServer).</b> Управляет всеми аудио-потоками.",
        "mediaserver" to "<b>Медиа-движок (MediaServer).</b> Работа камеры, видео и кодеков.",
        "zygote" to "<b>Материнский процесс (Zygote).</b> Процесс-шаблон для запуска приложений.",
        "zygote64" to "<b>Материнский процесс (Zygote).</b> Процесс-шаблон для запуска приложений.",
        "logd" to "<b>Демон логирования (Logd).</b> Принимает и буферизирует все логи от всех процессов.",
        "servicemanager" to "<b>Диспетчер служб.</b> Реестр всех Binder-сервисов. Помогает процессам находить друг друга.",
        "hwservicemanager" to "<b>Диспетчер аппаратных служб.</b> Реестр для HAL-сервисов (аппаратного уровня).",
        "netd" to "<b>Сетевой демон (Netd).</b> Управляет сетевыми интерфейсами, правилами брандмауэра и VPN.",
        "wpa_supplicant" to "<b>Wi-Fi Supplicant.</b> Управляет подключением к защищенным Wi-Fi сетям (WPA, WPA2).",
        "keystore" to "<b>Хранилище ключей (Keystore).</b> Защищенное хранилище криптографических ключей, паролей и сертификатов.",
        "keystore2" to "<b>Хранилище ключей (Keystore).</b> Защищенное хранилище криптографических ключей, паролей и сертификатов.",
        "gatekeeperd" to "<b>Сторож экрана (Gatekeeper).</b> Проверяет PIN-коды, пароли и графические ключи при разблокировке.",
        "statsd" to "<b>Сборщик статистики (Statsd).</b> Собирает анонимную диагностику и метрики использования системы.",
        "dumpstate" to "<b>Сборщик отчета (Dumpstate).</b> Собирает все логи и информацию при создании bug-репорта.",
        "tombstoned" to "<b>Регистратор падений (Tombstoned).</b> Записывает дампы памяти (tombstone) при падении нативных процессов.",
        "incidentd" to "<b>Сборщик инцидентов.</b> Собирает структурированные отчеты о проблемах для отправки разработчикам.",
        "apexd" to "<b>Менеджер APEX.</b> Управляет модульными системными компонентами (APEX-пакетами) для обновлений Android.",
        "cameraserver" to "<b>Сервер камеры.</b> Управляет доступом к камере, обработкой изображений и передачей данных приложениям.",
        "drmserver" to "<b>Защита контента (DRM).</b> Управляет лицензиями на защищенный медиаконтент.",
        "mediaextractor" to "<b>Извлечение медиа.</b> Разбирает медиафайлы на дорожки (аудио, видео, субтитры).",
        "mdnsd" to "<b>Сетевое обнаружение (mDNS).</b> Позволяет находить устройства в локальной сети (Chromecast, принтеры).",
        "clatd" to "<b>Переход на IPv6 (CLAT).</b> Помогает приложениям, работающим только с IPv4, работать в IPv6-сетях.",
        "wificond" to "<b>Демон Wi-Fi (Wificond).</b> Низкоуровневое взаимодействие с драйвером Wi-Fi.",
        "hostapd" to "<b>Точка доступа.</b> Запускает режим модема (Wi-Fi точки доступа).",
        "time_daemon" to "<b>Демон времени.</b> Синхронизирует системное время с аппаратными часами и сетью.",
        "thermald" to "<b>Термальный демон.</b> Следит за температурой и снижает частоты при перегреве.",
        "perfd" to "<b>Демон производительности.</b> Оптимизирует частоты CPU/GPU для плавности работы.",
        "storaged" to "<b>Монитор хранилища.</b> Следит за скоростью работы и состоянием внутренней памяти.",
        "iorapd" to "<b>Предиктор ввода/вывода.</b> Предсказывает, какие файлы понадобятся приложению.",
        "webview_zygote" to "<b>Процесс-шаблон WebView.</b> Отдельный Zygote для WebView, чтобы изолировать рендеринг.",
        "statscompanion" to "<b>Спутник статистики.</b> Помогает statsd обрабатывать сложные метрики.",
        "networkstack" to "<b>Сетевой стек.</b> Управляет IP-адресацией, DHCP и DNS-запросами.",
        "ipacm" to "<b>Менеджер IP-адресов.</b> Распределяет IP-адреса при использовании модема.",
        "IPACM" to "<b>Менеджер IP-адресов.</b> Распределяет IP-адреса при использовании модема.",
        "vold" to "<b>Volume Daemon (Vold).</b> Управляет подключением/отключением томов (SD-карты, USB OTG).",
        "installd" to "<b>Install Daemon.</b> Выполняет установку, удаление приложений и очистка кеша.",
        "adreno" to "<b>GPU Adreno.</b> Логи от драйвера GPU Qualcomm Adreno.",
        "kgsl" to "<b>GPU Driver (kgsl).</b> Низкоуровневый драйвер Qualcomm GPU.",
        "mali" to "<b>GPU Mali.</b> Логи от драйвера GPU ARM Mali.",
        "renderengine" to "<b>Движок отрисовки (RenderEngine).</b> Отвечает за 2D и 3D отрисовку интерфейса.",
        "charger" to "<b>Режим зарядки (Charger).</b> Показывает анимацию батареи при выключенном телефоне.",
        "batteryd" to "<b>Демон батареи.</b> Собирает статистику заряда, температуры и напряжения батареи.",
        "gmscore" to "<b>Google Play Services (ядро).</b> Обеспечивает работу аккаунта, синхронизацию и Firebase.",
        "firebase" to "<b>Firebase.</b> Облачные сервисы Google: аналитика, пуши, базы данных.",
        "sensorservice" to "<b>Sensor Service.</b> Собирает данные со всех датчиков и раздает их приложениям."
    )

    // 2. Анализ StackTrace
    private val ERROR_RULES = listOf(
        "NullPointerException" to "NullPointerException: Попытка обратиться к объекту null. Проверьте инициализацию переменной.",
        "IndexOutOfBoundsException" to "IndexOutOfBoundsException: Неверный индекс в массиве или списке. Проверьте размер коллекции.",
        "NetworkOnMainThreadException" to "NetworkOnMainThreadException: Сетевой запрос в UI-потоке! Используйте Coroutines (Dispatchers.IO).",
        "OutOfMemoryError" to "OutOfMemoryError: Нехватка оперативной памяти. Оптимизируйте работу с изображениями.",
        "Resources.NotFoundException" to "Resources.NotFoundException: Ресурс не найден. Проверьте ID в R.id или XML.",
        "ClassCastException" to "ClassCastException: Неверное приведение типов объектов.",
        "ArithmeticException" to "ArithmeticException: Арифметическая ошибка (например, деление на ноль).",
        "NumberFormatException" to "NumberFormatException: Ошибка преобразования строки в число.",
        "IllegalArgumentException" to "IllegalArgumentException: Передан недопустимый аргумент.",
        "IllegalStateException" to "IllegalStateException: Объект в недопустимом состоянии для операции.",
        "UnsupportedOperationException" to "UnsupportedOperationException: Операция не поддерживается.",
        "BadTokenException" to "BadTokenException: Activity уже закрыта, невозможно показать диалог.",
        "Fragment" to "IllegalStateException (Fragment): Проблема с FragmentManager (транзакция после сохранения состояния).",
        "SecurityException" to "SecurityException: Отсутствует разрешение. Проверьте Manifest и динамические права.",
        "ActivityNotFoundException" to "ActivityNotFoundException: Не найден компонент для запуска Activity.",
        "PackageManager.NameNotFoundException" to "NameNotFoundException: Пакет или компонент не найден.",
        "CalledFromWrongThreadException" to "CalledFromWrongThreadException: Изменение UI не из главного потока.",
        "SQLiteConstraintException" to "SQLiteConstraintException: Нарушение ограничения БД (например, UNIQUE).",
        "SQLiteDatabaseLockedException" to "SQLiteDatabaseLockedException: База данных заблокирована.",
        "SQLiteFullException" to "SQLiteFullException: Нет места на диске для БД.",
        "CursorIndexOutOfBoundsException" to "CursorIndexOutOfBoundsException: Обращение к несуществующей строке курсора.",
        "ConnectException" to "ConnectException: Ошибка соединения (отказ сервера или таймаут).",
        "SocketTimeoutException" to "SocketTimeoutException: Таймаут ожидания данных.",
        "UnknownHostException" to "UnknownHostException: Не удалось разрешить доменное имя (DNS).",
        "SSLHandshakeException" to "SSLHandshakeException: Ошибка SSL-сертификата.",
        "JSONException" to "JSONException: Ошибка разбора или отсутствие поля в JSON.",
        "JsonSyntaxException" to "JsonSyntaxException: Ошибка синтаксиса JSON (Gson/Moshi).",
        "CancellationException" to "CancellationException: Корутина отменена (штатное поведение).",
        "FileNotFoundException" to "FileNotFoundException: Файл не найден.",
        "TransactionTooLargeException" to "TransactionTooLargeException: Слишком много данных для Binder (> 1 МБ).",
        "DeadObjectException" to "DeadObjectException: Процесс на другом конце Binder умер.",
        "ANR" to "ANR: Главный поток заблокирован более чем на 5 сек. Вынесите тяжелый код в фоновый поток."
    )

    fun getDetailedStackTraceExplanation(message: String): String {
        for ((key, explanation) in ERROR_RULES) {
            if (message.contains(key, ignoreCase = true)) return explanation
        }
        return "Подробности ошибки:\n$message\n\nСовет: Проанализируйте StackTrace."
    }

    fun getRussianProcessDescription(pkg: String): String {
        val exactMatch = PROCESS_MAP[pkg]
        if (exactMatch != null) return "<html><body style='width: 300px;'>$exactMatch</body></html>"

        val description = when {
            pkg.contains("example") -> "<b>Твой проект.</b> Твое приложение, которое ты сейчас отлаживаешь."
            pkg.contains("google") -> "<b>Службы Google.</b> Аккаунты, синхронизация, Play Store и GMS."
            pkg.contains("systemui") -> "<b>Интерфейс системы.</b> Шторка, уведомления, навигация и часы."
            pkg.contains("android") -> "<b>Системный компонент Android.</b> Служебный процесс операционной системы."
            else -> "<b>Процесс: $pkg.</b> Работает в своей изолированной среде (Sandbox)."
        }
        return "<html><body style='width: 300px;'>$description</body></html>"
    }

    fun getProcessColor(pkgName: String?): String {
        if (pkgName == null) return "gray"
        val sysProcesses = listOf("system_server", "surfaceflinger", "zygote", "init")
        if (pkgName in sysProcesses) return "purple"
        
        return when {
            pkgName.contains("google") -> "red"
            pkgName.contains("android") -> "green"
            pkgName.contains("example") -> "gold"
            else -> "gray"
        }
    }

    private fun parseFps(message: String): String {
        val match = Regex("fps=([0-9.]+)").find(message)
        return match?.groupValues?.get(1) ?: "?"
    }

    fun getSmartLogExplanation(pkgName: String?, tag: String, message: String, level: String): String {
        val processInfo = if (pkgName != null) "<b>Отправитель:</b> $pkgName<br>" else ""
        val rawDesc = getRussianProcessDescription(pkgName ?: "")
        val cleanDesc = rawDesc.replace("<html><body style='width: 300px;'>", "").replace("</body></html>", "")
        val processDesc = if (cleanDesc.isNotEmpty()) "<i>$cleanDesc</i><br><hr>" else ""
        
        val errorLink = if (level == "E" || message.contains("Exception", ignoreCase = true)) {
            "<br><br><a href='show_stacktrace'>[ОПИСАНИЕ ОШИБКИ]</a>"
        } else ""

        val actionDesc = when (tag) {
            "PackageManager" -> when {
                message.contains("signatures do not match") -> "<b>PackageManager:</b> ОШИБКА ПОДПИСИ! Подписи не совпадают с предыдущей версией."
                message.contains("completed") -> "<b>Менеджер пакетов:</b> Завершена установка или удаление приложения."
                message.contains("scan") -> "<b>Менеджер пакетов:</b> Завершено сканирование директорий."
                message.contains("verify") -> "<b>Менеджер пакетов:</b> Завершена проверка целостности пакета."
                message.contains("Removing permission") -> "<b>PackageManager:</b> Удаление разрешения при удалении приложения."
                else -> "<b>PackageManager:</b> Управление установкой и удалением приложений."
            }
            "ActivityManager", "ActivityTaskManager" -> when {
                message.contains("Force stopping") -> "<b>ActivityManager:</b> Принудительная остановка приложения $pkgName."
                message.contains("Start proc") -> "<b>ActivityManager:</b> Запуск процесса. PID: ${Regex("(\\d+)").find(message)?.value ?: "?"}"
                message.contains("Displayed") -> "<b>ActivityManager:</b> Приложение загружено. Время запуска: ${Regex("\\+(\\d+)ms").find(message)?.groupValues?.get(1) ?: "?"}мс"
                message.contains("START") -> "<b>ActivityTaskManager:</b> ЗапускMainActivity."
                message.contains("stop") -> "<b>Менеджер активностей:</b> Приложение остановлено штатно."
                message.contains("destroy") -> "<b>Менеджер активностей:</b> Активность уничтожена."
                else -> "<b>ActivityManager:</b> Управление жизненным циклом приложений."
            }
            "WindowManager" -> when {
                message.contains("add") -> "<b>Менеджер окон:</b> Окно добавлено на экран."
                message.contains("remove") -> "<b>Менеджер окон:</b> Окно удалено с экрана."
                message.contains("focus") || message.contains("Changing focus") -> "<b>Менеджер окон:</b> Фокус ввода передан другому окну."
                message.contains("Relayout") -> "<b>WindowManager:</b> Изменение размера и позиции окна."
                else -> "<b>WindowManager:</b> Управление окнами на экране."
            }
            "PowerManagerService", "PowerHalWrapper", "libPowerHal" -> when {
                message.contains("sleep") -> "<b>Питание:</b> Устройство перешло в спящий режим."
                message.contains("wake") -> "<b>Питание:</b> Устройство пробудилось."
                message.contains("brightness") -> "<b>Питание:</b> Изменена яркость экрана."
                message.contains("state:5") -> "<b>PowerHal:</b> Приложение активно. Частоты повышены."
                message.contains("state:0") -> "<b>PowerHal:</b> Приложение в фоне. Частоты снижены."
                else -> "<b>PowerHal:</b> Оптимизация производительности и питания."
            }
            "ConnectivityService" -> if (message.contains("connected")) "<b>Сеть:</b> Подключение установлено." else "<b>Сеть:</b> Подключение разорвано."
            "WifiService", "WifiConfigManager" -> "<b>Wi-Fi:</b> Управление беспроводным модулем."
            "BluetoothAdapter", "BluetoothDevice" -> "<b>Bluetooth:</b> Управление адаптером и сопряжением."
            "CameraService" -> "<b>Камера:</b> Операция службы камеры (открытие, съемка)."
            "AudioService" -> "<b>Аудио:</b> Управление звуковой подсистемой."
            "MediaPlayer", "MediaRecorder" -> "<b>Медиа:</b> Воспроизведение или запись контента."
            "SensorManager" -> "<b>Датчики:</b> Работа с сенсорами устройства."
            "LocationManager" -> "<b>Геолокация:</b> Запрос местоположения."
            "NotificationManager", "NotificationService", "NotificationListener" -> "<b>Уведомления:</b> Управление системными оповещениями."
            "InputMethodManager" -> "<b>Клавиатура:</b> Управление вводом данных."
            "AlarmManager" -> "<b>Будильник:</b> Установка таймеров."
            "JobScheduler" -> "<b>Планировщик:</b> Работа с фоновыми задачами."
            "DownloadManager" -> "<b>Загрузки:</b> Скачивание файлов."
            "BackupManager", "BackupManagerService" -> "<b>Бэкап:</b> Резервное копирование."
            "SyncManager" -> "<b>Синхронизация:</b> Данные синхронизированы с сервером."
            "AccountManager" -> "<b>Аккаунты:</b> Управление учетными записями."
            "UsbDeviceManager" -> "<b>USB:</b> Подключение внешнего устройства."
            "BatteryService" -> "<b>Батарея:</b> Изменение состояния заряда."
            "ThermalService" -> "<b>Температура:</b> Контроль нагрева устройства."
            "StorageManager", "StorageManagerService" -> "<b>Хранилище:</b> Управление дисками и разделами."
            "NfcService" -> "<b>NFC:</b> Бесконтактная оплата или чтение меток."
            "BufferQueueProducer", "BufferQueueDebug", "BufferQueueConsumer" -> {
                if (message.contains("queueBuffer")) "<b>Графика:</b> Кадр отправлен. FPS: ${parseFps(message)}"
                else "<b>BufferQueue:</b> Управление графическим буфером."
            }
            "SurfaceFlinger" -> "<b>SurfaceFlinger:</b> Композитор графических слоев."
            "OpenGLRenderer" -> "<b>OpenGL:</b> Отрисовка интерфейса через GPU."
            "BLASTBufferQueue" -> "<b>BLAST:</b> Новая графическая очередь Android."
            "studio.deploy" -> "<b>Android Studio Deploy:</b> Процесс развертывания и отладки."
            "nativeloader" -> "<b>Native Loader:</b> Загрузка системных библиотек."
            "ProfileInstaller" -> "<b>ProfileInstaller:</b> Оптимизация производительности."
            "Kolun.KolunLoader" -> "<b>Kolun Preload:</b> Предварительная загрузка приложений (Transsion)."
            "Memfusion/Utils" -> "<b>Memfusion:</b> Управление памятью и задачами (Transsion)."
            "UxUtility" -> "<b>UxUtility:</b> Мониторинг фокуса приложений."
            else -> when {
                message.contains("skip frames", ignoreCase = true) -> "<font color='orange'><b>Пропуск кадров:</b> Интерфейс работает с задержками.</font>"
                message.contains("ANR", ignoreCase = true) -> "<font color='red'><b>Зависание (ANR):</b> Приложение не отвечает.</font>"
                message.contains("avc: denied") -> "<b>SELinux:</b> Запрещен доступ к системному ресурсу."
                tag.startsWith("Griffin") -> "<b>Griffin:</b> Служба оптимизации (Transsion)."
                tag.startsWith("SmartPanel") -> "<b>SmartPanel:</b> Служба умной панели."
                tag.startsWith("Binder:") -> "<b>Binder:</b> Служебный поток межпроцессного взаимодействия."
                else -> "<b>Событие системы:</b> Сообщение от компонента '$tag'."
            }
        }

        return "<html><body style='width: 350px;'>$processInfo$processDesc$actionDesc$errorLink</body></html>"
    }
}

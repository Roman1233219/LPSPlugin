# LPS

[English](#english) | [Русский](#russian)

---

<a name="english"></a>
# 📑 Complete User Guide for LPS

**LPS** is a powerful Android log analysis environment that replaces standard text output with a structured system featuring deep code tracing and intelligent hints.

## 💻 System Requirements
- **IDE**: Android Studio or IntelliJ IDEA **2023.2+** (Build 232 or newer).
- **Java**: JDK **17**.
- **Android Gradle Plugin (AGP)**: **8.0+** recommended for ASM instrumentation features.

---

## 🌳 1. Process Tree (Left Panel)
The tool automatically scans the device and groups hundreds of processes into 11 semantic categories for quick navigation:
- **⭐ MY PROJECT**: Contains the application you launched from the current Android Studio window.
  - *Important*: If "application not found" is displayed, the project is not currently running on the device.
- **🔍 GOOGLE APPS / ☁️ GOOGLE SERVICES**: Separates visible apps (Chrome, YouTube) from background services (GMS).
- **🖼️ INTERFACE & GRAPHICS**: Visual shell processes (systemui, surfaceflinger, launcher).
- **📡 NETWORK / 🔊 MEDIA / 🛠️ HARDWARE / ⚙️ SYSTEM**: Low-level Android modules and drivers.
- **👤 USER APPLICATIONS**: Third-party software (WhatsApp, Telegram, etc.).
- **🧱 LOW-LEVEL**: Kernel processes (init, zygote, adbd, magisk).

**How to use**: Click on a package name (e.g., `com.my.app`) to see aggregated logs from all its instances. Select a specific PID under the package to isolate one particular session.

---

## 🛠️ 2. Control Panel (Tools)

### Left Group: Configuration & Synchronization
- **📝 Dictionary (EditSource)**: Opens `lps_dictionary.txt`. Allows you to assign "human-readable" names to packages.
- **ℹ️ Knowledge Base (Help)**: Opens `lps_descriptions.txt`. You can add your own descriptions for tags or errors here.
- **🔄 Reset (Rollback)**: Deletes dictionary and description files in the `.idea` folder, restoring the plugin to default settings.
- **📡 Synchronization (Refresh)**: **Deep Sync.** Runs a script on the device to fetch real icons and names for all applications.
- **▶️ Enable Tracing (Execute)**: Writes instrumentation into `build.gradle`. Unlocks the central tracing panel.
- **⏹️ Disable Tracing (Suspend)**: Removes instrumentation from `build.gradle`.

### Central Group: Tracing (ASM)
- **Trace Level**: Depth of log injection into your code (Minimal, Basic, Standard, Extended, Full, Selective).
- **Class Selector**: Appears in **Full** or **Selective** modes. Allows you to select one specific class for tracing (speeds up build).
- **📑 All Trace Logs (ListFiles)**: Filter only `LPS_TRACE` logs.
- **🎯 App Trace (Nodes.Class)**: Shows only your code's tracing, hiding calls from libraries.

### Right Group: Search & Log Filters
- **🔍 Search Field**: Filter messages by text or tag.
- **Filter All Logs (Filter)**: Resets all active filters.
- **E, W, I, S buttons**: Quick filtering by level (Error, Warning, Info, System).
- **⬇️ Autoscroll**: Keeps the table pinned to the bottom.
- **🗑️ Clear (GC)**: Deletes all accumulated rows.
- **💾 Save**: Exports logs to a `.txt` file (UTF-8).

---

## 🎨 3. Log Color Map

| Background | Text | Meaning |
| :--- | :--- | :--- |
| **🔵 Bright Blue** | Black | **APP Trace**: Entry into your method (your code only). |
| **🟪 Light Purple** | Black | **LIB Trace**: Entry into a third-party library method. |
| **🔴 Dark Red** | White | **Error (E)**: Critical error or crash. |
| **🟡 Orange-Yellow** | Black | **Warning (W)**: System or application warning. |
| **⬛ Dark Blue** | Cyan | **System**: Android system component logs. |
| **⬜ Dark Gray** | Bright Green | **User**: Regular application logs (Log.d/i). |

---

## 🖱️ 4. Interactive Features
- **Double-click (left)**: Jump to source code instantly (works for trace and logs with file:line format).
- **Right-click -> What is this?**: Intelligent assistant that explains tags and errors in detail.
- **Auto-clear**: Logs are automatically cleared only on a new app start (Run).

---

<a name="russian"></a>
# 📑 Полное руководство пользователя LPS

**LPS** — это мощная среда анализа Android-логов, которая заменяет стандартный текстовый вывод на структурированную систему с глубокой трассировкой кода и интеллектуальными подсказками.

## 💻 Технические требования
- **IDE**: Android Studio or IntelliJ IDEA **2023.2+** (Build 232 или новее).
- **Java**: JDK **17**.
- **Android Gradle Plugin (AGP)**: **8.0+** рекомендуется для функций ASM-инструментации.

---

## 🌳 1. Дерево процессов (Левая панель)
Инструмент автоматически сканирует устройство и группирует сотни процессов по 11 смысловым категориям:
- **⭐ МОЙ ПРОЕКТ**: Приложение, запущенное из текущего окна Android Studio.
  - *Важно*: Если отображается **"приложение не найдено"**, значит проект не запущен на устройстве.
- **🔍 ПРИЛОЖЕНИЯ ГУГЛ / ☁️ СЛУЖБЫ ГУГЛ**: Отделение видимых приложений от фоновых сервисов.
- **🖼️ ИНТЕРФЕЙС И ГРАФИКА**: Процессы оболочки (`systemui`, `surfaceflinger`, `launcher`).
- **📡 СЕТЬ / 🔊 МЕДИА / 🛠️ ЖЕЛЕЗО / ⚙️ СИСТЕМНЫЕ**: Низкоуровневые модули и драйверы Android.
- **👤 ПОЛЬЗОВАТЕЛЬСКИЕ ПРИЛОЖЕНИЯ**: Стороннее ПО (WhatsApp, Telegram и др.).
- **🧱 НИЗКОУРОВНЕВЫЕ**: Процессы ядра (`init`, `zygote`, `adbd`, `magisk`).

**Как пользоваться:** Нажмите на имя пакета, чтобы видеть сводные логи всех его запусков. Выберите конкретный **PID**, чтобы изолировать одну сессию.

---

## 🛠️ 2. Панель управления (Инструменты)

### Левая группа: Настройка и Синхронизация
- **📝 Словарь (EditSource):** Открывает файл `lps_dictionary.txt`. Позволяет задать «человеческие» имена для пакетов.
- **ℹ️ База знаний (Help):** Открывает `lps_descriptions.txt`. Здесь хранятся описания для тегов или ошибок.
- **🔄 Сброс (Rollback):** Возвращает плагин к стандартным настройкам (удаляет словари в `.idea`).
- **📡 Синхронизация (Refresh):** **Deep Sync.** Получение реальных иконок и имен всех приложений с устройства.
- **▶️ Вкл. Трассировку (Execute):** Прописывает инструментацию в `build.gradle`. Разблокирует панель трассировки.
- **⏹️ Выкл. Трассировку (Suspend):** Удаляет инструментацию из `build.gradle`.

### Центральная группа: Трассировка (ASM)
- **Trace Level (Уровни):** Глубина внедрения логов (Minimal, Basic, Standard, Extended, Full, Selective).
- **Class Selector:** Выбор **одного конкретного класса** для трассировки (ускоряет сборку).
- **📑 Все Trace-логи (ListFiles):** Фильтр «Путь кода». Оставляет только логи `LPS_TRACE`.
- **🎯 App Trace (Nodes.Class):** Показывает только трассировку **вашего кода**.

### Правая группа: Поиск и Фильтры логов
- **🔍 Поле поиска:** Фильтрация сообщений по тексту или тегу.
- **Filter Все логи (Воронка):** Сбрасывает все активные фильтры.
- **Квадраты E, W, I, S:** Быстрая фильтрация по уровню (Error, Warning, Info, System).
- **⬇️ Автопрокрутка:** Таблица всегда показывает последние логи.
- **🗑️ Очистить (GC):** Мгновенное удаление всех накопленных строк.
- **💾 Сохранить:** Экспорт логов в файл `.txt` (UTF-8).

---

## 🎨 3. Цветовая карта логов

| Цвет фона | Цвет текста | Значение |
| :--- | :--- | :--- |
| **🟦 Ярко-голубой** | Черный | **APP Trace**: Вход в ваш метод (только ваш код). |
| **🟪 Светло-фиолетовый** | Черный | **LIB Trace**: Вход в метод библиотеки. |
| **🟥 Темно-красный** | Белый | **Error (E)**: Критическая ошибка или крэш. |
| **🟨 Оранжево-желтый** | Черный | **Warning (W)**: Предупреждение. |
| **⬛ Темно-синий** | Голубой | **System**: Логи системных компонентов Android. |
| **⬜ Темно-серый** | Светло-зеленый | **User**: Обычные логи приложения (`Log.d/i`). |

---

## 🖱️ 4. Интерактивные возможности
- **Двойной клик:** Мгновенный переход к исходному коду (для трассировки и логов с FileName:Line).
- **Правая кнопка -> Что это?:** Интеллектуальный помощник. Объяснит роль системной службы или причину ошибки.
- **Авто-очистка:** Таблица чистится автоматически при каждом новом нажатии **Run** в IDE.

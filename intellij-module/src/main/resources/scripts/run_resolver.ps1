# Universal Script to compile and run LabelResolver
${'$'}ErrorActionPreference = "Stop"

${'$'}SCRIPT_DIR = Split-Path -Parent ${'$'}MyInvocation.MyCommand.Definition
${'$'}SRC_DIR = ${'$'}SCRIPT_DIR
${'$'}ICONS_LOCAL_DIR = Join-Path ${'$'}SRC_DIR "icons"

${'$'}STUDIO_ROOT = "C:\Program Files\Android\Android Studio"
${'$'}SDK_PATH = "${'$'}env:LOCALAPPDATA\Android\Sdk"

if (-not (Test-Path ${'$'}ICONS_LOCAL_DIR)) { New-Item -ItemType Directory -Path ${'$'}ICONS_LOCAL_DIR }

if (-not (Test-Path "${'$'}SDK_PATH\build-tools")) { throw "Android SDK not found at ${'$'}SDK_PATH." }
${'$'}BUILD_TOOLS_DIR = Get-ChildItem -Path "${'$'}SDK_PATH\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
${'$'}D8_PATH = Join-Path ${'$'}BUILD_TOOLS_DIR.FullName "d8.bat"

${'$'}env:JAVA_HOME = "${'$'}STUDIO_ROOT\jbr"
${'$'}JAVAC_PATH = "${'$'}env:JAVA_HOME\bin\javac.exe"
${'$'}ADB_PATH = "${'$'}SDK_PATH\platform-tools\adb.exe"
${'$'}ANDROID_JAR = (Get-ChildItem -Path "${'$'}SDK_PATH\platforms" -Filter "android-*" | Sort-Object Name -Descending | Select-Object -First 1).FullName + "\android.jar"

Write-Host "1. Compiling..." -ForegroundColor Cyan
& ${'$'}JAVAC_PATH -source 1.8 -target 1.8 -bootclasspath ${'$'}ANDROID_JAR "${'$'}SRC_DIR\LabelResolver.java"

Write-Host "2. Creating DEX..." -ForegroundColor Cyan
& ${'$'}D8_PATH --output "${'$'}SRC_DIR\label_resolver.jar" "${'$'}SRC_DIR\LabelResolver.class"

Write-Host "3. Pushing..." -ForegroundColor Cyan
& ${'$'}ADB_PATH push "${'$'}SRC_DIR\label_resolver.jar" /data/local/tmp/

Write-Host "4. Running..." -ForegroundColor Yellow
& ${'$'}ADB_PATH shell "export CLASSPATH=/data/local/tmp/label_resolver.jar; app_process /data/local/tmp LabelResolver"

Write-Host "5. Pulling icons..." -ForegroundColor Cyan
& ${'$'}ADB_PATH pull "/data/local/tmp/icons/." "${'$'}ICONS_LOCAL_DIR"

Write-Host "Done!" -ForegroundColor Green

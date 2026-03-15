# Universal Script to compile and run LabelResolver
$ErrorActionPreference = "Stop"

# --- DYNAMIC PATHS ---
# Get the directory where THIS script is located
$SCRIPT_DIR = Split-Path -Parent $MyInvocation.MyCommand.Definition
$SRC_DIR = $SCRIPT_DIR
$ICONS_LOCAL_DIR = Join-Path $SRC_DIR "icons"

# Standard Android Paths (common for all Windows users)
$STUDIO_ROOT = "C:\Program Files\Android\Android Studio"
$SDK_PATH = "$env:LOCALAPPDATA\Android\Sdk"

# Create local icons directory
if (-not (Test-Path $ICONS_LOCAL_DIR)) { New-Item -ItemType Directory -Path $ICONS_LOCAL_DIR }

# Find build-tools path
if (-not (Test-Path "$SDK_PATH\build-tools")) { throw "Android SDK not found at $SDK_PATH. Please check your SDK location." }
$BUILD_TOOLS_DIR = Get-ChildItem -Path "$SDK_PATH\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
$D8_PATH = Join-Path $BUILD_TOOLS_DIR.FullName "d8.bat"

# Find Java inside Studio
$env:JAVA_HOME = "$STUDIO_ROOT\jbr"
$JAVAC_PATH = "$env:JAVA_HOME\bin\javac.exe"
$ADB_PATH = "$SDK_PATH\platform-tools\adb.exe"
$ANDROID_JAR = (Get-ChildItem -Path "$SDK_PATH\platforms" -Filter "android-*" | Sort-Object Name -Descending | Select-Object -First 1).FullName + "\android.jar"

# --- PROCESS ---
Write-Host "1. Compiling Java in $SRC_DIR..." -ForegroundColor Cyan
& $JAVAC_PATH -source 1.8 -target 1.8 -bootclasspath $ANDROID_JAR "$SRC_DIR\LabelResolver.java"

Write-Host "2. Creating DEX (JAR)..." -ForegroundColor Cyan
& $D8_PATH --output "$SRC_DIR\label_resolver.jar" "$SRC_DIR\LabelResolver.class"

Write-Host "3. Pushing to device..." -ForegroundColor Cyan
& $ADB_PATH shell "mkdir -p /data/local/tmp/icons"
& $ADB_PATH push "$SRC_DIR\label_resolver.jar" /data/local/tmp/

Write-Host "4. Running on device..." -ForegroundColor Yellow
Write-Host "------------------------------------------------"
& $ADB_PATH shell "export CLASSPATH=/data/local/tmp/label_resolver.jar; app_process /data/local/tmp LabelResolver"
Write-Host "------------------------------------------------"

Write-Host "5. Pulling icons to PC..." -ForegroundColor Cyan
& $ADB_PATH pull "/data/local/tmp/icons/." "$ICONS_LOCAL_DIR"

Write-Host "6. Cleaning up device..." -ForegroundColor Gray
& $ADB_PATH shell "rm -rf /data/local/tmp/icons"
& $ADB_PATH shell "rm /data/local/tmp/label_resolver.jar"

Write-Host "Done!" -ForegroundColor Green

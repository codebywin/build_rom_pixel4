@echo off
setlocal enabledelayedexpansion

echo ================================================================
echo          CONG CU BIEN DICH APK PAYOUT MANAGER (com.payout.app)
echo ================================================================
echo.

cd /d "%~dp0"

REM Thiet lap JDK tu Android Studio
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"
)

echo [*] Dang bien dich APK bang Gradle...
call gradlew.bat assembleDebug

if %ERRORLEVEL% neq 0 (
    echo.
    echo [LOI] Bien dich Gradle that bai!
    pause
    exit /b 1
)

echo.
echo ================================================================
echo   THANH CONG! File APK: app\build\outputs\apk\debug\app-debug.apk
echo ================================================================
echo.

REM Kiem tra thiet bi ADB va cai dat tu dong
where adb >nul 2>&1
if %ERRORLEVEL% equ 0 (
    adb devices | findstr "device$" >nul
    if not errorlevel 1 (
        echo [ADB] Phat hien dien thoai Android dang ket noi USB Debugging.
        echo [*] Dang chuyen tiep cong 3000 (adb reverse tcp:3000 tcp:3000)...
        adb reverse tcp:3000 tcp:3000
        echo [*] Dang cai dat APK len thiet bi...
        adb install -r -d "app\build\outputs\apk\debug\app-debug.apk"
        echo [*] Dang mo ung dung...
        adb shell am start -n com.payout.app/.MainActivity
        echo [V] Da cai dat va mo ung dung thanh cong!
    )
)

echo.
pause

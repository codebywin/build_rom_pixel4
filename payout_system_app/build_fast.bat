@echo off
setlocal enabledelayedexpansion

echo ================================================================
echo   BUILD NHANH APK ARM64 PAYOUT MANAGER (AUTO INSTALL VA RUN)
echo ================================================================
echo.

cd /d "%~dp0"

REM Thiet lap JDK tu Android Studio neu co
if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    set "PATH=C:\Program Files\Android\Android Studio\jbr\bin;%PATH%"
)

echo [*] [1/3] Dang bien dich APK bang Gradle...
call gradlew.bat assembleDebug

if %ERRORLEVEL% neq 0 (
    echo.
    echo [LOI] Bien dich APK that bai!
    pause
    exit /b 1
)

echo.
echo [V] [2/3] Build thanh cong! File: app\build\outputs\apk\debug\app-debug.apk
echo.

REM Kiem tra thiet bi ADB
where adb >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [*] [3/3] Dang kiem tra thiet bi qua ADB...
    adb devices | findstr "device$" >nul
    if not errorlevel 1 (
        echo [V] Phat hien dien thoai Android cam USB!
        echo [*] Dang chuyen tiep cong Server 3000 qua USB (adb reverse tcp:3000 tcp:3000)...
        adb reverse tcp:3000 tcp:3000
        
        echo [*] Dang cai dat APK len dien thoai...
        adb install -r -d "app\build\outputs\apk\debug\app-debug.apk"
        
        echo [*] Dang khoi chay ung dung Payout Manager...
        adb shell am start -n com.payout.app/.MainActivity
        
        echo.
        echo ================================================================
        echo   HOAN TAT! Ung dung da duoc cai dat va mo tren dien thoai!
        echo ================================================================
    ) else (
        echo [!] Khong tim thay thiet bi ADB cam USB. Ban co the copy APK thu cong.
    )
)

echo.
pause


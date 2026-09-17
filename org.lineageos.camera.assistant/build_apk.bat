@echo off
chcp 65001 >nul
title Build Camera Assistant APK - Pixel 4

cd /d "%~dp0"

where python >nul 2>nul
if %errorlevel% neq 0 (
    echo [LOI] Khong tim thay Python trong he thong!
    echo Vui long cai dat Python hoac them Python vao bien moi truong PATH.
    echo.
    pause
    exit /b 1
)

set BUILD_MODE=%1
if not "%BUILD_MODE%"=="" goto DO_BUILD

:MENU
echo ======================================================
echo    CHON CHE DO BIEN DICH CAMERA ASSISTANT
echo ======================================================
echo   1. Ban DEBUG   - Nhanh, giu debug symbols (Mac dinh)
echo   2. Ban RELEASE - Toi uu d8, ZipAlign, ky Release Key
echo ======================================================
set /p MODE_CHOICE="Chon che do (1 hoac 2, mac dinh 1): "
if "%MODE_CHOICE%"=="2" (
    set BUILD_MODE=release
) else (
    set BUILD_MODE=debug
)

:DO_BUILD
echo.
echo ======================================================
echo    BAT DAU BIEN DICH BAN: %BUILD_MODE%
echo ======================================================
echo.

python build_app.py %BUILD_MODE%
if %errorlevel% neq 0 (
    echo.
    echo ======================================================
    echo [THAT BAI] Qua trinh bien dich APK gap loi!
    echo ======================================================
    echo.
    pause
    exit /b %errorlevel%
)

echo.
echo ======================================================
echo [THANH CONG] Da tao CameraAssistant-%BUILD_MODE%.apk!
echo ======================================================
echo.

where adb >nul 2>nul
if %errorlevel% equ 0 (
    adb get-state >nul 2>nul
    if %errorlevel% equ 0 (
        echo Phat hien thiet bi Pixel ket noi qua ADB.
        set /p INSTALL_CHOICE="Ban co muon cai dat APK len dien thoai ngay khong? (Y/N, mac dinh Y): "
        if /i "%INSTALL_CHOICE%"=="" set INSTALL_CHOICE=Y
        if /i "%INSTALL_CHOICE%"=="Y" (
            echo.
            echo Dang cai dat CameraAssistant-%BUILD_MODE%.apk len thiet bi...
            adb install -r "CameraAssistant-%BUILD_MODE%.apk"
            if %errorlevel% equ 0 (
                echo.
                echo [OK] Cai dat ung dung thanh cong!
                echo.
                set /p LAUNCH_CHOICE="Ban co muon mo ung dung tren dien thoai luon khong? (Y/N, mac dinh Y): "
                if /i "%LAUNCH_CHOICE%"=="" set LAUNCH_CHOICE=Y
                if /i "%LAUNCH_CHOICE%"=="Y" (
                    adb shell am start -n org.lineageos.camera.assistant/.MainActivity
                    echo [OK] Da mo Camera Assistant tren dien thoai!
                )
            ) else (
                echo [LOI] Khong the cai dat APK len thiet bi!
            )
        )
    ) else (
        echo [THONG BAO] Khong co thiet bi nao ket noi qua ADB.
    )
)

echo.
echo Hoan tat!
pause
@echo off
chcp 65001 >nul
title VCam A16 Auto Patcher - Pixel 4 (flame)
color 0B

echo ================================================================
echo       CÔNG CỤ TỰ ĐỘNG NẠP VCAM ANDROID 16 - PIXEL 4 (FLAME)
echo ================================================================
echo.

set "SCRIPT_DIR=%~dp0"
set "JAR_FILE=%SCRIPT_DIR%dump_device\framework_vcam.jar"
set "APK_FILE=%SCRIPT_DIR%org.lineageos.camera.assistant\CameraAssistant-release.apk"
set "LIC_FILE=%SCRIPT_DIR%dump_device\vcam_test.lic"

:: 1. Kiem tra file nguon
echo [*] Kiem tra file can thiet...
if not exist "%JAR_FILE%" (
    color 0C
    echo [!] LOI: Khong tim thay file %JAR_FILE%
    echo Vui long kiem tra lai thu muc dump_device!
    pause
    exit /b 1
)

if not exist "%APK_FILE%" (
    color 0C
    echo [!] LOI: Khong tim thay file %APK_FILE%
    echo Vui long bien dich app CameraAssistant truoc!
    pause
    exit /b 1
)

echo [OK] File framework_vcam.jar: SAN SANG
echo [OK] File CameraAssistant.apk: SAN SANG
echo.

:: 2. Kiem tra ket noi ADB
echo [*] Dang tim thiet bi Pixel 4 ket noi qua ADB...
adb wait-for-device
for /f "tokens=1" %%d in ('adb get-serialno') do set DEVICE_SERIAL=%%d
echo [OK] Da ket noi thiet bi: %DEVICE_SERIAL%
echo.

:: 3. Lay quyen Root qua ADB
echo [*] 1. Yeu cau quyen ADB Root...
adb root >nul 2>&1
timeout /t 2 >nul
adb wait-for-device

:: 4. Remount phan vung he thong RW
echo [*] 2. Remount phan vung /system sang Read-Write (RW)...
adb remount
if %ERRORLEVEL% NEQ 0 (
    echo [!] Remount chua thanh cong, dang thu disable-verity...
    adb disable-verity
    echo [!] Can khoi dong lai thiet bi sau khi disable-verity.
    echo Dang khoi dong lai...
    adb reboot
    echo Vui long cho may khoi dong xong roi chay lai tool nay!
    pause
    exit /b 1
)
echo [OK] Remount he thong thanh cong!
echo.

:: 5. Nap framework_vcam.jar
echo [*] 3. Dang nap file framework_vcam.jar vao /system/framework/framework.jar...
adb push "%JAR_FILE%" /system/framework/framework.jar
adb shell chmod 644 /system/framework/framework.jar
adb shell chown root:root /system/framework/framework.jar
echo [OK] Da nap xong framework.jar!
echo.

:: 6. Nap App quan ly vao System App
echo [*] 4. Dang nap CameraAssistant vao /system/app/CameraAssistant/...
adb shell mkdir -p /system/app/CameraAssistant
adb push "%APK_FILE%" /system/app/CameraAssistant/CameraAssistant.apk
adb shell chmod 755 /system/app/CameraAssistant
adb shell chmod 644 /system/app/CameraAssistant/CameraAssistant.apk
adb shell chown -R root:root /system/app/CameraAssistant
echo [OK] Da cai dat App He Thong thanh cong!
echo.

:: 7. Thiet lap thu muc /data/local/tmp va file dieu khien
echo [*] 5. Thiet lap quyen va thu muc dieu khien VCam (/data/local/tmp)...
adb shell chmod 0777 /data/local/tmp
adb shell "mkdir -p /data/local/tmp/vcam 2>/dev/null; chmod 0777 /data/local/tmp/vcam 2>/dev/null"
adb shell "echo 0 > /data/local/tmp/vcam_disable && chmod 0666 /data/local/tmp/vcam_disable"
adb shell "echo 0 > /data/local/tmp/vcam_pause && chmod 0666 /data/local/tmp/vcam_pause"
adb shell "echo 0 > /data/local/tmp/vcam_reset && chmod 0666 /data/local/tmp/vcam_reset"

:: Nap key ban quyen neu co san
if exist "%LIC_FILE%" (
    echo [*] Tim thay key ban quyen: Dang nap vcam.lic vao may...
    adb push "%LIC_FILE%" /data/local/tmp/vcam.lic
    adb shell chmod 0666 /data/local/tmp/vcam.lic
    adb push "%LIC_FILE%" /sdcard/vcam.lic >nul 2>&1
)
echo [OK] Thiet lap node VCam hoan tat!
echo.

:: 8. Xoa cache dalvik de load dex moi
echo [*] 6. Don dep bo nho cache dex cu...
adb shell "rm -rf /data/dalvik-cache/*framework* 2>/dev/null"
echo [OK] Da don cache he thong!
echo.

echo ================================================================
echo                  🎉 NẠP VCAM A16 THÀNH CÔNG! 🎉
echo ================================================================
echo.
echo Thiet bi can duoc KHOI DONG LAI de he thong nap framework va app moi.
echo.
set /p REBOOT_CHOICE="Ban co muon khoi dong lai may ngay bay gio? (Y/N, mac dinh: Y): "
if /i "%REBOOT_CHOICE%"=="N" (
    echo.
    echo Ban da chon khoi dong lai sau. Hay khoi dong lai dien thoai truoc khi dung!
) else (
    echo.
    echo [*] Dang khoi dong lai dien thoai...
    adb reboot
    echo [OK] May dang khoi dong lai. Chuc ban trai nghiem vui ve!
)

echo.
pause


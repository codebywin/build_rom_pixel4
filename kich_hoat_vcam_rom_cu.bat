@echo off
title Kich hoat va Test VCAM Pixel 4

echo ========================================================
echo   KICH HOAT VA TEST VCAM PIXEL 4
echo ========================================================
echo.

echo [1/5] Kiem tra ket noi ADB...
adb devices
adb wait-for-device

echo.
echo [2/5] Cai dat Camera Assistant phien ban moi nhat...
if exist "org.lineageos.camera.assistant\CameraAssistant.apk" (
    adb install -r "org.lineageos.camera.assistant\CameraAssistant.apk"
) else (
    echo [CANH BAO] Khong tim thay CameraAssistant.apk, bo qua buoc nay.
)

echo.
echo [3/5] Nap ban quyen VCAM (vcam.lic)...
if exist "vcam.lic" (
    adb push vcam.lic /sdcard/vcam.lic
    adb push vcam.lic /data/local/tmp/vcam.lic
    adb shell "chmod 0666 /sdcard/vcam.lic /data/local/tmp/vcam.lic"
    echo [OK] Da nap ban quyen vcam.lic!
)

echo.
echo [4/5] Cap quyen va tao tat ca cac node dieu khien VCAM...
adb shell "mkdir -p /data/local/tmp && chmod 0777 /data/local/tmp"
adb shell "touch /data/local/tmp/vcam_pause /data/local/tmp/vcam_disable /data/local/tmp/vcam_mic_disable /data/local/tmp/vcam_mic_mix /data/local/tmp/vcam_zoom /data/local/tmp/vcam_zoom.cfg /data/local/tmp/vcam_pan.cfg /data/local/tmp/vcam_pan_x /data/local/tmp/vcam_pan_y /data/local/tmp/vcam_rotation /data/local/tmp/vcam_reset /data/local/tmp/vcam_kyc_flash /data/local/tmp/vcam_color_sync /data/local/tmp/vcam_color_val /data/local/tmp/vcam_mic_boost"
adb shell "chmod 0666 /data/local/tmp/vcam*"
adb shell "echo 0 > /data/local/tmp/vcam_disable"

echo.
echo [5/5] Nap video mau vao VCAM...
if exist "vcam.mp4" (
    echo Dang day file vcam.mp4 vao dien thoai...
    adb push vcam.mp4 /data/local/tmp/vcam.mp4
    adb shell "chmod 0666 /data/local/tmp/vcam.mp4"
    echo [OK] Da nap video vcam.mp4 thanh cong!
) else (
    echo [NOTE] Khong thay vcam.mp4 trong thu muc, ban hay mo app Camera Assistant de chon video.
)

echo.
echo ========================================================
echo   DA KICH HOAT THANH CONG!
echo   Mo ung dung Camera (Aperture) de xem video ao.
echo ========================================================
echo.
pause
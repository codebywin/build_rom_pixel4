# Tool nạp VCam Android 16 - Pixel 4 (flame)
$ErrorActionPreference = "Stop"

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "       CÔNG CỤ TỰ ĐỘNG NẠP VCAM ANDROID 16 - PIXEL 4 (FLAME)" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$SCRIPT_DIR = $PSScriptRoot
$JAR_FILE = Join-Path $SCRIPT_DIR "dump_device\framework_vcam.jar"
$APK_FILE = Join-Path $SCRIPT_DIR "org.lineageos.camera.assistant\CameraAssistant-release.apk"
$LIC_FILE = Join-Path $SCRIPT_DIR "dump_device\vcam_test.lic"

# 1. Kiểm tra file
if (-not (Test-Path $JAR_FILE)) {
    Write-Host "[!] LỖI: Không tìm thấy $JAR_FILE" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $APK_FILE)) {
    Write-Host "[!] LỖI: Không tìm thấy $APK_FILE" -ForegroundColor Red
    exit 1
}

Write-Host "[OK] File framework_vcam.jar: SẴN SÀNG" -ForegroundColor Green
Write-Host "[OK] File CameraAssistant.apk: SẴN SÀNG" -ForegroundColor Green
Write-Host ""

# 2. Kiểm tra ADB
Write-Host "[*] Đang chờ kết nối Pixel 4 qua ADB..." -ForegroundColor Yellow
& adb wait-for-device
$serial = (& adb get-serialno).Trim()
Write-Host "[OK] Đã kết nối thiết bị: $serial" -ForegroundColor Green
Write-Host ""

# 3. ADB root
Write-Host "[*] 1. Yêu cầu quyền ADB Root..." -ForegroundColor Yellow
& adb root
Start-Sleep -Seconds 2
& adb wait-for-device

# 4. Remount
Write-Host "[*] 2. Remount phân vùng /system sang RW..." -ForegroundColor Yellow
$remount = & adb remount 2>&1
Write-Host $remount
if ($LASTEXITCODE -ne 0) {
    Write-Host "[!] Đang thử disable-verity..." -ForegroundColor Yellow
    & adb disable-verity
    Write-Host "[!] Vui lòng khởi động lại thiết bị rồi chạy lại tool!" -ForegroundColor Red
    & adb reboot
    exit 1
}
Write-Host "[OK] Remount hệ thống thành công!" -ForegroundColor Green
Write-Host ""

# 5. Nạp framework.jar
Write-Host "[*] 3. Đang nạp framework_vcam.jar vào /system/framework/framework.jar..." -ForegroundColor Yellow
& adb push $JAR_FILE /system/framework/framework.jar
& adb shell chmod 644 /system/framework/framework.jar
& adb shell chown root:root /system/framework/framework.jar
Write-Host "[OK] Đã nạp xong framework.jar!" -ForegroundColor Green
Write-Host ""

# 6. Nạp CameraAssistant
Write-Host "[*] 4. Đang cài đặt CameraAssistant vào /system/app/CameraAssistant/..." -ForegroundColor Yellow
& adb shell mkdir -p /system/app/CameraAssistant
& adb push $APK_FILE /system/app/CameraAssistant/CameraAssistant.apk
& adb shell chmod 755 /system/app/CameraAssistant
& adb shell chmod 644 /system/app/CameraAssistant/CameraAssistant.apk
& adb shell chown -R root:root /system/app/CameraAssistant
Write-Host "[OK] Đã cài đặt App Hệ Thống thành công!" -ForegroundColor Green
Write-Host ""

# 7. Mở quyền /data/local/tmp
Write-Host "[*] 5. Thiết lập quyền và node VCam (/data/local/tmp)..." -ForegroundColor Yellow
& adb shell chmod 0777 /data/local/tmp
& adb shell "mkdir -p /data/local/tmp/vcam 2>/dev/null; chmod 0777 /data/local/tmp/vcam 2>/dev/null"
& adb shell "echo 0 > /data/local/tmp/vcam_disable && chmod 0666 /data/local/tmp/vcam_disable"
& adb shell "echo 0 > /data/local/tmp/vcam_pause && chmod 0666 /data/local/tmp/vcam_pause"
& adb shell "echo 0 > /data/local/tmp/vcam_reset && chmod 0666 /data/local/tmp/vcam_reset"

if (Test-Path $LIC_FILE) {
    Write-Host "[*] Đang nạp key bản quyền vào máy..." -ForegroundColor Yellow
    & adb push $LIC_FILE /data/local/tmp/vcam.lic
    & adb shell chmod 0666 /data/local/tmp/vcam.lic
    & adb push $LIC_FILE /sdcard/vcam.lic 2>$null
}
Write-Host "[OK] Thiết lập node VCam hoàn tất!" -ForegroundColor Green
Write-Host ""

# 8. Xóa cache dex cũ
Write-Host "[*] 6. Dọn dẹp cache dalvik..." -ForegroundColor Yellow
& adb shell "rm -rf /data/dalvik-cache/*framework* 2>/dev/null"
Write-Host "[OK] Đã dọn cache hệ thống!" -ForegroundColor Green
Write-Host ""

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "                  🎉 NẠP VCAM A16 THÀNH CÔNG! 🎉" -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ""

$reboot = Read-Host "Bạn có muốn khởi động lại máy ngay bây giờ? (Y/N, mặc định: Y)"
if ($reboot -eq "" -or $reboot -eq "Y" -or $reboot -eq "y") {
    Write-Host "[*] Đang khởi động lại điện thoại..." -ForegroundColor Yellow
    & adb reboot
    Write-Host "[OK] Máy đang khởi động lại. Chúc bạn trải nghiệm vui vẻ!" -ForegroundColor Green
} else {
    Write-Host "Bạn đã chọn khởi động lại sau. Hãy nhớ reboot máy trước khi dùng!" -ForegroundColor Yellow
}


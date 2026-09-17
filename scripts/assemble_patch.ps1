$sandboxDiff = git -C "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\test_patch" diff
$oldPatch = Get-Content "c:\Users\admin\Desktop\codebywin\build_rom_pixel4\patches\vcam_pixel4.patch" -Raw

# Lấy phần các file Java mới của VCam từ patch cũ (từ diff --git a/core/java/android/hardware/VcamCamera.java trở đi)
$startIndex = $oldPatch.IndexOf("diff --git a/core/java/android/hardware/VcamCamera.java")
$endIndex = $oldPatch.IndexOf("diff --git a/media/java/android/media/VcamAudioEngine.java")

if ($endIndex -lt 0) {
    $vcamNewFiles = $oldPatch.Substring($startIndex)
} else {
    $vcamNewFiles = $oldPatch.Substring($startIndex, $endIndex - $startIndex)
}

# Ghép phần diff chuẩn của Camera & CameraDeviceImpl với các file VCam Java thuần
# Lưu ý: loại bỏ BOM marker nếu có
$cleanSandboxDiff = ($sandboxDiff -join "`n").Replace("﻿/*", "/*")

$finalPatch = $cleanSandboxDiff + "`n" + $vcamNewFiles.Trim() + "`n"

$targetPatch = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\patches\vcam_pixel4_a16.patch"
[System.IO.File]::WriteAllText($targetPatch, $finalPatch, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Created final vcam_pixel4_a16.patch successfully without BOM!"


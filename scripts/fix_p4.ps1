$f = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2_v3\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $f -Raw

# Sửa p2 thành p4 (p4 mới là boolean repeating)
$content = $content.Replace(
    "invoke-static {v2, p2}, Landroid/hardware/camera2/impl/VcamEngine;->filterCaptureRequest(Landroid/hardware/camera2/CaptureRequest;Z)V",
    "invoke-static {v2, p4}, Landroid/hardware/camera2/impl/VcamEngine;->filterCaptureRequest(Landroid/hardware/camera2/CaptureRequest;Z)V"
)

[System.IO.File]::WriteAllText($f, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Fixed repeating parameter from p2 to p4!"


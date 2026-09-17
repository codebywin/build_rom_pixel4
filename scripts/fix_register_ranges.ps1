$f = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2_v3\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $f -Raw

# Sửa invoke-static thành invoke-static/range cho các lệnh dùng p0/p2 (register cao)
$content = $content.Replace(
    "invoke-static {p0, p2}, Landroid/hardware/camera2/impl/VcamEngine;->onSessionConfigured(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V",
    "move-object v0, p0`r`n    move-object v1, p2`r`n    invoke-static {v0, v1}, Landroid/hardware/camera2/impl/VcamEngine;->onSessionConfigured(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V"
)

$content = $content.Replace(
    "invoke-static {p0}, Landroid/hardware/camera2/impl/VcamEngine;->onCameraClose(Landroid/hardware/camera2/CameraDevice;)V",
    "move-object v1, p0`r`n    invoke-static {v1}, Landroid/hardware/camera2/impl/VcamEngine;->onCameraClose(Landroid/hardware/camera2/CameraDevice;)V"
)

# Ghi lại không có BOM
[System.IO.File]::WriteAllText($f, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Fixed register ranges with move-object!"


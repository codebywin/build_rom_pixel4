$f = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2_v3\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $f -Raw

# Sửa signature CameraDeviceImpl thành CameraDevice
$content = $content.Replace(
    "Landroid/hardware/camera2/impl/VcamEngine;->prepareOutputs(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V",
    "Landroid/hardware/camera2/impl/VcamEngine;->prepareOutputs(Landroid/hardware/camera2/CameraDevice;Ljava/util/List;)V"
)

$content = $content.Replace(
    "Landroid/hardware/camera2/impl/VcamEngine;->onSessionConfigured(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V",
    "Landroid/hardware/camera2/impl/VcamEngine;->onSessionConfigured(Landroid/hardware/camera2/CameraDevice;Ljava/util/List;)V"
)

[System.IO.File]::WriteAllText($f, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Fixed method signatures to CameraDevice!"


$smaliFile = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $smaliFile -Raw

# 1. Hook prepareOutputs
$target1 = "    :try_start_91
    invoke-virtual/range {v1 .. v7}, Landroid/hardware/camera2/impl/CameraDeviceImpl;->configureStreamsChecked(Landroid/hardware/camera2/params/InputConfiguration;Ljava/util/List;ILandroid/hardware/camera2/CaptureRequest;J)Z"

$replace1 = "    :try_start_91
    invoke-static {v1, v3}, Landroid/hardware/camera2/impl/VcamEngine;->prepareOutputs(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V

    invoke-virtual/range {v1 .. v7}, Landroid/hardware/camera2/impl/CameraDeviceImpl;->configureStreamsChecked(Landroid/hardware/camera2/params/InputConfiguration;Ljava/util/List;ILandroid/hardware/camera2/CaptureRequest;J)Z"

$content = $content.Replace($target1, $replace1)

# 2. Hook onSessionConfigured
$target2 = "    iput-object v0, p0, Landroid/hardware/camera2/impl/CameraDeviceImpl;->mCurrentSession:Landroid/hardware/camera2/impl/CameraCaptureSessionCore;"

$replace2 = "    iput-object v0, p0, Landroid/hardware/camera2/impl/CameraDeviceImpl;->mCurrentSession:Landroid/hardware/camera2/impl/CameraCaptureSessionCore;

    invoke-static {p0, p2}, Landroid/hardware/camera2/impl/VcamEngine;->onSessionConfigured(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V"

$content = $content.Replace($target2, $replace2)

# 3. Hook filterCaptureRequest
$target3 = "    invoke-virtual {v2, v4}, Landroid/hardware/camera2/CaptureRequest;->convertSurfaceToStreamId(Landroid/util/SparseArray;)V"

$replace3 = "    invoke-static {v2, p2}, Landroid/hardware/camera2/impl/VcamEngine;->filterCaptureRequest(Landroid/hardware/camera2/CaptureRequest;Z)V

    invoke-virtual {v2, v4}, Landroid/hardware/camera2/CaptureRequest;->convertSurfaceToStreamId(Landroid/util/SparseArray;)V"

$content = $content.Replace($target3, $replace3)

# 4. Hook onCameraClose
$target4 = ".method public whitelist test-api close()V
    .registers 6

    .line 1760
    iget-object v0, p0, Landroid/hardware/camera2/impl/CameraDeviceImpl;->mInterfaceLock:Ljava/lang/Object;

    monitor-enter v0"

$replace4 = ".method public whitelist test-api close()V
    .registers 6

    .line 1760
    iget-object v0, p0, Landroid/hardware/camera2/impl/CameraDeviceImpl;->mInterfaceLock:Ljava/lang/Object;

    monitor-enter v0

    invoke-static {p0}, Landroid/hardware/camera2/impl/VcamEngine;->onCameraClose(Landroid/hardware/camera2/CameraDevice;)V"

$content = $content.Replace($target4, $replace4)

[System.IO.File]::WriteAllText($smaliFile, $content, [System.Text.Encoding]::UTF8)

Write-Host "Successfully hooked all 4 points in CameraDeviceImpl.smali!"


$smaliFile = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2_v3\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $smaliFile -Raw

# 1. Hook prepareOutputs bằng Regex
$pattern1 = "(:try_start_91\r?\n\s+)(invoke-virtual/range \{v1 \.\. v7\}, Landroid/hardware/camera2/impl/CameraDeviceImpl;->configureStreamsChecked)"
$replacement1 = "`$1invoke-static {v1, v3}, Landroid/hardware/camera2/impl/VcamEngine;->prepareOutputs(Landroid/hardware/camera2/impl/CameraDeviceImpl;Ljava/util/List;)V`r`n`r`n    `$2"

if ($content -match $pattern1) {
    $content = $content -replace $pattern1, $replacement1
    Write-Host "Hook 1 (prepareOutputs) applied successfully!"
} else {
    Write-Warning "Hook 1 pattern still not matched!"
}

# 4. Hook onCameraClose bằng Regex
$pattern4 = "(\.method public whitelist test-api close\(\)V[\s\S]*?monitor-enter v0\r?\n)"
$replacement4 = "`$1`r`n    invoke-static {p0}, Landroid/hardware/camera2/impl/VcamEngine;->onCameraClose(Landroid/hardware/camera2/CameraDevice;)V`r`n"

if ($content -match $pattern4) {
    $content = $content -replace $pattern4, $replacement4
    Write-Host "Hook 4 (onCameraClose) applied successfully!"
} else {
    Write-Warning "Hook 4 pattern still not matched!"
}

[System.IO.File]::WriteAllText($smaliFile, $content, [System.Text.Encoding]::UTF8)
Write-Host "All hooks finalized in CameraDeviceImpl.smali!"


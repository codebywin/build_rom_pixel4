$f = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\smali_classes2_v3\android\hardware\camera2\impl\CameraDeviceImpl.smali"
$content = Get-Content $f -Raw

# Thay move-object bằng move-object/from16
$content = $content.Replace("move-object v0, p0", "move-object/from16 v0, p0")
$content = $content.Replace("move-object v1, p2", "move-object/from16 v1, p2")
$content = $content.Replace("move-object v1, p0", "move-object/from16 v1, p0")

[System.IO.File]::WriteAllText($f, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Updated with move-object/from16!"


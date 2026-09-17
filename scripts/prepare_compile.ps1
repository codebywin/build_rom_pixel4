$compileDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_src"
$classesDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_classes"
$dexDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_dex"

if (Test-Path $compileDir) { Remove-Item -Recurse -Force $compileDir }
if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
if (Test-Path $dexDir) { Remove-Item -Recurse -Force $dexDir }

New-Item -ItemType Directory -Force -Path $compileDir | Out-Null
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

# Copy 9 file VCam
Copy-Item -Recurse -Force "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\vcam_src\*" $compileDir

# Copy 2 file Camera.java và CameraDeviceImpl.java đã mod
$targetCam = "$compileDir\core\java\android\hardware\Camera.java"
$targetDev = "$compileDir\core\java\android\hardware\camera2\impl\CameraDeviceImpl.java"
Copy-Item -Force "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\test_patch\core\java\android\hardware\Camera.java" $targetCam
Copy-Item -Force "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\test_patch\core\java\android\hardware\camera2\impl\CameraDeviceImpl.java" $targetDev

# Thu thập tất cả file .java
$javaFiles = Get-ChildItem -Path $compileDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$sourceFileList = "$compileDir\sources.txt"
[System.IO.File]::WriteAllLines($sourceFileList, $javaFiles)

Write-Host "Ready to compile $($javaFiles.Count) Java files!"

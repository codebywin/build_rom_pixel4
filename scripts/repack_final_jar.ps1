$workDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\repack_jar"
if (Test-Path $workDir) { Remove-Item -Recurse -Force $workDir }
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

$originalJar = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\framework.jar"
$targetJar = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\framework_vcam.jar"

# Giải nén jar gốc
& tar -xf $originalJar -C $workDir

# Thay thế classes2.dex đã patch
Copy-Item "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\classes2_mod.dex" "$workDir\classes2.dex" -Force

# Thêm classes7.dex chứa VCam Engine
Copy-Item "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_dex\classes.dex" "$workDir\classes7.dex" -Force

# Đóng gói lại thành zip/jar
$oldPwd = Get-Location
Set-Location $workDir
& jar -cfm $targetJar META-INF\MANIFEST.MF *
Set-Location $oldPwd

Write-Host "Repacked framework_vcam.jar successfully!"
Get-Item $targetJar | Select-Object Name, Length
& tar -tf $targetJar | Select-String "classes"


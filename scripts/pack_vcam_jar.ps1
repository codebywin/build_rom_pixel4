$originalJar = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\framework.jar"
$modJar = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\framework_mod.jar"
$vcamDex = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_dex\classes.dex"
$tempZipDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\jar_temp"

if (Test-Path $tempZipDir) { Remove-Item -Recurse -Force $tempZipDir }
New-Item -ItemType Directory -Force -Path $tempZipDir | Out-Null

Copy-Item $originalJar $modJar -Force

# Đổi tên classes.dex của VCam thành classes7.dex
$dex7 = "$tempZipDir\classes7.dex"
Copy-Item $vcamDex $dex7 -Force

# Thêm classes7.dex vào file jar bằng lệnh tar/jar
cd $tempZipDir
& jar -uf $modJar classes7.dex

Write-Host "Updated framework_mod.jar with classes7.dex!"
& tar -tf $modJar | Select-String "classes"

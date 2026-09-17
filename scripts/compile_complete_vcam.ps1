$sdkPlatformJar = "C:\Users\admin\AppData\Local\Android\Sdk\platforms\android-34\android.jar"
$d8Path = "C:\Users\admin\AppData\Local\Android\Sdk\build-tools\34.0.0\d8.bat"
$compileDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_src"
$classesDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_classes"
$dexDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\compile_dex"

if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
if (Test-Path $dexDir) { Remove-Item -Recurse -Force $dexDir }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

$frameworkJar = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\framework.jar"
$classPath = "$frameworkJar;$sdkPlatformJar"

$sourceFiles = @(
    "$compileDir\core\java\android\hardware\camera2\impl\CameraDeviceImpl.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamEngine.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamRenderer.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamConfig.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamMultiStreamManager.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamColorSync.java",
    "$compileDir\core\java\android\hardware\camera2\impl\VcamLicenseVerifier.java",
    "$compileDir\core\java\android\hardware\VcamYuvPlayer.java"
)

Write-Host "Compiling CameraDeviceImpl + VCam Camera2..."

$javacArgs = @(
    "-cp", $classPath,
    "-d", $classesDir,
    "-source", "1.8",
    "-target", "1.8",
    "-Xlint:none"
) + $sourceFiles

& javac $javacArgs

if ($LASTEXITCODE -ne 0) {
    Write-Error "javac compilation failed with code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "javac compilation successful!"

$classFiles = Get-ChildItem -Path $classesDir -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
Write-Host "Converting $($classFiles.Count) class files to dex..."

& $d8Path --output $dexDir --min-api 26 $classFiles

if ($LASTEXITCODE -ne 0) {
    Write-Error "d8 conversion failed with code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Host "SUCCESS! Generated complete Camera2 + VCam dex!"
Get-ChildItem -Path $dexDir


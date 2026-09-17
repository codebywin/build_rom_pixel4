$patchPath = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\patches\vcam_pixel4_a16.patch"
$baseDir = "c:\Users\admin\Desktop\codebywin\build_lineageOS_pixel4_a16\dump_device\vcam_src"

if (Test-Path $baseDir) { Remove-Item -Recurse -Force $baseDir }
New-Item -ItemType Directory -Force -Path $baseDir | Out-Null

$lines = Get-Content $patchPath
$currentFile = $null
$fileLines = [System.Collections.Generic.List[string]]::new()
$isFileSection = $false

foreach ($line in $lines) {
    if ($line -match "^diff --git a/(.*) b/.*") {
        if ($currentFile -and $fileLines.Count -gt 0) {
            $outPath = Join-Path $baseDir $currentFile
            $outDir = Split-Path $outPath -Parent
            if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
            [System.IO.File]::WriteAllLines($outPath, $fileLines, (New-Object System.Text.UTF8Encoding($false)))
        }
        $relPath = $matches[1]
        $fileLines.Clear()
        $isFileSection = $false
        
        if ($relPath -ne "core/java/android/hardware/Camera.java" -and $relPath -ne "core/java/android/hardware/camera2/impl/CameraDeviceImpl.java") {
            $currentFile = $relPath
            $isFileSection = $true
        } else {
            $currentFile = $null
        }
    }
    elseif ($isFileSection) {
        # Bỏ qua các header của git diff như +++ b/... hoặc @@ ...
        if ($line -match "^\+\+\+ " -or $line -match "^--- ") {
            continue
        }
        if ($line -match "^@@") {
            continue
        }
        if ($line -match "^\+(.*)") {
            $fileLines.Add($matches[1])
        }
    }
}

if ($currentFile -and $fileLines.Count -gt 0) {
    $outPath = Join-Path $baseDir $currentFile
    $outDir = Split-Path $outPath -Parent
    if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
    [System.IO.File]::WriteAllLines($outPath, $fileLines, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host "Clean extraction completed!"

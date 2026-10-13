# Script ho tro lay tag va commit message tu dong cho PUSH_GIT_WITH_TAG.bat

$now = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$dateTag = Get-Date -Format 'yyyy.MM.dd'

# 1. Lay danh sach tat ca cac tag
$tags = @(git tag -l 2>$null)

# 2. Tim cac tag dang vX.Y.Z (SemVer)
$semverTags = @($tags | Where-Object { $_ -match '^v?(\d+)\.(\d+)\.(\d+)$' })

if ($semverTags.Count -gt 0) {
    # Sap xep va lay tag phien ban cao nhat
    $sorted = $semverTags | Sort-Object { [version]($_.TrimStart('v')) }
    $lastTag = $sorted[-1]
    if ($lastTag -match '^v?(\d+)\.(\d+)\.(\d+)$') {
        $p = [int]$matches[3] + 1
        $nextTag = "v$($matches[1]).$($matches[2]).$p"
    }
} else {
    # Tim tag theo ngay dang vYYYY.MM.dd_vX hoac vYYYY.MM.dd_XX
    $todayTags = @($tags | Where-Object { $_ -match ('^v?' + [regex]::Escape($dateTag) + '(_|\.)v?(\d+)$') })
    if ($todayTags.Count -gt 0) {
        $maxN = 0
        foreach ($t in $todayTags) {
            if ($t -match ('^v?' + [regex]::Escape($dateTag) + '(_|\.)v?(\d+)$')) {
                $n = [int]$matches[2]
                if ($n -gt $maxN) { $maxN = $n }
            }
        }
        $lastTag = $todayTags[-1]
        $nextTag = "v${dateTag}_v$($maxN + 1)"
    } else {
        if ($tags.Count -gt 0) {
            $lastTag = $tags[-1]
            $nextTag = "v${dateTag}_v1"
        } else {
            $lastTag = "Chua co"
            $nextTag = "v1.0.0"
        }
    }
}

$defaultMsg = "Cap nhat code [$now]"

# Output dinh dang: CurrentTag|NextTag|DefaultCommitMessage
Write-Output "$lastTag|$nextTag|$defaultMsg"


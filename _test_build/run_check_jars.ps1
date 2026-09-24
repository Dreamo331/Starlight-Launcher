$jars = @(
    (Join-Path (Get-Location) 'lib\StarlightLauncher-2.0.0.jar'),
    (Join-Path (Get-Location) '启动库jar源码（修复）\target\StarlightLauncher-2.0.0.jar'),
    (Join-Path (Get-Location) '启动库jar源码\target\StarlightLauncher-2.0.0.jar'),
    (Join-Path $env:USERPROFILE '.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar')
)
foreach ($j in $jars) {
    if (-not (Test-Path $j)) { Write-Output ('MISSING: ' + $j); continue }
    Write-Output ('===== ' + $j + ' (' + (Get-Item $j).Length + ' bytes, ' + (Get-Item $j).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + ') =====')
    $bytes = [System.IO.File]::ReadAllBytes($j)
    $text = [System.Text.Encoding]::ASCII.GetString($bytes)
    if ($text.Contains('NonNMethodCodeHeapSize')) {
        Write-Output '  -> CONTAINS NonNMethodCodeHeapSize'
    } else {
        Write-Output '  -> no NonNMethodCodeHeapSize'
    }
    if ($text.Contains('CodeHeap')) {
        Write-Output '  -> CONTAINS CodeHeap (check)'
    } else {
        Write-Output '  -> no CodeHeap'
    }
}

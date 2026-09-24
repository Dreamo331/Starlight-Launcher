$logs = Join-Path (Get-Location) 'logs'
$latest = Get-ChildItem $logs -Filter 'minecraft_launch_*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 3
foreach ($f in $latest) {
    Write-Output ('===== ' + $f.Name + ' (' + $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + ') =====')
    $content = [System.IO.File]::ReadAllText($f.FullName)
    $lines = $content -split "`n"
    foreach ($l in $lines) {
        $t = $l.Trim()
        if ($t.Length -gt 0 -and ($t -match 'NonNMethodCodeHeap|java|Java|正在启动|模式|失败|ERROR|error|Exception|参数|命令')) {
            if ($t.Length -gt 400) { $t = $t.Substring(0, 400) }
            Write-Output $t
        }
    }
}
Write-Output '===== error logs latest ====='
$errs = Get-ChildItem $logs -Filter 'minecraft_error_*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 2
foreach ($f in $errs) {
    Write-Output ('===== ' + $f.Name + ' =====')
    [System.IO.File]::ReadAllLines($f.FullName) | Select-Object -First 30 | ForEach-Object { Write-Output $_ }
}

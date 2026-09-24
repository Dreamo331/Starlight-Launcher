$logs = Join-Path (Get-Location) 'logs'
Get-ChildItem $logs -Filter 'minecraft_launch_*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 12 | ForEach-Object {
    Write-Output ('===== ' + $_.Name + ' (' + $_.LastWriteTime.ToString('MM-dd HH:mm:ss') + ', ' + $_.Length + 'B) =====')
}
$f2135 = Get-ChildItem $logs -Filter 'minecraft_launch_*.log' | Sort-Object LastWriteTime | Select-Object -First 1
Write-Output ('##### FULL CONTENT (oldest): ' + $f2135.Name + ' #####')
[System.IO.File]::ReadAllLines($f2135.FullName) | ForEach-Object { Write-Output $_ }

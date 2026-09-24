$w = (Get-Location).Path
Write-Output ('=== ' + $w + '\v1.0.0-rc2-2 ===')
$d1 = Join-Path $w 'v1.0.0-rc2-2'
Get-ChildItem -Path $d1 -Recurse -File | ForEach-Object {
    Write-Output ($_.FullName.Substring($w.Length + 1) + '  |  ' + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  |  ' + $_.Length)
}
Write-Output ('=== ' + $w + '\v1.0.0-rc2-2\logs ===')
$d2 = Join-Path $d1 'logs'
if (Test-Path $d2) { Get-ChildItem -Path $d2 -File | ForEach-Object { Write-Output ($_.Name + '  |  ' + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  |  ' + $_.Length) } } else { Write-Output '(no logs dir)' }
Write-Output ('=== ' + $w + '\logs (launcher) ===')
$d3 = Join-Path $w 'logs'
Get-ChildItem -Path $d3 -File | Sort-Object LastWriteTime -Descending | Select-Object -First 8 | ForEach-Object { Write-Output ($_.Name + '  |  ' + $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  |  ' + $_.Length) }

param([string]$Base)
Write-Output '=== v1.0.0-rc2-2 folder file times ==='
$vf = Join-Path $Base 'v1.0.0-rc2-2'
[System.IO.Directory]::GetFiles($vf) | ForEach-Object {
    $fi = Get-Item -LiteralPath $_
    Write-Output ($fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  ' + $fi.Name)
}
Write-Output ''
Write-Output '=== launcher logs (last 12) ==='
$logDir = Join-Path $Base 'logs'
[System.IO.Directory]::GetFiles($logDir, 'minecraft_launch_*.log') | ForEach-Object {
    $fi = Get-Item -LiteralPath $_
    [PSCustomObject]@{ T = $fi.LastWriteTime; N = $fi.Name; S = $fi.Length }
} | Sort-Object T | Select-Object -Last 12 | ForEach-Object {
    Write-Output ($_.T.ToString('MM-dd HH:mm:ss') + '  ' + $_.N + '  (' + $_.S + 'B)')
}
Write-Output ''
Write-Output '=== minecraft error logs (last 6) ==='
[System.IO.Directory]::GetFiles($logDir, 'minecraft_error_*.log') | ForEach-Object {
    $fi = Get-Item -LiteralPath $_
    [PSCustomObject]@{ T = $fi.LastWriteTime; N = $fi.Name; S = $fi.Length }
} | Sort-Object T | Select-Object -Last 6 | ForEach-Object {
    Write-Output ($_.T.ToString('MM-dd HH:mm:ss') + '  ' + $_.N + '  (' + $_.S + 'B)')
}
Write-Output ''
Write-Output '=== starlight.ini ==='
$ini = Join-Path $Base 'Starlight-Launcher\starlight.ini'
if (Test-Path -LiteralPath $ini) { [System.IO.File]::ReadAllText($ini) } else { Write-Output 'no starlight.ini' }
Write-Output ''
Write-Output '=== .minecraft/logs latest.log time ==='
$ml = 'D:\Starlight Launcher启动器工程-Java\.minecraft\logs\latest.log'
if (Test-Path -LiteralPath $ml) {
    $fi = Get-Item -LiteralPath $ml
    Write-Output ($fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  size=' + $fi.Length)
    $head = [System.IO.File]::ReadAllLines($ml) | Select-Object -First 3
    $head | ForEach-Object { Write-Output $_ }
} else { Write-Output 'no latest.log' }

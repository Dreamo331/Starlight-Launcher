$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\gui\UIGeneralControlClass.java'
$lines = [System.IO.File]::ReadAllLines($base)
for ($i = 565; $i -lt [Math]::Min(600, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }
Write-Output '----- 940-1030 -----'
for ($i = 939; $i -lt [Math]::Min(1030, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

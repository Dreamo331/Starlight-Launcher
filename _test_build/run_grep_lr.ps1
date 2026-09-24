$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\service\GameLauncherService.java'
$c = [System.IO.File]::ReadAllText($base)
$lines = $c -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'LaunchResult|^import ') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}

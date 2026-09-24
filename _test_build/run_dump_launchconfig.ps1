$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\model\LaunchConfig.java'
$c = [System.IO.File]::ReadAllText($base)
$lines = $c -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    $s = $lines[$i].Trim()
    if ($s.Length -gt 0) { Write-Output (($i + 1).ToString() + ': ' + $s) }
}

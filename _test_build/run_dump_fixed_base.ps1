$f = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' -and $_.FullName -match '修复' } | Select-Object -First 1
Write-Output ('FILE=' + $f.FullName)
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 840; $i -lt [Math]::Min(895, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }
Write-Output '===== ForgeLegacy filter ====='
$fl = Get-ChildItem (Get-Location) -Recurse -Filter 'ForgeLegacyLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' -and $_.FullName -match '修复' } | Select-Object -First 1
if ($fl) {
    $fll = [System.IO.File]::ReadAllLines($fl.FullName)
    for ($i = 50; $i -lt [Math]::Min(85, $fll.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $fll[$i].TrimEnd()) }
}
Write-Output '===== imports of BaseLauncher (top 60) ====='
for ($i = 0; $i -lt [Math]::Min(60, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

$f = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' } | Select-Object -First 1
if (-not $f) { Write-Output 'NOT FOUND'; exit }
Write-Output ('FILE=' + $f.FullName)
$lines = [System.IO.File]::ReadAllLines($f.FullName)
Write-Output ('TOTAL=' + $lines.Count)
for ($i = 780; $i -lt [Math]::Min(910, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

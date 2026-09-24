$f = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' } | Select-Object -First 1
if (-not $f) { Write-Output 'NOT FOUND'; exit }
Write-Output ('FILE=' + $f.FullName)
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 770; $i -lt [Math]::Min(900, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

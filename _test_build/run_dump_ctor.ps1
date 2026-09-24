$all = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$f = $all | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 66; $i -lt [Math]::Min(140, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

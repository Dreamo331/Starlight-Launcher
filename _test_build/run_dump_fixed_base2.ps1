$all = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
# 两份副本：路径更长的为修复版
$f = $all | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
Write-Output ('FILE=' + $f.FullName)
$lines = [System.IO.File]::ReadAllLines($f.FullName)
Write-Output ('TOTAL=' + $lines.Count)
for ($i = 835; $i -lt [Math]::Min(900, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }
Write-Output '===== imports (top 70) ====='
for ($i = 0; $i -lt [Math]::Min(70, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }

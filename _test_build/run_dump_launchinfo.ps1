$f = Get-ChildItem (Get-Location) -Recurse -Filter 'LaunchInfo.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' } | Select-Object -First 1
if ($f) {
    Write-Output ('FILE=' + $f.FullName)
    $lines = [System.IO.File]::ReadAllLines($f.FullName)
    foreach ($l in $lines) { $t = $l.TrimEnd(); if ($t.Length -gt 0) { Write-Output $t } }
} else { Write-Output 'LaunchInfo NOT FOUND' }

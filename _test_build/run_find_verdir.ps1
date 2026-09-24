$all = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$f = $all | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'verDir = |verDir=|assetsDir = |jarPath = |libsRoot = |resolveVersionDir|initialize\(|initPaths|resolvePaths') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}

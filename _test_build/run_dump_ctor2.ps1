$all = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$f = $all | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'public BaseLauncher|protected BaseLauncher|BaseLauncher\(LaunchInfo|launchInfo = |setLaunchInfo') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}
Write-Output '===== ctor region ====='
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'BaseLauncher\(LaunchInfo') {
        for ($j = $i; $j -lt [Math]::Min($i + 40, $lines.Count); $j++) { Write-Output (($j + 1).ToString() + ': ' + $lines[$j].TrimEnd()) }
        break
    }
}

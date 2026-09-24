$all = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$f = $all | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$lines = [System.IO.File]::ReadAllLines($f.FullName)
for ($i = 900; $i -lt [Math]::Min(960, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }
Write-Output '===== find buildJvmBase start & getJavaPath usages ====='
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'protected List<String> buildJvmBase|getJavaPath\(\)|private.*cached.*Version|detectGameJava') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}

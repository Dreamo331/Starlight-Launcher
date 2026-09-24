$f = Get-ChildItem (Get-Location) -Recurse -Filter 'BaseLauncher.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' } | Select-Object -First 1
$lines = [System.IO.File]::ReadAllLines($f.FullName)
Write-Output ('FILE=' + $f.FullName)
for ($i = 690; $i -lt [Math]::Min(790, $lines.Count); $i++) { Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd()) }
Write-Output '===== PlatformUtils ====='
$pf = Get-ChildItem (Join-Path (Split-Path $f.FullName -Parent) '..\..') -Recurse -Filter 'PlatformUtils.java' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($pf) {
    $pl = [System.IO.File]::ReadAllLines($pf.FullName)
    for ($i = 0; $i -lt $pl.Count; $i++) {
        if ($pl[$i] -match 'getJavaVersion|java\.specification|java\.version|class PlatformUtils') {
            Write-Output (($i + 1).ToString() + ': ' + $pl[$i].TrimEnd())
        }
    }
} else { Write-Output 'PlatformUtils NOT FOUND' }

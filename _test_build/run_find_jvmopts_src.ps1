$w = (Get-Location).Path
$dirs = Get-ChildItem $w -Directory | Where-Object { $_.Name -match 'jar' }
foreach ($d in $dirs) {
    Write-Output ('##### ROOT: ' + $d.FullName + ' #####')
    $files = [System.IO.Directory]::GetFiles($d.FullName, '*.java', 'AllDirectories')
    foreach ($f in $files) {
        try { $c = [System.IO.File]::ReadAllText($f) } catch { continue }
        if ($c -match 'NonNMethodCodeHeapSize|CodeHeap|JVMCI|EnableJVMCI|XX:') {
            $rel = $f.Substring($w.Length + 1)
            Write-Output ('===== ' + $rel + ' =====')
            $lines = $c -split "`n"
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match 'NonNMethodCodeHeapSize|CodeHeap|JVMCI|EnableJVMCI|XX:') {
                    $s = $lines[$i].Trim()
                    if ($s.Length -gt 220) { $s = $s.Substring(0, 220) }
                    Write-Output (($i + 1).ToString() + ': ' + $s)
                }
            }
        }
    }
}
Write-Output 'DONE'

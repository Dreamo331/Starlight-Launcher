$roots = @(
    (Join-Path (Get-Location) 'src'),
    (Join-Path (Get-Location) '启动库jar源码'),
    (Join-Path (Get-Location) '启动库jar源码（修复）'),
    (Join-Path (Get-Location) 'Starlight-Launcher')
)
foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    $files = [System.IO.Directory]::GetFiles($r, '*.*', 'AllDirectories')
    foreach ($f in $files) {
        if ($f -match '\.(class|jar|zip|png|jpg)$') { continue }
        try { $c = [System.IO.File]::ReadAllText($f) } catch { continue }
        if ($c -match 'NonNMethodCodeHeapSize') {
            $rel = $f.Substring((Get-Location).Path.Length + 1)
            Write-Output ('===== ' + $rel + ' =====')
            $lines = $c -split "`n"
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match 'NonNMethodCodeHeapSize') {
                    $s = $lines[$i].Trim()
                    if ($s.Length -gt 200) { $s = $s.Substring(0, 200) }
                    Write-Output (($i + 1).ToString() + ': ' + $s)
                }
            }
        }
    }
}
Write-Output 'DONE'

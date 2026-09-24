Add-Type -AssemblyName System.IO.Compression

$files = @('2026-08-17-6.log.gz','2026-08-17-5.log.gz','2026-08-17-4.log.gz','2026-08-17-3.log.gz','2026-08-17-2.log.gz','2026-08-17-1.log.gz','2026-08-16-6.log.gz','2026-08-16-5.log.gz')
foreach ($f in $files) {
    $p = "D:\Starlight Launcher启动器工程-Java\.minecraft\logs\$f"
    if (-not (Test-Path $p)) { continue }
    $fs = [System.IO.File]::OpenRead($p)
    $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object System.IO.StreamReader($gs)
    $c = $sr.ReadToEnd()
    $sr.Close()
    $lines = $c -split "`n"
    Write-Output "=== $f (lines=$($lines.Count)) ==="
    $hits = $lines | Select-String -Pattern 'Exception|ERROR|FATAL|crash|Stopping|exit' | Select-Object -First 10
    if ($hits) { $hits | ForEach-Object { $_.Line.Substring(0, [Math]::Min(200, $_.Line.Length)) } } else { Write-Output 'no error lines' }
}

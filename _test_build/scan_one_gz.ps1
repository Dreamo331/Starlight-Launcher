param([string]$LogPath)
$ErrorActionPreference = 'Continue'
Write-Output "PATH: $LogPath"
Write-Output ("EXISTS: " + (Test-Path $LogPath))
if (-not (Test-Path $LogPath)) { exit 1 }
try {
    $fs = [System.IO.File]::OpenRead($LogPath)
    $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object System.IO.StreamReader($gs)
    $c = $sr.ReadToEnd()
    $sr.Close()
    Write-Output ("LEN: " + $c.Length)
    $lines = $c -split "`n"
    Write-Output ("LINES: " + $lines.Count)
    $hits = $lines | Select-String -Pattern 'Exception|ERROR|FATAL|Stopping' | Select-Object -First 8
    if ($hits) { $hits | ForEach-Object { $_.Line.Substring(0, [Math]::Min(250, $_.Line.Length)) } } else { Write-Output 'no error lines' }
} catch {
    Write-Output ("ERROR: " + $_.Exception.Message)
}

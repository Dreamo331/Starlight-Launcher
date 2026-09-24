param([string]$LogPath)
$ErrorActionPreference = 'Continue'
if (-not (Test-Path $LogPath)) { Write-Output 'NOT FOUND'; exit 1 }
try {
    $fs = [System.IO.File]::OpenRead($LogPath)
    $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object System.IO.StreamReader($gs)
    $c = $sr.ReadToEnd()
    $sr.Close()
    Write-Output $c
} catch {
    Write-Output ("ERROR: " + $_.Exception.Message)
}

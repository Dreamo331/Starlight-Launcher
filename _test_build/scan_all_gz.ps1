param([string]$LogDir)
$ErrorActionPreference = 'Continue'
$files = Get-ChildItem -LiteralPath $LogDir -Filter '*.log.gz' -File | Sort-Object Name -Descending
foreach ($f in $files) {
    try {
        $fs = [System.IO.File]::OpenRead($f.FullName)
        $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
        $sr = New-Object System.IO.StreamReader($gs)
        $c = $sr.ReadToEnd()
        $sr.Close()
        $lines = $c -split "`n"
        $start = ($lines | Select-String -Pattern 'Loading Minecraft' | Select-Object -First 1)
        $errors = ($lines | Select-String -Pattern 'Exception|ERROR|FATAL|Stopping' | Select-Object -First 5)
        $first = if ($start) { $start.Line.Substring(11, [Math]::Min(60, $start.Line.Length - 11)) } else { '???' }
        Write-Output ("=== " + $f.Name + " | " + $first + " | lines=" + $lines.Count)
        if ($errors) { $errors | ForEach-Object { Write-Output ("    " + $_.Line.Substring(0, [Math]::Min(160, $_.Line.Length))) } }
    } catch {
        Write-Output ("=== " + $f.Name + " | ERROR: " + $_.Exception.Message)
    }
}

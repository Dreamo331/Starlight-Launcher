Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'C:\Users\Administrator\.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar'
$z = [IO.Compression.ZipFile]::OpenRead($jar)
$hits = @{}
foreach ($e in $z.Entries) {
    if ($e.FullName -notlike '*.class') { continue }
    $s = $e.Open()
    $ms = New-Object IO.MemoryStream
    $s.CopyTo($ms)
    $bytes = $ms.ToArray()
    $text = [Text.Encoding]::ASCII.GetString($bytes)
    if ($text.Contains('com/sun/jna') -or $text.Contains('Lcom/sun/jna') -or $text.Contains('oshi/')) {
        $hits[$e.FullName] = $true
    }
    $ms.Dispose(); $s.Dispose()
}
$z.Dispose()
if ($hits.Count -eq 0) { Write-Output 'NO_JNA_OSHI_REFS' } else { $hits.Keys | ForEach-Object { Write-Output $_ } }

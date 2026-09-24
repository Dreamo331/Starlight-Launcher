Add-Type -AssemblyName System.IO.Compression.FileSystem
$w = (Get-Location).Path
$jars = @(
    (Join-Path $w 'lib\StarlightLauncher-2.0.0.jar'),
    (Join-Path $env:USERPROFILE '.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar'),
    (Join-Path $w 'OLDUI\lib\StarlightLauncher-2.0.0.jar')
)
foreach ($j in $jars) {
    if (-not (Test-Path $j)) { Write-Output ('MISSING: ' + $j); continue }
    Write-Output ('===== ' + $j + ' =====')
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($j)
        $hit = $false
        foreach ($entry in $zip.Entries) {
            if ($entry.Name -notmatch '\.class$') { continue }
            $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
            $content = $reader.ReadToEnd()
            $reader.Close()
            if ($content.Contains('NonNMethodCodeHeapSize')) {
                $hit = $true
                Write-Output ('  -> ' + $entry.FullName + ' CONTAINS NonNMethodCodeHeapSize')
            }
        }
        if (-not $hit) { Write-Output '  -> no NonNMethodCodeHeapSize in any class' }
        $zip.Dispose()
    } catch { Write-Output ('  ERROR: ' + $_.Exception.Message) }
}

$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\listjava\FindAllJavaWindows.java'
$c = [System.IO.File]::ReadAllText($base)
$lines = $c -split "`n"
Write-Output ('TOTAL_LINES=' + $lines.Count)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'scanDiskRootJavaDirs|NonNMethodCodeHeap|JvmArgs|jvmArgs|UnlockDiagnostic|JVMCI|GraalVM|graal') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}
Write-Output '===== starlight.ini ====='
$ini = Join-Path (Get-Location) 'Starlight-Launcher\starlight.ini'
if (Test-Path $ini) { [System.IO.File]::ReadAllLines($ini) | ForEach-Object { Write-Output $_ } } else { Write-Output 'NOT FOUND' }
Write-Output '===== .minecraft 版 starlight.ini ====='
$ini2 = Join-Path (Get-Location) 'starlight.ini'
if (Test-Path $ini2) { [System.IO.File]::ReadAllLines($ini2) | ForEach-Object { Write-Output $_ } } else { Write-Output 'NOT FOUND' }

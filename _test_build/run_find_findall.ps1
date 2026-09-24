$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\listjava\FindAllJavaWindows.java'
if (-not (Test-Path $base)) {
    $files = [System.IO.Directory]::GetFiles((Join-Path (Get-Location) 'src'), 'FindAllJavaWindows.java', 'AllDirectories')
    if ($files.Count -gt 0) { $base = $files[0] } else { Write-Output 'NOT FOUND'; exit }
}
Write-Output ('FILE=' + $base)
$c = [System.IO.File]::ReadAllText($base)
$lines = $c -split "`n"
Write-Output ('TOTAL_LINES=' + $lines.Count)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'Program Files|JAVA_HOME|REG|registry|jdk|jre|scan|directory|Directory|File\.|SystemDrive|ProgramFiles|HOMEDRIVE|java_home|\\java|\.jdks|search|Search') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}

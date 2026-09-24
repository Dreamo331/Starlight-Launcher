$base = Join-Path (Get-Location) 'src\main\java\com\example\starlight\gui\UIGeneralControlClass.java'
$c = [System.IO.File]::ReadAllText($base)
$lines = $c -split "`n"
Write-Output ('TOTAL_LINES=' + $lines.Count)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'autoSelectJavaPath|resolveRealMcVersion|getRequiredJavaMajor|extractMcVersion|FindAllJavaWindows|autoJava|AutoJava|Java8|java8') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}

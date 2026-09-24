param([string]$Base)
Write-Output '=== test-classes *.class ==='
$tc = Join-Path $Base 'target\test-classes'
if (Test-Path -LiteralPath $tc) {
    [System.IO.Directory]::GetFiles($tc, '*.class', 'AllDirectories') | ForEach-Object { Write-Output $_ }
} else { Write-Output 'no test-classes dir' }
Write-Output '=== src/test/java *.java ==='
$sj = Join-Path $Base 'src\test\java'
if (Test-Path -LiteralPath $sj) {
    [System.IO.Directory]::GetFiles($sj, '*.java', 'AllDirectories') | ForEach-Object { Write-Output $_ }
} else { Write-Output 'no src/test/java dir' }
Write-Output '=== classes UIGeneralControlClass ==='
$cls = Join-Path $Base 'target\classes\com\example\starlight\gui\UIGeneralControlClass.class'
Write-Output ('UIGeneralControlClass.class exists: ' + (Test-Path -LiteralPath $cls) + '  modified: ' + $(if (Test-Path -LiteralPath $cls) { (Get-Item -LiteralPath $cls).LastWriteTime } else { '-' }))

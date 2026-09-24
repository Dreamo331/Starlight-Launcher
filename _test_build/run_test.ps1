param([string]$Base)
$m2 = Join-Path $env:USERPROFILE '.m2\repository'
$jars = [System.IO.Directory]::GetFiles($m2, '*.jar', 'AllDirectories')
$cp = @(
    (Join-Path $Base 'target\classes'),
    (Join-Path $Base 'target\test-classes'),
    (Join-Path $Base 'lib\StarlightLauncher-2.0.0.jar')
) + $jars
$cpStr = $cp -join ';'
Write-Output ('classpath len: ' + $cpStr.Length)
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { $javaExe = 'java' }
Write-Output ('java: ' + $javaExe)
if ($cpStr.Length -lt 7000) {
    & $javaExe -cp $cpStr com.example.starlight.test.TestAutoJavaFix
} else {
    $argFile = Join-Path $Base '_test_build\java_args.txt'
    $lines = @('-cp', $cpStr, 'com.example.starlight.test.TestAutoJavaFix')
    [System.IO.File]::WriteAllLines($argFile, $lines, [System.Text.Encoding]::Default)
    & $javaExe "@$argFile"
}

$w = (Get-Location).Path
$poms = Get-ChildItem $w -Recurse -Filter 'pom.xml' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$pom = $poms | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$dir = $pom.DirectoryName
Push-Location $dir
try {
    Write-Output '===== test-compile ====='
    & mvn -q test-compile 2>&1 | ForEach-Object { Write-Output $_ }
    Write-Output ('COMPILE_EXIT=' + $LASTEXITCODE)
    $gson = Join-Path $env:USERPROFILE '.m2\repository\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
    $cp = (Join-Path $dir 'target\test-classes') + ';' + (Join-Path $dir 'target\classes') + ';' + $gson
    Write-Output '===== run TestGameJavaVersion ====='
    & java -cp $cp com.startgame.launcher.TestGameJavaVersion 2>&1 | ForEach-Object { Write-Output $_ }
    Write-Output ('RUN_EXIT=' + $LASTEXITCODE)
} finally {
    Pop-Location
}

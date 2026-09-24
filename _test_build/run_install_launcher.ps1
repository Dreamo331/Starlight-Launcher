$w = (Get-Location).Path
$poms = Get-ChildItem $w -Recurse -Filter 'pom.xml' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$pom = $poms | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
$jar = Join-Path $pom.DirectoryName 'target\StarlightLauncher-2.0.0.jar'
$libJar = Join-Path $w 'lib\StarlightLauncher-2.0.0.jar'
Copy-Item $jar $libJar -Force
Write-Output ('COPIED lib = ' + (Get-Item $libJar).Length + 'B ' + (Get-Item $libJar).LastWriteTime.ToString('HH:mm:ss'))
Write-Output '===== mvn install:install-file ====='
& mvn -q install:install-file -Dfile=$jar -DgroupId=com.starlight -DartifactId=starlight-launcher-core -Dversion=2.0.0 -Dpackaging=jar 2>&1 | ForEach-Object { Write-Output $_ }
Write-Output ('EXIT=' + $LASTEXITCODE)
$m2jar = Join-Path $env:USERPROFILE '.m2\repository\com\starlight\starlight-launcher-core\2.0.0\starlight-launcher-core-2.0.0.jar'
Write-Output ('M2 = ' + (Get-Item $m2jar).Length + 'B ' + (Get-Item $m2jar).LastWriteTime.ToString('HH:mm:ss'))

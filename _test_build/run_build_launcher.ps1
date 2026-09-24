$w = (Get-Location).Path
# 找修复版 pom（路径最长）
$poms = Get-ChildItem $w -Recurse -Filter 'pom.xml' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$pom = $poms | Sort-Object { $_.FullName.Length } -Descending | Select-Object -First 1
Write-Output ('POM=' + $pom.FullName)
$dir = $pom.DirectoryName
Push-Location $dir
try {
    Write-Output '===== mvn package ====='
    & mvn -q package -DskipTests 2>&1 | ForEach-Object { Write-Output $_ }
    Write-Output ('EXIT=' + $LASTEXITCODE)
} finally {
    Pop-Location
}
if (Test-Path (Join-Path $dir 'target\StarlightLauncher-2.0.0.jar')) {
    $tgt = (Get-Item (Join-Path $dir 'target\StarlightLauncher-2.0.0.jar'))
    Write-Output ('BUILT=' + $tgt.Length + 'B ' + $tgt.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
} else {
    Write-Output 'BUILD TARGET NOT FOUND'
}

$pom = Join-Path (Get-Location) 'pom.xml'
$lines = [System.IO.File]::ReadAllLines($pom)
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'starlight|startgame|<dependency>|<artifactId>|<version>|<scope>') {
        Write-Output (($i + 1).ToString() + ': ' + $lines[$i].TrimEnd())
    }
}
Write-Output '===== .m2 starlight dirs ====='
$m2base = Join-Path $env:USERPROFILE '.m2\repository\com\starlight'
if (Test-Path $m2base) {
    Get-ChildItem $m2base -Directory | ForEach-Object { Write-Output $_.Name }
    Get-ChildItem $m2base -Recurse -Filter '*.jar' | ForEach-Object { Write-Output ($_.FullName + ' | ' + $_.LastWriteTime.ToString('MM-dd HH:mm:ss')) }
}

$out = Join-Path $PSScriptRoot "probe_out.txt"
$err = Join-Path $PSScriptRoot "probe_err.txt"
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
mvn -q exec:java -o "-Dexec.mainClass=com.example.starlight.LayoutProbe" *> $out
$code = $LASTEXITCODE
Pop-Location
Write-Output "EXIT=$code"

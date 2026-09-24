$exe = "D:\.jdks\ms-17.0.19\bin\jar.exe"
$src = "C:\Users\Administrator\.m2\repository\org\openjfx\javafx-controls\17.0.6\javafx-controls-17.0.6-sources.jar"
$dst = Join-Path $PSScriptRoot "javafx-src"
if (-not (Test-Path $dst)) { New-Item -ItemType Directory -Path $dst | Out-Null }
Push-Location $dst
& $exe xf $src "javafx/scene/control/skin/ScrollPaneSkin.java"
Pop-Location
Write-Output "EXTRACTED: $(Test-Path (Join-Path $dst 'javafx\scene\control\skin\ScrollPaneSkin.java'))"

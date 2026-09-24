$javap = "D:\.jdks\ms-17.0.19\bin\javap.exe"
$jar = "C:\Users\Administrator\.m2\repository\org\openjfx\javafx-controls\17.0.6\javafx-controls-17.0.6.jar"
$out = Join-Path $PSScriptRoot "ScrollPaneSkin.txt"
& $javap -p -c -classpath $jar "javafx.scene.control.skin.ScrollPaneSkin" > $out 2>&1
Write-Output "DUMPED: $((Get-Item $out).Length) bytes"
Get-Content $out -TotalCount 3

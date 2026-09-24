Get-ChildItem "C:\Users\Administrator\.m2\repository\org\openjfx\javafx-controls\17.0.6" | ForEach-Object { Write-Output "$($_.Name)  $($_.Length)" }

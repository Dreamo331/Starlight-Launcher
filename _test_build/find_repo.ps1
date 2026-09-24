$candidates = @(
    "$env:USERPROFILE\.m2\repository",
    "D:\maven-repo",
    "D:\.m2\repository",
    "E:\.m2\repository",
    "D:\apache-maven-3.9.6\repository"
)
foreach ($p in $candidates) {
    if (Test-Path $p) {
        Write-Output "FOUND: $p"
        Get-ChildItem "$p\org\openjfx\javafx-controls" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
    }
}

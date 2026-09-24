$all = Get-ChildItem (Get-Location) -Recurse -Filter 'pom.xml' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' }
$all | Sort-Object { $_.FullName.Length } -Descending | ForEach-Object {
    Write-Output ('===== ' + $_.FullName + ' =====')
    $lines = [System.IO.File]::ReadAllLines($_.FullName)
    foreach ($l in $lines) {
        $t = $l.Trim()
        if ($t -match 'maven.compiler|<source>|<target>|<release>|<groupId>|<artifactId>|<version>|javafx') {
            Write-Output $t
        }
    }
}

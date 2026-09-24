$base = Get-ChildItem (Get-Location) -Recurse -Filter '*.java' -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match 'jar' -and $_.FullName -notmatch 'OLDUI' -and $_.FullName -notmatch '\\src\\main\\java\\com\\startgame\\launcher\\BaseLauncher\.java$' }
$base | ForEach-Object {
    $c = [System.IO.File]::ReadAllText($_.FullName)
    if ($c -match 'extends BaseLauncher|buildJvmBase|buildJvm') {
        $rel = $_.FullName.Substring((Get-Location).Path.Length + 1)
        Write-Output ('===== ' + $rel + ' =====')
        $lines = $c -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'extends BaseLauncher|buildJvmBase|buildJvm\(') {
                Write-Output (($i + 1).ToString() + ': ' + $lines[$i].Trim())
            }
        }
    }
}
Write-Output 'DONE'

$root = Join-Path (Get-Location) 'src\main\java\com\example\starlight'
$files = [System.IO.Directory]::GetFiles($root, '*.java', 'AllDirectories')
foreach ($f in $files) {
    $c = [System.IO.File]::ReadAllText($f)
    if ($c -match 'new LaunchResult\(|findLatestErrorLog\(|collectCrashData\(') {
        $rel = $f.Substring($root.Length - 9)
        Write-Output ('===== ' + $rel + ' =====')
        $lines = $c -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'new LaunchResult\(|findLatestErrorLog\(|collectCrashData\(') {
                $s = $lines[$i].Trim()
                if ($s.Length -gt 160) { $s = $s.Substring(0, 160) }
                Write-Output (($i + 1).ToString() + ': ' + $s)
            }
        }
    }
}

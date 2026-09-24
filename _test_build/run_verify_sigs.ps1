$root = Join-Path (Get-Location) 'src'
$files = [System.IO.Directory]::GetFiles($root, '*.java', 'AllDirectories')
$hits = 0
foreach ($f in $files) {
    $c = [System.IO.File]::ReadAllText($f)
    if ($c -match 'findLatestErrorLog\(|new LaunchResult\(|collectCrashData\(') {
        $rel = $f.Substring((Get-Location).Path.Length + 1)
        $lines = $c -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match 'findLatestErrorLog\(|new LaunchResult\(|collectCrashData\(') {
                $hits++
                $s = $lines[$i].Trim()
                if ($s.Length -gt 160) { $s = $s.Substring(0, 160) }
                Write-Output ($rel + ':' + ($i + 1) + ': ' + $s)
            }
        }
    }
}
Write-Output ('TOTAL: ' + $hits)

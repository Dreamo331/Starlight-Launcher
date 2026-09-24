param([string]$Base, [string]$Pattern)
$files = [System.IO.Directory]::GetFiles($Base, '*.java', 'AllDirectories')
foreach ($f in $files) {
    $c = [System.IO.File]::ReadAllText($f)
    if ($c -match $Pattern) {
        $rel = $f.Substring($Base.Length)
        Write-Output ('===== ' + $rel + ' =====')
        $lines = $c -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match $Pattern) {
                $s = $lines[$i].Trim()
                if ($s.Length -gt 180) { $s = $s.Substring(0, 180) }
                Write-Output ($i + 1).ToString() + ': ' + $s
            }
        }
    }
}

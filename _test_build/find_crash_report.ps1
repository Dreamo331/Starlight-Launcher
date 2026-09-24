param([string]$Base)
$files = [System.IO.Directory]::GetFiles($Base, '*.java', 'AllDirectories')
$pat = 'latest\.log|crash_summary|错误报告|E0001|关键日志'
foreach ($f in $files) {
    $c = [System.IO.File]::ReadAllText($f)
    if ($c -match $pat) {
        $rel = $f.Substring($Base.Length)
        Write-Output ('===== ' + $rel + ' =====')
        $lines = $c -split "`n"
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match $pat) {
                $s = $lines[$i].Trim()
                if ($s.Length -gt 200) { $s = $s.Substring(0, 200) }
                Write-Output ($i + 1).ToString() + ': ' + $s
            }
        }
    }
}

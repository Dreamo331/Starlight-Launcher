param([string]$FilePath, [string]$Pattern)
$c = [System.IO.File]::ReadAllText($FilePath)
$lines = $c -split "`n"
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $Pattern) {
        Write-Output ("{0}: {1}" -f ($i + 1), $lines[$i].Trim())
    }
}

param([string]$FilePath, [int]$Start = 1, [int]$End = -1)
$lines = [System.IO.File]::ReadAllLines($FilePath)
Write-Output ('TotalLines=' + $lines.Count)
if ($End -lt 0) { $End = $lines.Count }
for ($i = $Start - 1; $i -lt [Math]::Min($End, $lines.Count); $i++) {
    Write-Output ($i + 1).ToString() + ': ' + $lines[$i]
}

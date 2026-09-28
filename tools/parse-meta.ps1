param(
    [Parameter(Mandatory=$true)][string]$Meta,
    [Parameter(Mandatory=$true)][string]$Out
)

$lines = Get-Content -LiteralPath $Meta -Encoding UTF8
$result = foreach ($l in $lines) {
    $l = $l -replace [char]0xFEFF, ''
    $t = $l.TrimStart()
    if ($t -eq '') { continue }
    if ($t.StartsWith('#') -or $t.StartsWith(';')) { continue }
    $i = $l.IndexOf('=')
    if ($i -lt 1) { continue }
    $k = $l.Substring(0, $i).Trim()
    $v = $l.Substring($i + 1)
    'set "{0}={1}"' -f $k, $v
}
Set-Content -LiteralPath $Out -Value $result -Encoding ASCII
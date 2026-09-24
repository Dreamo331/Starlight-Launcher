param([string]$Base)
$v = Join-Path $Base 'versions'
Write-Output '=== versions dir ==='
if (Test-Path -LiteralPath $v) {
    [System.IO.Directory]::GetDirectories($v) | ForEach-Object { Write-Output $_ }
} else {
    Write-Output 'no versions dir'
}
Write-Output '=== check inheritsFrom ==='
$j = Join-Path $v '1.0.0-rc2-2\1.0.0-rc2-2.json'
if (Test-Path -LiteralPath $j) {
    $c = [System.IO.File]::ReadAllText($j)
    if ($c -match 'inheritsFrom') { Write-Output '1.0.0-rc2-2: HAS inheritsFrom' } else { Write-Output '1.0.0-rc2-2: no inheritsFrom' }
}
$j2 = Join-Path $v '26.2-Fabric 0.19.3\26.2-Fabric 0.19.3.json'
if (Test-Path -LiteralPath $j2) {
    $c = [System.IO.File]::ReadAllText($j2)
    if ($c -match 'inheritsFrom') { Write-Output '26.2-Fabric: HAS inheritsFrom' } else { Write-Output '26.2-Fabric: no inheritsFrom' }
}

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$lines = Get-Content (Join-Path $root 'step3-fix.ps1')
$start = ($lines | Select-String '^function Repair-JsonEscapes').LineNumber
$end = $start
for ($i = $start; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^}') { $end = $i + 1; break } }
$fn = ($lines[($start-1)..($end-1)] -join "`r`n")
Invoke-Expression $fn

$bs = [string][char]92   # backslash

# --- A) 坏 JSON 修复 ---
$c = Get-Content (Join-Path $root 'target\gluonfx\x86_64-windows\gvm\resourceconfig-x86_64-windows.json') -Raw
$c = $c.TrimStart([char]0xFEFF)
$fixed = Repair-JsonEscapes $c
$j = $fixed | ConvertFrom-Json
$last = $j.resources[-1].pattern
Write-Host "A1 valid JSON: yes"
Write-Host ("A2 last pattern: " + $last)
Write-Host ("A3 idempotent: " + ($fixed -eq (Repair-JsonEscapes $fixed)))
# raw 文本里 pattern 必须是 反斜杠反斜杠+点 (JSON 合法转义, 解析后为 正则 \.)
Write-Host ("A4 raw has escaped-dot: " + $fixed.Contains($bs + $bs + '.(fxml'))
Write-Host ("A5 parsed value: " + $last.StartsWith('.*\.') + " / raw single-backslash remaining: " + ($fixed -match [regex]::Escape($bs + '(fxml')))

# --- B) 合法转义不误伤 ---
# 目标 JSON: { "a": "\\.(ok)$\n\t\u4e2d\"q\"\/x\\y" }  全部合法
$probe = '{ "a": "' + $bs + $bs + '.(ok)$' + $bs + 'n' + $bs + 't' + $bs + 'u4e2d' + $bs + '"q"' + $bs + '/x' + $bs + $bs + 'y" }'
$p1 = Repair-JsonEscapes $probe
Write-Host ("B1 valid escapes untouched: " + ($p1 -eq $probe))
if ($p1 -ne $probe) { Write-Host ("  in : " + $probe); Write-Host ("  out: " + $p1) }
$j2 = $p1 | ConvertFrom-Json
Write-Host ("B2 probe still valid JSON: " + ($j2.a -ne $null))

# --- C) 混合: 合法 + 非法混在一行 ---
$bad2 = '{ "p": "' + $bs + 'd' + $bs + $bs + '.png$' + $bs + 'n' + '" }'   # \d 非法, \\ 合法, \n 合法
$f3 = Repair-JsonEscapes $bad2
$expect = '{ "p": "' + $bs + $bs + 'd' + $bs + $bs + '.png$' + $bs + 'n' + '" }'
Write-Host ("C1 mixed fix correct: " + ($f3 -eq $expect))
if ($f3 -ne $expect) { Write-Host ("  expect: " + $expect); Write-Host ("  actual: " + $f3) }

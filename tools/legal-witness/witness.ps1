<#
.SYNOPSIS
    法律文档存证工具：为 LICENSE 与 docs/ 下的法律文本生成哈希清单、打包、校验。

.DESCRIPTION
    三种模式：
      manifest  生成 MANIFEST-<日期>.sha256 与 BUNDLE-<日期>.zip，并打印 zip 的 SHA-256
      verify    重新计算当前文件哈希，与最近的清单比对
      record    把存证结果（服务商、编号、时间、哈希）追加到 WITNESS-LOG.md

.EXAMPLE
    pwsh -File tools/legal-witness/witness.ps1 -Mode manifest

.EXAMPLE
    pwsh -File tools/legal-witness/witness.ps1 -Mode verify

.EXAMPLE
    pwsh -File tools/legal-witness/witness.ps1 -Mode record -Provider "联合信任TSA" -Reference "TSR-2026-xxxx" -BundleSha256 "AB12..."
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('manifest', 'verify', 'tsa', 'record')]
    [string]$Mode,

    # tsa 模式使用：要对哪个文件取时间戳（默认最近一次生成的清单）
    [string]$Target = '',

    # tsa 模式使用：TSA 服务地址；留空则只生成 .tsq 不发请求
    [string]$TsaUrl = 'https://freetsa.org/tsr',

    # record 模式使用
    [string]$Provider = '',
    [string]$Reference = '',
    [string]$ManifestSha256 = '',
    [string]$BundleSha256 = '',
    [string]$Note = ''
)

$ErrorActionPreference = 'Stop'

# 仓库根目录 = 本脚本所在目录的上两级
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$OutDir    = $ScriptDir

# 被存证的文件（相对仓库根目录）；只含法律文本，不含历史技术文档
$Targets = @(
    'LICENSE',
    'docs/README.md',
    'docs/LICENSE-EN.md',
    'docs/LICENSE-ZH.md',
    'docs/用户服务协议.md',
    'docs/免责声明.md',
    'docs/隐私政策.md',
    'docs/安全与合规.md',
    'docs/商标与品牌使用规范.md',
    'docs/第三方声明-Third-Party-Notices.md',
    'docs/第三方声明-Third-Party-Notices-中文.md'
)

function Get-TargetFiles {
    $files = @()
    foreach ($rel in $Targets) {
        $abs = Join-Path $RepoRoot ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $abs)) {
            Write-Warning "缺少文件，已跳过：$rel"
            continue
        }
        $files += [pscustomobject]@{
            Relative = $rel
            Absolute = (Resolve-Path -LiteralPath $abs).Path
        }
    }
    return $files
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ManifestPath([string]$Stamp) {
    return Join-Path $OutDir "MANIFEST-$Stamp.sha256"
}

function Get-LatestManifest {
    $latest = Get-ChildItem -LiteralPath $OutDir -Filter 'MANIFEST-*.sha256' -File -ErrorAction SilentlyContinue |
              Sort-Object Name |
              Select-Object -Last 1
    return $latest
}

function Invoke-Manifest {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $files = Get-TargetFiles

    $manifestLines = New-Object System.Collections.Generic.List[string]
    $manifestLines.Add("# Starlight Launcher — legal documents SHA-256 manifest")
    $manifestLines.Add("# generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    $manifestLines.Add("# host: $env:COMPUTERNAME")
    $manifestLines.Add("# format: <sha256>  <relative path>")

    foreach ($f in $files) {
        $hash = Get-Sha256 $f.Absolute
        $size = (Get-Item -LiteralPath $f.Absolute).Length
        $manifestLines.Add("$hash  $($f.Relative)")
        "{0}  {1,8} bytes  {2}" -f $hash, $size, $f.Relative | Write-Host
    }

    $manifestPath = Get-ManifestPath $stamp
    Set-Content -LiteralPath $manifestPath -Value $manifestLines -Encoding UTF8
    Write-Host ""
    Write-Host "清单已生成: $manifestPath"

    # 打包：zip 内保留 docs/ 层级
    $zipPath = Join-Path $OutDir "BUNDLE-$stamp.zip"
    if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

    $stage = Join-Path ([IO.Path]::GetTempPath()) ("legal-witness-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    try {
        foreach ($f in $files) {
            $dest = Join-Path $stage ($f.Relative -replace '/', [IO.Path]::DirectorySeparatorChar)
            $destDir = Split-Path -Parent $dest
            if (-not (Test-Path -LiteralPath $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            Copy-Item -LiteralPath $f.Absolute -Destination $dest
        }
        Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $stage "MANIFEST.sha256")
        Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
        [IO.Compression.ZipFile]::CreateFromDirectory($stage, $zipPath)
    }
    finally {
        Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
    }

    $zipHash = Get-Sha256 $zipPath
    $manifestHash = Get-Sha256 $manifestPath
    Write-Host "打包已生成: $zipPath"
    Write-Host ""
    Write-Host "==== 送交 TSA 时间戳的对象（请记录） ===="
    Write-Host "MANIFEST SHA-256 : $manifestHash"
    Write-Host "  建议用清单的哈希去盖时间戳：清单是纯文本、会入库，"
    Write-Host "  任何人事后都能重算并与 .tsr 中的 imprint 比对，证明力最稳。"
    Write-Host ""
    Write-Host "==== 送交公证处 / 司法区块链的物件 ===="
    Write-Host "BUNDLE SHA-256   : $zipHash"
    Write-Host "  压缩包不入库（见 .gitignore），仅在公证时作为实物提交并离线留存。"
    Write-Host ""
    Write-Host "下一步：拿到 .tsr 后运行"
    Write-Host "  pwsh -File tools/legal-witness/witness.ps1 -Mode record -Provider ""<服务商>"" -Reference ""<令牌编号>"" -ManifestSha256 ""$manifestHash"""
}

function Invoke-Verify {
    $latest = Get-LatestManifest
    if (-not $latest) {
        Write-Error "未找到 MANIFEST-*.sha256，请先运行 -Mode manifest"
        return
    }

    Write-Host "使用清单: $($latest.Name)"
    Write-Host ""

    $expected = @{}
    foreach ($line in Get-Content -LiteralPath $latest.FullName) {
        if ($line.StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split '\s{2,}', 2
        if ($parts.Count -ne 2) { continue }
        $expected[$parts[1].Trim()] = $parts[0].Trim()
    }

    $fail = 0
    $checked = 0
    foreach ($key in ($expected.Keys | Sort-Object)) {
        $abs = Join-Path $RepoRoot ($key -replace '/', [IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $abs)) {
            Write-Host ("MISSING   {0}" -f $key) -ForegroundColor Red
            $fail++
            continue
        }
        $actual = Get-Sha256 $abs
        $checked++
        if ($actual -eq $expected[$key]) {
            Write-Host ("OK        {0}" -f $key) -ForegroundColor Green
        }
        else {
            Write-Host ("MISMATCH  {0}" -f $key) -ForegroundColor Yellow
            Write-Host ("          expected {0}" -f $expected[$key])
            Write-Host ("          actual   {0}" -f $actual)
            $fail++
        }
    }

    Write-Host ""
    if ($fail -eq 0) {
        Write-Host "通过：$checked 个文件与清单完全一致。" -ForegroundColor Green
    }
    else {
        Write-Host "未通过：$fail 项异常（文件被修改、缺失，或清单来自更早的版本）。" -ForegroundColor Yellow
        Write-Host "若这是有意修订，请提交改动后重新运行 -Mode manifest，再取新的时间戳。"
        exit 1
    }
}

function Invoke-Tsa {
    $targetPath = $null
    if (-not [string]::IsNullOrWhiteSpace($Target)) {
        $targetPath = (Resolve-Path -LiteralPath $Target).Path
    }
    else {
        $latest = Get-LatestManifest
        if (-not $latest) {
            Write-Error "未找到 MANIFEST-*.sha256，请先运行 -Mode manifest，或用 -Target 指定文件"
            return
        }
        $targetPath = $latest.FullName
    }

    $bytes = [System.IO.File]::ReadAllBytes($targetPath)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash($bytes) } finally { $sha.Dispose() }

    $algId = [byte[]](0x30, 0x0D, 0x06, 0x09, 0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00)
    $octet = [byte[]](0x04, 0x20) + [byte[]]$digest
    $miContent = $algId + $octet
    $mi = [byte[]](0x30, [byte]$miContent.Length) + [byte[]]$miContent
    $body = [byte[]](0x02, 0x01, 0x01) + $mi + [byte[]](0x01, 0x01, 0xFF)
    $tsq = [byte[]](0x30, [byte]$body.Length) + [byte[]]$body

    $baseName = [IO.Path]::GetFileNameWithoutExtension($targetPath)
    $tsqPath = Join-Path $OutDir "$baseName.tsq"
    [System.IO.File]::WriteAllBytes($tsqPath, $tsq)

    $digestHex = (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
    Write-Host "目标文件 : $targetPath"
    Write-Host "SHA-256  : $digestHex  （含 BOM 的原始字节）"
    Write-Host "时间戳请求已生成: $tsqPath"

    if ([string]::IsNullOrWhiteSpace($TsaUrl)) {
        Write-Host ""
        Write-Host "未指定 -TsaUrl，仅生成本地请求文件。可交给服务商官网或 OpenSSL 处理。"
        return
    }

    $tsrPath = Join-Path $OutDir "$baseName.tsr"
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $resp = Invoke-WebRequest -Uri $TsaUrl -Method Post -InFile $tsqPath `
                -ContentType 'application/timestamp-query' -UseBasicParsing -TimeoutSec 60
        [System.IO.File]::WriteAllBytes($tsrPath, $resp.Content)
    }
    catch {
        Write-Host "TSA 请求失败：$($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host "可换一个 TSA 地址重试（如 https://freetsa.org/tsr），或用官网手动提交 $tsqPath。"
        return
    }

    $tsrBytes = [System.IO.File]::ReadAllBytes($tsrPath)
    Write-Host "时间戳令牌已保存: $tsrPath（$($tsrBytes.Length) 字节）"

    # 校验：令牌中的 imprint 必须与本文件摘要一致
    $imprintOk = $false
    for ($i = 0; $i -le $tsrBytes.Length - 32; $i++) {
        $same = $true
        for ($j = 0; $j -lt 32; $j++) {
            if ($tsrBytes[$i + $j] -ne $digest[$j]) { $same = $false; break }
        }
        if ($same) { $imprintOk = $true; break }
    }

    $ascii = [System.Text.Encoding]::ASCII.GetString($tsrBytes)
    $genTime = [regex]::Match($ascii, '\d{14}Z').Value

    if ($imprintOk) {
        Write-Host "校验通过：令牌内的 imprint 与本文件摘要一致。" -ForegroundColor Green
    }
    else {
        Write-Host "校验警告：令牌内未找到匹配的 imprint，请不要直接采信该令牌。" -ForegroundColor Yellow
    }
    if ($genTime) { Write-Host "时间戳时刻（UTC）: $genTime" }
}

function Invoke-Record {
    if ([string]::IsNullOrWhiteSpace($Provider) -or [string]::IsNullOrWhiteSpace($Reference)) {
        Write-Error "record 模式需要 -Provider 与 -Reference（服务商与令牌/公证书编号）"
        return
    }

    $latest = Get-LatestManifest
    $manifestName = if ($latest) { $latest.Name } else { '(未生成)' }

    if ([string]::IsNullOrWhiteSpace($ManifestSha256) -and $latest) {
        $ManifestSha256 = Get-Sha256 $latest.FullName
        Write-Host "未提供 -ManifestSha256，已从 $($latest.Name) 计算：$ManifestSha256"
    }
    elseif ([string]::IsNullOrWhiteSpace($ManifestSha256)) {
        $ManifestSha256 = '(未提供)'
    }

    if ([string]::IsNullOrWhiteSpace($BundleSha256)) {
        $zip = Get-ChildItem -LiteralPath $OutDir -Filter 'BUNDLE-*.zip' -File -ErrorAction SilentlyContinue |
               Sort-Object Name |
               Select-Object -Last 1
        if ($zip) {
            $BundleSha256 = Get-Sha256 $zip.FullName
            Write-Host "未提供 -BundleSha256，已从 $($zip.Name) 计算：$BundleSha256"
        }
        else {
            $BundleSha256 = '(未提供)'
        }
    }

    $commit = '(无 git)'
    try {
        $c = & git -C $RepoRoot rev-parse --short HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $c) { $commit = $c.Trim() }
    }
    catch { }

    $logPath = Join-Path $OutDir 'WITNESS-LOG.md'
    if (-not (Test-Path -LiteralPath $logPath)) {
        $header = @(
            '# 法律文档存证台账',
            '',
            '按时间顺序追加，不覆盖历史条目。',
            '',
            '| 序号 | 时间（本地） | 服务商 | 令牌/公证编号 | Manifest SHA-256 | Bundle SHA-256 | 清单 | Git 提交 | 备注 |',
            '|---|---|---|---|---|---|---|---|---|'
        )
        Set-Content -LiteralPath $logPath -Value $header -Encoding UTF8
    }

    $existing = Get-Content -LiteralPath $logPath
    $seq = ($existing | Where-Object { $_ -match '^\|\s*\d+\s*\|' }).Count + 1
    $row = "| $seq | $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') | $Provider | $Reference | ``$ManifestSha256`` | ``$BundleSha256`` | $manifestName | $commit | $Note |"
    Add-Content -LiteralPath $logPath -Value $row -Encoding UTF8

    Write-Host ""
    Write-Host "已登记第 $seq 条存证记录 -> $logPath"
    Write-Host $row
    Write-Host ""
    Write-Host "提醒：MANIFEST / .tsr / 本台账必须入库（git 可证时间），BUNDLE 仅在公证时离线提交。"
}

switch ($Mode) {
    'manifest' { Invoke-Manifest }
    'verify'   { Invoke-Verify }
    'tsa'      { Invoke-Tsa }
    'record'   { Invoke-Record }
}

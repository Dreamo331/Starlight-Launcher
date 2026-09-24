$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

# 加载 step3-fix.ps1 里的第 4 节逻辑: 通过提取整个脚本但替换掉 1/2/5 节来单测第 4 节
# 简化: 直接内联同样的追加代码, 对当前 (已含控制器条目的) 配置应 0 missing; 对手工剔除后的应补回
$gvm = Join-Path $root 'target\gluonfx\x86_64-windows\gvm'
$rf = Join-Path $gvm 'reflectionconfig-x86_64-windows.json'

$requiredReflection = @(
    'com.example.starlight.launch.LaunchRecord',
    'com.example.starlight.community.CommunityApi$CommunityUser',
    'com.example.starlight.community.CommunityApi$CommunityPost',
    'com.example.starlight.community.CommunityApi$CommunityComment',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthProject',
    'com.example.starlight.ModsApi.ModrinthAPI$GameVersion',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthVersion',
    'com.example.starlight.ModsApi.ModrinthAPI$ModrinthFile',
    'com.example.starlight.version.CacheManager$CacheEntry',
    'com.example.starlight.JavaFXLauncher',
    'com.example.starlight.newui.StarlightSplashController'
)

function Test-Append {
    param([string]$rfPath)
    $r = Get-Content $rfPath -Raw
    $r = $r.TrimStart([char]0xFEFF)
    $rObj = $r | ConvertFrom-Json
    $have = @($rObj | ForEach-Object { $_.name })
    $missing = @($requiredReflection | Where-Object { $have -notcontains $_ })
    if ($missing.Count -gt 0) {
        foreach ($m in $missing) {
            $entry = [ordered]@{
                name = $m
                allDeclaredConstructors = $true
                allPublicConstructors = $true
                allDeclaredFields = $true
                allPublicFields = $true
                allDeclaredMethods = $true
                allPublicMethods = $true
            }
            $entryJson = (ConvertTo-Json @($entry) -Depth 4)
            $entryJson = $entryJson.Trim()
            if ($entryJson.StartsWith('[')) { $entryJson = $entryJson.Substring(1) }
            if ($entryJson.EndsWith(']')) { $entryJson = $entryJson.Substring(0, $entryJson.Length - 1) }
            $entryJson = $entryJson.Trim()
            $r = $r.TrimEnd()
            if ($r.EndsWith(']')) { $r = $r.Substring(0, $r.Length - 1).TrimEnd() }
            $r = $r + ",`n" + $entryJson + "`n]"
            $have += $m
        }
        $null = $r | ConvertFrom-Json
        [System.IO.File]::WriteAllText($rfPath, $r, (New-Object System.Text.UTF8Encoding($false)))
    }
    return $missing.Count
}

# 用副本测试, 不动真身
$copy = Join-Path $env:TEMP 'rf-test-copy.json'
Copy-Item $rf $copy -Force

# 场景1: 完整配置 -> 应 0 missing
$n1 = Test-Append $copy
Write-Host "case1 (complete config) missing: $n1"

# 场景2: 剔除 2 个条目 -> 应补回 2, 且仍是合法 JSON、全量在册
$j = Get-Content $copy -Raw | ConvertFrom-Json
$filtered = @($j | Where-Object { $_.name -notin @('com.example.starlight.newui.StarlightSplashController','com.example.starlight.JavaFXLauncher') })
$json = ($filtered | ConvertTo-Json -Depth 6)
[System.IO.File]::WriteAllText($copy, $json, (New-Object System.Text.UTF8Encoding($false)))
$n2 = Test-Append $copy
$j2 = Get-Content $copy -Raw | ConvertFrom-Json
$names = @($j2 | ForEach-Object { $_.name })
$allPresent = ($requiredReflection | Where-Object { $names -notcontains $_ }).Count -eq 0
Write-Host "case2 (after removal) appended: $n2, still valid: yes, all required present: $allPresent"

Remove-Item $copy -Force

$path = 'C:\Users\Administrator\.qoder-cn\cache\projects\2026.6.21-a8167368\conversation-history\ee00afa9\ee00afa9.jsonl'
$lines = Get-Content $path -Encoding UTF8
$patterns = 'NPE|缓存|修复|判空|avatarCache|根因'
foreach ($line in $lines) {
    if ($line -match 'assistant') { continue }
    $plain = $line -replace '\\u003c', '<' -replace '\\u003e', '>'
    if ($plain -match $patterns) {
        try {
            $j = $line | ConvertFrom-Json
            if ($j.message.content -is [array]) {
                foreach ($c in $j.message.content) {
                    if ($c.type -eq 'text') {
                        $t = $c.text
                        if ($t.Length -gt 800) { $t = $t.Substring(0, 800) }
                        Write-Output ('===== ' + $j.role + ' =====')
                        Write-Output $t
                    }
                }
            }
        } catch { }
    }
}

$path = 'C:\Users\Administrator\.qoder-cn\cache\projects\2026.6.21-a8167368\conversation-history\ee00afa9\ee00afa9.jsonl'
$lines = Get-Content $path -Encoding UTF8
$total = $lines.Count
Write-Output ("TOTAL LINES: " + $total)
$start = $total - 20
for ($i = $start; $i -lt $total; $i++) {
    $line = $lines[$i]
    try {
        $j = $line | ConvertFrom-Json
        $role = $j.role
        $texts = @()
        if ($j.message.content -is [array]) {
            foreach ($c in $j.message.content) {
                if ($c.type -eq 'text') { $texts += $c.text }
            }
        } else {
            $texts += $j.message.content
        }
        Write-Output ("----- " + $role + " -----")
        foreach ($t in $texts) {
            if ($t.Length -gt 600) { $t = $t.Substring(0, 600) }
            Write-Output $t
        }
    } catch {
        Write-Output ("[parse error] " + $line.Substring(0, [Math]::Min(200, $line.Length)))
    }
}

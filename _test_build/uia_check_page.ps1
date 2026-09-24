param([IntPtr]$Handle)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
if ($root -eq $null) { Write-Output "NO_ROOT"; exit 1 }

function C([int[]]$codes) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($c in $codes) { [void]$sb.Append([char]$c) }
    return $sb.ToString()
}

$names = @(
    @((C @(0x7A97, 0x53E3, 0x5927, 0x5C0F)), "window-size"),       # 窗口大小
    @((C @(0x5168, 0x5C4F, 0x6A21, 0x5F0F)), "fullscreen"),         # 全屏模式
    @((C @(0x6E38, 0x620F, 0x53C2, 0x6570)), "game-args"),          # 游戏参数
    @((C @(0x7248, 0x672C, 0x9694, 0x79BB)), "version-isolation"),  # 版本隔离
    @((C @(0x6D4B, 0x8BD5)), "test"),                               # 测试
    @((C @(0x52A0, 0x8F7D, 0x4E2D, 0x5FC3)), "download-center"),    # 下载中心
    @((C @(0x5173, 0x4E8E)), "about")                               # 关于
)

foreach ($n in $names) {
    $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $n[0])
    try {
        $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
        if ($el) {
            $r = $el.Current.BoundingRectangle
            Write-Output "$($n[1]): FOUND at $($r.X),$($r.Y) $($r.Width)x$($r.Height)"
        } else {
            Write-Output "$($n[1]): NOT_FOUND"
        }
    } catch {
        Write-Output "$($n[1]): ERROR"
    }
}

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
    @((C @(0x8BBE, 0x7F6E)), "tab-setting"),
    @((C @(0x9AD8, 0x7EA7, 0x8BBE, 0x7F6E)), "advanced"),
    @((C @(0x4E3B, 0x9898, 0x4E0E, 0x80CC, 0x666F)), "theme"),
    @((C @(0x9996, 0x9875)), "tab-home")
)

foreach ($n in $names) {
    $cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $n[0])
    try {
        $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
        if ($el) {
            $r = $el.Current.BoundingRectangle
            Write-Output "$($n[1]): $($r.X),$($r.Y) $($r.Width)x$($r.Height)"
        } else {
            Write-Output "$($n[1]): NOT_FOUND"
        }
    } catch {
        Write-Output "$($n[1]): ERROR $($_.Exception.Message)"
    }
}

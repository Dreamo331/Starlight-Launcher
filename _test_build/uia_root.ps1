param([IntPtr]$Handle)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle($Handle)
if ($root -eq $null) { Write-Output "NO_ROOT"; exit 1 }
$r = $root.Current.BoundingRectangle
Write-Output "ROOT: $($r.X),$($r.Y) $($r.Width)x$($r.Height)"

$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, "Starlight Launcher")
$el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
if ($el) {
    $r2 = $el.Current.BoundingRectangle
    Write-Output "TITLEBAR: $($r2.X),$($r2.Y) $($r2.Width)x$($r2.Height)"
}

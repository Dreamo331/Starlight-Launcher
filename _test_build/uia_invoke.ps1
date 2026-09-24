param([string]$Handle, [string]$BtnName)

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$hwnd = [IntPtr][int64]$Handle
$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
if ($root -eq $null) { Write-Output "NO_ROOT"; exit 1 }

$cond = New-Object System.Windows.Automation.PropertyCondition([System.Windows.Automation.AutomationElement]::NameProperty, $BtnName)
$el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
if ($el -eq $null) { Write-Output "NOT_FOUND: $BtnName"; exit 1 }

$invoke = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
$invoke.Invoke()
Start-Sleep -Milliseconds 600
Write-Output "INVOKED: $BtnName"

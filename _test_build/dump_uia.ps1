param([string]$Handle, [string]$OutFile = "uia_dump.txt")

$hwnd = [IntPtr][int64]$Handle
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
if ($root -eq $null) { Write-Output "NO_ROOT"; exit 1 }

$sb = New-Object System.Text.StringBuilder
$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
$sb.AppendLine("COUNT=$($all.Count)") | Out-Null
for ($i = 0; $i -lt $all.Count; $i++) {
    $el = $all.Item($i)
    $r = $el.Current.BoundingRectangle
    $name = $el.Current.Name
    $ct = $el.Current.ControlType.ProgrammaticName
    if ($r.Width -gt 0 -and $r.Height -gt 0 -and ($name -ne "")) {
        $sb.AppendLine("[$i] $ct name='$name' @ $([int]$r.X),$([int]$r.Y) $([int]$r.Width)x$([int]$r.Height)") | Out-Null
    }
}
$sb.ToString() | Out-File -FilePath (Join-Path $PSScriptRoot $OutFile) -Encoding UTF8
Write-Output "DUMPED $($all.Count)"

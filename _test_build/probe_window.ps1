param([IntPtr]$Handle, [int]$X = 0, [int]$Y = 0)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Probe {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$r = New-Object Win32Probe+RECT
[Win32Probe]::GetWindowRect($Handle, [ref]$r) | Out-Null
$w = $r.Right - $r.Left
$hh = $r.Bottom - $r.Top
Write-Output "SIZE $w x $hh at ($($r.Left),$($r.Top))"

if ($X -ne 0 -or $Y -ne 0) {
    [Win32Probe]::SetForegroundWindow($Handle) | Out-Null
    Start-Sleep -Milliseconds 300
    [Win32Probe]::SetCursorPos($X, $Y) | Out-Null
    Start-Sleep -Milliseconds 150
    [Win32Probe]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [Win32Probe]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 150
    Write-Output "CLICKED ($X,$Y)"
}

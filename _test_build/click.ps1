param([string]$Handle, [int]$X = 0, [int]$Y = 0)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class WinClick {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
}
"@

$hwnd = [IntPtr][int64]$Handle
[WinClick]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 400
[WinClick]::SetCursorPos($X, $Y) | Out-Null
Start-Sleep -Milliseconds 150
[WinClick]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
[WinClick]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 200
Write-Output "CLICKED ($X,$Y)"

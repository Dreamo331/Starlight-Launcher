param([IntPtr]$Handle, [double]$X, [double]$Y, [int]$WaitMs = 1200)

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Click {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

[Win32Click]::SetForegroundWindow($Handle) | Out-Null
Start-Sleep -Milliseconds 300
$px = [int]($X / 1.5)
$py = [int]($Y / 1.5)
[Win32Click]::SetCursorPos($px, $py) | Out-Null
Start-Sleep -Milliseconds 150
[Win32Click]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
[Win32Click]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds $WaitMs

$r = New-Object Win32Click+RECT
[Win32Click]::GetWindowRect($Handle, [ref]$r) | Out-Null
Write-Output "CLICKED ($px,$py) -> SIZE $($r.Right - $r.Left) x $($r.Bottom - $r.Top)"

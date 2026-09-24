Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32List {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

Get-Process | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object {
    $r = New-Object Win32List+RECT
    [Win32List]::GetWindowRect($_.MainWindowHandle, [ref]$r) | Out-Null
    $line = "$($_.Id) | $($_.ProcessName) | $($_.MainWindowTitle) | rect=$($r.Left),$($r.Top)->$($r.Right),$($r.Bottom)"
    Write-Output $line
}

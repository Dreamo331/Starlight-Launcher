Add-Type @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WinEnum2 {
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
}
"@

$found = @()
$cb = [WinEnum2+EnumWindowsProc]{
    param($h, $l)
    $sb = New-Object System.Text.StringBuilder 256
    [WinEnum2]::GetWindowText($h, $sb, 256) | Out-Null
    $title = $sb.ToString()
    if ($title -like '*Starlight*' -or $title -like '*星光*') {
        $pid2 = 0
        [WinEnum2]::GetWindowThreadProcessId($h, [ref]$pid2) | Out-Null
        $vis = [WinEnum2]::IsWindowVisible($h)
        $script:found += "HWND=$($h.ToInt64()) PID=$pid2 VISIBLE=$vis TITLE='$title'"
    }
    return $true
}
[WinEnum2]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
$found

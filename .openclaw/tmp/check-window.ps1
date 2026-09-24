# 检查 Starlight Launcher 窗口尺寸（主界面 >= 920x620，闪屏 660x400）
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class Win32Enum {
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")]
    public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder text, int count);
    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
'@

$found = @()
$proc = Get-Process -Name 'Starlight Launcher' -ErrorAction SilentlyContinue
if (-not $proc) { Write-Output 'PROCESS_NOT_FOUND'; exit }

$cb = {
    param($h, $l)
    if ([Win32Enum]::IsWindowVisible($h)) {
        $sb = New-Object System.Text.StringBuilder 256
        [Win32Enum]::GetWindowText($h, $sb, 256) | Out-Null
        $t = $sb.ToString()
        if ($t -like '*Starlight*') {
            $r = New-Object Win32Enum+RECT
            [Win32Enum]::GetWindowRect($h, [ref]$r) | Out-Null
            $script:found += "title=[$t] size=$($r.Right-$r.Left)x$($r.Bottom-$r.Top) pid=$($proc.Id)"
        }
    }
    return $true
}
[Win32Enum]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
if ($found.Count -eq 0) { Write-Output 'NO_WINDOW' } else { $found | ForEach-Object { Write-Output $_ } }
Write-Output ("process alive: " + (-not $proc.HasExited))

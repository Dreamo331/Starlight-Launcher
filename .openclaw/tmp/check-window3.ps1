Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WEnum {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int c);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
'@

$found = @()
$cb = {
    param($h, $l)
    if ([WEnum]::IsWindowVisible($h)) {
        $sb = New-Object System.Text.StringBuilder 256
        [WEnum]::GetWindowText($h, $sb, 256) | Out-Null
        $t = $sb.ToString()
        if ($t -like '*Starlight*') {
            $rect = New-Object WEnum+RECT
            [WEnum]::GetWindowRect($h, [ref]$rect) | Out-Null
            $script:found += "title=[$t] size=$(($rect.R - $rect.L))x$(($rect.B - $rect.T))"
        }
    }
    return $true
}
[WEnum]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null

$proc = Get-Process -Name 'Starlight Launcher' -ErrorAction SilentlyContinue
Write-Output ("process alive: " + [bool]$proc)
if ($found.Count -eq 0) { Write-Output 'NO_STARLIGHT_WINDOW' } else { $found | ForEach-Object { Write-Output $_ } }

# 列出所有进程名带 Star/Launcher 的，以及可见窗口标题
$names = Get-Process | Where-Object { $_.Name -like '*Starlight*' -or $_.Name -like '*Launcher*' } | Select-Object Id, Name, MainWindowTitle
if ($names) { $names | Format-Table -AutoSize | Out-String | Write-Output } else { Write-Output 'NO_MATCHING_PROCESS' }

# 顺便列出当前所有可见的顶层窗口标题（前 40 个）
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WinEnum {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int c);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
}
'@
$list = New-Object System.Collections.ArrayList
$cb = {
    param($h, $l)
    if ([WinEnum]::IsWindowVisible($h)) {
        $sb = New-Object System.Text.StringBuilder 256
        [WinEnum]::GetWindowText($h, $sb, 256) | Out-Null
        $t = $sb.ToString()
        if ($t.Length -gt 0) { [void]$script:list.Add($t) }
    }
    return $true
}
[WinEnum]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
Write-Output '--- visible window titles ---'
$list | Select-Object -First 40 | ForEach-Object { Write-Output $_ }

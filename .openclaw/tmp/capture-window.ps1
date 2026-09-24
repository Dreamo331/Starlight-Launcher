Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class Cap {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
'@

# 找 Starlight Launcher 窗口
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public class WFind {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int c);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
}
'@
$target = [IntPtr]::Zero
$cb = {
    param($h, $l)
    if ([WFind]::IsWindowVisible($h)) {
        $sb = New-Object System.Text.StringBuilder 256
        [WFind]::GetWindowText($h, $sb, 256) | Out-Null
        if ($sb.ToString() -eq 'Starlight Launcher') { $script:target = $h; return $false }
    }
    return $true
}
[WFind]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null

if ($target -eq [IntPtr]::Zero) { Write-Output 'WINDOW_NOT_FOUND'; exit 1 }

[Cap]::SetForegroundWindow($target) | Out-Null
Start-Sleep -Milliseconds 800

$r = New-Object Cap+RECT
[Cap]::GetWindowRect($target, [ref]$r) | Out-Null
$w = $r.R - $r.L; $h2 = $r.B - $r.T
Write-Output ("window rect: $($r.L),$($r.T) ${w}x${h2}")

$bmp = New-Object System.Drawing.Bitmap $w, $h2
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.L, $r.T, 0, 0, (New-Object System.Drawing.Size $w, $h2))
$out = 'D:\Starlight Launcher启动器工程-Java\UI\2026.6.21\.openclaw\tmp\launcher-main-page.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output "SAVED: $out"

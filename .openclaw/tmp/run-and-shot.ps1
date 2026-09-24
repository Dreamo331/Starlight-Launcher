$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32 {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    public struct RECT { public int L, T, R, B; }
}
"@

$tmpDir = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $tmpDir)
$exe = Join-Path $root 'target\gluonfx\x86_64-windows\Starlight Launcher.exe'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe
$psi.WorkingDirectory = Split-Path $exe
$psi.RedirectStandardError = $true
$psi.RedirectStandardOutput = $true
$psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)

function Snapshot($phase) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(0, 0, 0, 0, $bmp.Size)
    $path = Join-Path $tmpDir ("shot-" + $phase + ".png")
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host "saved: $path"
}

function WinInfo($phase) {
    $p.Refresh()
    $h = $p.MainWindowHandle
    if ($h -eq [IntPtr]::Zero) { Write-Host "$phase : no main window handle"; return }
    $r = New-Object Win32+RECT
    [Win32]::GetWindowRect($h, [ref]$r) | Out-Null
    $vis = [Win32]::IsWindowVisible($h)
    Write-Host "$phase : hwnd=$h visible=$vis rect=($($r.L),$($r.T))-($($r.R),$($r.B)) size=$($r.R-$r.L)x$($r.B-$r.T)"
}

Start-Sleep -Seconds 5
WinInfo "t5"
Snapshot "splash5"
Start-Sleep -Seconds 5
WinInfo "t10"
Snapshot "splash10"
Start-Sleep -Seconds 15
WinInfo "t25"
Snapshot "t25"

$p.Refresh()
if (-not $p.HasExited) { Write-Host "process alive, killing"; $p.Kill() } else { Write-Host "process exited code=$($p.ExitCode)" }
Start-Sleep -Milliseconds 800
$err = $p.StandardError.ReadToEnd()
Write-Host "--- stderr bytes: $($err.Length) ---"
$err -split "`n" | Select-Object -Last 40

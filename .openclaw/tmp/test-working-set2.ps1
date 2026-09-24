# 测试 Native Image 下内存优化的 PowerShell 备用方案（Add-Type + P/Invoke EmptyWorkingSet）
$ErrorActionPreference = 'Continue'

$src = @'
using System;
using System.Runtime.InteropServices;
public static class StarMemOpt {
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern IntPtr OpenProcess(int access, bool inherit, int pid);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern bool CloseHandle(IntPtr h);
    [DllImport("psapi.dll", SetLastError=true)]
    public static extern bool EmptyWorkingSet(IntPtr h);
}
'@
try {
    if (-not ([System.Management.Automation.PSTypeName]'StarMemOpt').Type) {
        Add-Type -TypeDefinition $src -Language CSharp -ErrorAction Stop
    }
    Write-Output "Add-Type: OK"
} catch {
    Write-Output ("Add-Type FAILED: " + $_.Exception.Message)
    exit 1
}

# 对一个真实子进程执行 OpenProcess + EmptyWorkingSet 验证语义
$child = Start-Process -FilePath "cmd.exe" -ArgumentList "/c ping -n 20 127.0.0.1 > nul" -WindowStyle Hidden -PassThru
Start-Sleep -Milliseconds 800
$h = [StarMemOpt]::OpenProcess(0x500, $false, $child.Id)  # PROCESS_SET_QUOTA(0x100) | PROCESS_QUERY_INFORMATION(0x400)
Write-Output ("open ok: " + ($h -ne [IntPtr]::Zero))
if ($h -ne [IntPtr]::Zero) {
    $r = [StarMemOpt]::EmptyWorkingSet($h)
    Write-Output ("EmptyWorkingSet: " + $r)
    [StarMemOpt]::CloseHandle($h) | Out-Null
}
Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue
Write-Output "DONE"

# 测试：Native Image 下内存优化功能的 PowerShell 备用方案是否可用
$ErrorActionPreference = 'Continue'

# 1) 检查目标机器上有没有相关 cmdlet（预期没有，验证不用）
$cmd = Get-Command Clear-RecursiveWorkingSet -ErrorAction SilentlyContinue
Write-Output ("Clear-RecursiveWorkingSet exists: " + [bool]$cmd)

# 2) Add-Type + P/Invoke 方式（预期方案）：先验证能编译
$m = '[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)] public static extern System.IntPtr OpenProcess(int a, bool b, int p); [System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError=true)] public static extern bool CloseHandle(System.IntPtr h); [System.Runtime.InteropServices.DllImport("psapi.dll", SetLastError=true)] public static extern bool EmptyWorkingSet(System.IntPtr h);'
try {
    Add-Type -Namespace StarL -Name N -MemberDefinition $m -ErrorAction Stop
    Write-Output "Add-Type: OK"
} catch {
    Write-Output ("Add-Type FAILED: " + $_)
    exit 1
}

# 3) 对一个真实子进程执行 OpenProcess + EmptyWorkingSet（验证语义）
$child = Start-Process -FilePath "cmd.exe" -ArgumentList "/c ping -n 20 127.0.0.1 > nul" -WindowStyle Hidden -PassThru
Start-Sleep -Milliseconds 700
$h = [StarL.N]::OpenProcess(0x500, $false, $child.Id)
Write-Output ("handle is zero: " + ($h -eq [IntPtr]::Zero))
if ($h -ne [IntPtr]::Zero) {
    $r = [StarL.N]::EmptyWorkingSet($h)
    Write-Output ("EmptyWorkingSet on child: " + $r)
    [StarL.N]::CloseHandle($h) | Out-Null
}
Stop-Process -Id $child.Id -Force -ErrorAction SilentlyContinue
Write-Output "DONE"

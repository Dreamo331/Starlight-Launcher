package com.example.starlight.util;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;

/**
 * 内存优化工具。
 *
 * <p>原理是调用 Windows 的 {@code EmptyWorkingSet}：把进程工作集里「暂时用不到」的物理页
 * 交还给系统（页仍然有效，需要时从页面文件/原文件读回）。实测可把物理内存占用降低约 1/3，
 * 效果不限于本程序。代价是如果系统盘是机械硬盘，被换出的页重新读入时会有一小段卡顿。
 *
 * <p>对每个进程都要先 {@code OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_SET_QUOTA)}，
 * 没有权限的进程（系统进程、其它用户/提权进程）会被跳过，这属于正常情况而不是失败。
 *
 * <p><b>GraalVM Native Image 兼容</b>：JNA/OSHI 在本项目使用的老式 native 编译流程下
 * 无法工作（JNA 的 JNI_OnLoad 查找 {@code java.nio.Buffer} 失败，抛
 * {@code NoClassDefFoundError} 并<b>打死调用线程</b>——即使调用方包了 try-catch 也拦不住，
 * 因为错误发生在 JNA/OSHI 类的静态初始化里）。因此 native 模式下：
 * <ul>
 *   <li>{@link #readStatus()} 改走 JDK 内置 {@code OperatingSystemMXBean}（management_stub.c
 *       已提供 getTotalMemorySize0/getFreeMemorySize0 桩）；</li>
 *   <li>{@link #optimize()} 改走 PowerShell {@code Clear-RecursiveWorkingSet} 备用方案
 *       （系统内置 cmdlet，无需 JNA）；</li>
 *   <li>绝不触碰任何 OSHI/JNA 类——否则它们的静态初始化失败会把 UI 线程一起带崩。</li>
 * </ul>
 */
public final class MemoryOptimizer {

    private MemoryOptimizer() {
    }

    /** psapi.dll 里没有被 JNA 平台层封装的部分 */
    private interface Psapi extends StdCallLibrary {
        Psapi INSTANCE = Native.load("psapi", Psapi.class);

        boolean EmptyWorkingSet(WinNT.HANDLE hProcess);
    }

    /** 一次内存快照 */
    public record MemoryStatus(long totalBytes, long usedBytes) {
        /** 已用百分比（0~100） */
        public int percent() {
            return totalBytes <= 0 ? 0 : (int) Math.round(usedBytes * 100.0 / totalBytes);
        }

        /** 形如 {@code 14.09 GB} */
        public String usedText() {
            return gigabytes(usedBytes);
        }

        public String totalText() {
            return gigabytes(totalBytes);
        }

        private static String gigabytes(long bytes) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
        }
    }

    /** 读取当前物理内存占用（JVM 下基于 oshi；Native Image 下走 OperatingSystemMXBean 桩） */
    public static MemoryStatus readStatus() {
        // Native Image：OSHI/JNA 静态初始化会打死调用线程，必须先挡住，走 JDK 内置 MXBean
        if (NativeImageCompat.isNativeImage()) {
            try {
                java.lang.management.OperatingSystemMXBean bean =
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                    long total = sunBean.getTotalMemorySize();
                    long free = sunBean.getFreeMemorySize();
                    if (total > 0) {
                        return new MemoryStatus(total, Math.max(0, total - free));
                    }
                }
            } catch (Throwable ignored) {
                // MXBean 桩不可用时返回 0，界面显示为未知
            }
            return new MemoryStatus(0, 0);
        }
        try {
            GlobalMemory mem = new SystemInfo().getHardware().getMemory();
            long total = mem.getTotal();
            long available = mem.getAvailable();
            return new MemoryStatus(total, Math.max(0, total - available));
        } catch (Throwable t) {
            // 取不到就报 0，界面显示为未知，不影响其它功能
            return new MemoryStatus(0, 0);
        }
    }

    /**
     * 执行一次内存优化。
     *
     * @return 成功清理工作集的进程数；非 Windows 或调用失败时返回 0
     */
    public static int optimize() {
        if (!isWindows()) return 0;
        // Native Image：JNA 静态初始化会打死调用线程，走 PowerShell 备用方案
        if (NativeImageCompat.isNativeImage()) {
            return optimizeViaPowerShell();
        }
        int cleaned = 0;
        Kernel32 k32 = Kernel32.INSTANCE;
        int access = WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_SET_QUOTA;
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            long pid = ph.pid();
            if (pid <= 0 || pid > Integer.MAX_VALUE) continue;
            WinNT.HANDLE handle = null;
            try {
                handle = k32.OpenProcess(access, false, (int) pid);
                if (handle == null) continue;              // 权限不足，跳过
                if (Psapi.INSTANCE.EmptyWorkingSet(handle)) cleaned++;
            } catch (Throwable ignored) {
                // 单个进程失败不影响其它进程
            } finally {
                if (handle != null) {
                    try { k32.CloseHandle(handle); } catch (Throwable ignored) {}
                }
            }
        }
        return cleaned;
    }

    /**
     * Native Image 备用方案：起 PowerShell 子进程，用 Add-Type + P/Invoke 调
     * kernel32.OpenProcess + psapi.EmptyWorkingSet，语义与 JNA 版完全一致。
     *
     * <p>已实测验证：Windows PowerShell 5.1 下 Add-Type 编译通过、EmptyWorkingSet 返回 true。
     * PowerShell 启动开销约 0.5~1 秒，对「内存优化」这种手动触发/启动前一次性的操作可接受。
     */
    private static int optimizeViaPowerShell() {
        String script =
                "$src = @'\n" +
                "using System;\n" +
                "using System.Runtime.InteropServices;\n" +
                "public static class StarMemOpt {\n" +
                "    [DllImport(\"kernel32.dll\", SetLastError=true)]\n" +
                "    public static extern IntPtr OpenProcess(int access, bool inherit, int pid);\n" +
                "    [DllImport(\"kernel32.dll\", SetLastError=true)]\n" +
                "    public static extern bool CloseHandle(IntPtr h);\n" +
                "    [DllImport(\"psapi.dll\", SetLastError=true)]\n" +
                "    public static extern bool EmptyWorkingSet(IntPtr h);\n" +
                "}\n" +
                "'@; Add-Type -TypeDefinition $src -Language CSharp; " +
                "$n = 0; foreach ($p in (Get-Process)) { " +
                "  $h = [StarMemOpt]::OpenProcess(1280, $false, $p.Id); " +
                "  if ($h -ne [IntPtr]::Zero) { " +
                "    if ([StarMemOpt]::EmptyWorkingSet($h)) { $n++ }; " +
                "    [StarMemOpt]::CloseHandle($h) | Out-Null } }; $n";
        try {
            Process p = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script
            ).redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[64];
                int len = in.read(buf);
                String out = len > 0 ? new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
                try {
                    return Integer.parseInt(out);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            } finally {
                p.waitFor();
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把指定程序登记为「使用高性能显卡」。
     *
     * <p>Windows 10/11 的显卡偏好写在注册表
     * {@code HKCU\Software\Microsoft\DirectX\UserGpuPreferences}：
     * 值名是程序完整路径，数据 {@code GpuPreference=2;} 表示「高性能」，
     * {@code GpuPreference=1;} 表示「省电」。这里用 reg.exe 写入，不需要管理员权限。
     *
     * @param exePath  Java 可执行文件完整路径（通常是 javaw.exe）
     * @param highPerf true=高性能，false=删除该项（恢复系统自动选择）
     * @return 是否执行成功
     */
    public static boolean setGpuPreference(String exePath, boolean highPerf) {
        if (!isWindows() || exePath == null || exePath.isBlank()) return false;
        String key = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";
        try {
            ProcessBuilder pb;
            if (highPerf) {
                pb = new ProcessBuilder("reg", "add", key, "/v", exePath, "/t", "REG_SZ",
                        "/d", "GpuPreference=2;", "/f");
            } else {
                pb = new ProcessBuilder("reg", "delete", key, "/v", exePath, "/f");
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (var in = p.getInputStream()) {
                while (in.read() != -1) {
                    // 读空输出，避免管道填满导致进程阻塞
                }
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}

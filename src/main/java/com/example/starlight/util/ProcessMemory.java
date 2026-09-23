package com.example.starlight.util;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;

import java.util.List;

/**
 * 单个进程的内存占用读取（Windows psapi 的 {@code GetProcessMemoryInfo}）。
 *
 * <p>与 {@link MemoryOptimizer} 一样走 JNA：OpenProcess 拿句柄 → 读 PROCESS_MEMORY_COUNTERS。
 * 之前试过 OSHI 的 {@code OSProcess.getResidentSetSize()}，实测在这台机器上读出来的值不可信
 * （同一个进程时而 244 KB、时而 1.1 MB、时而取不到，而 tasklist 显示 5.9 MB），所以改成直接调
 * Windows API：进程内调用、微秒级，不额外起子进程。
 *
 * <p>单独放一个类是为了给非 Windows / GraalVM native 构建留降级路径：JNA 不可用时引用本类会抛错，
 * 调用方用 {@code catch (Throwable)} 接住、界面显示未知（「—」）即可。
 *
 * <p><b>GraalVM Native Image 兼容</b>：JNA 静态初始化失败抛 {@code NoClassDefFoundError}
 * 并打死调用线程（即使包了 try-catch 也拦不住，见 {@link NativeImageCompat}）。
 * 本类所有 JNA 引用都收在 {@link #workingSetBytes(long)} 的 native 分支判断之后，
 * native 模式下直接返回 -1，绝不触碰 JNA 类。
 */
public final class ProcessMemory {

    private ProcessMemory() {
    }

    /**
     * 进程工作集（常驻内存）字节数。
     *
     * @return 字节数；进程已退出、无权限或环境不支持（非 Windows / native 构建）时返回 -1，界面显示「—」
     */
    public static long workingSetBytes(long pid) {
        if (pid <= 0) return -1L;
        // Native Image：JNA 静态初始化会打死调用线程，直接降级
        if (NativeImageCompat.isNativeImage()) return -1L;
        WinNT.HANDLE handle = null;
        try {
            handle = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false, (int) pid);
            if (handle == null) {
                // 受限权限的进程打不开 QUERY_INFORMATION，退回 Vista+ 的受限查询
                handle = Kernel32.INSTANCE.OpenProcess(
                        WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, (int) pid);
            }
            if (handle == null) return -1L;

            PROCESS_MEMORY_COUNTERS counters = new PROCESS_MEMORY_COUNTERS();
            if (!PsapiExt.INSTANCE.GetProcessMemoryInfo(handle, counters, counters.size())) return -1L;
            counters.read();
            long bytes = counters.WorkingSetSize.longValue();
            return bytes > 0 ? bytes : -1L;
        } catch (Throwable t) {
            return -1L;
        } finally {
            if (handle != null) {
                Kernel32.INSTANCE.CloseHandle(handle);
            }
        }
    }

    // ==================== 以下为 JNA 实现（仅 JVM 模式可达） ====================
    // 注意：这些类成员只在 workingSetBytes 的非 native 分支中被触碰，native 模式下
    // 类初始化链不会走到这里（方法体未执行就不会触发接口/嵌套类的初始化）。

    /** psapi 里没有被 JNA 平台层封装的 GetProcessMemoryInfo */
    private interface PsapiExt extends StdCallLibrary {
        PsapiExt INSTANCE = Native.load("psapi", PsapiExt.class);

        boolean GetProcessMemoryInfo(WinNT.HANDLE hProcess, PROCESS_MEMORY_COUNTERS counters, int cb);
    }

    /** Windows 的 PROCESS_MEMORY_COUNTERS（字段顺序必须与头文件一致，JNA 按顺序映射） */
    public static class PROCESS_MEMORY_COUNTERS extends Structure {
        public int cb;
        public int PageFaultCount;
        public BaseTSD.SIZE_T PeakWorkingSetSize;
        public BaseTSD.SIZE_T WorkingSetSize;
        public BaseTSD.SIZE_T QuotaPeakPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaPeakNonPagedPoolUsage;
        public BaseTSD.SIZE_T QuotaNonPagedPoolUsage;
        public BaseTSD.SIZE_T PagefileUsage;
        public BaseTSD.SIZE_T PeakPagefileUsage;

        public PROCESS_MEMORY_COUNTERS() {
            cb = size();
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                    "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                    "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage");
        }
    }
}

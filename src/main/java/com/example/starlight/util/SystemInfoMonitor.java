package com.example.starlight.util;

import oshi.SystemInfo;
import oshi.hardware.HWDiskStore;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 系统信息监控器（CPU / 内存 / 磁盘读写速率），统计口径与任务管理器一致：
 * <ul>
 *   <li>CPU：系统整体负载（{@code getCpuLoad()}）</li>
 *   <li>内存：系统物理内存占用率（非 JVM 堆）</li>
 *   <li>磁盘：读写速率（MB/s，基于 OSHI 两次采样字节差值）</li>
 * </ul>
 * <p>
 * 闪屏期间预热：{@code getCpuLoad()} 首次调用仅建立采样基线（返回 -1），因此
 * {@link #warmUp()} 会立即采样一次建立基线，再延迟采样写入真实值。
 * OSHI 首次初始化（枚举物理磁盘，WMI 查询）在 Windows 上可能耗时数秒，
 * 因此磁盘采样一律在后台线程异步执行，绝不阻塞闪屏初始化线程与 UI 线程。
 */
public final class SystemInfoMonitor {

    /** 是否运行在 GraalVM Native Image 下（OSHI/JNA 不可用，磁盘直走 PDH 桩）。
     *  统一走 {@link NativeImageCompat} 判断：org.graalvm.nativeimage.imagecode 在 substrate 0.0.69
     *  下可能缺失，叠加 java.vm.name=="Substrate VM" 双保险 */
    private static final boolean IS_NATIVE = NativeImageCompat.isNativeImage();

    // ===== Native Image：应用类 native 方法 JNI 注册 =====
    // Native Image 下应用类 native 方法的 JNI 查找只遍历 knownLibraries，符号须位于
    // 某已注册模块的导出表。磁盘桩已 __declspec(dllexport) 进 exe 导出表，这里
    // System.load 加载 exe 自身（Windows 允许 LoadLibrary PE），使查找可命中。
    static {
        if (IS_NATIVE) {
            try {
                String cmd = ProcessHandle.current().info().command().orElse(null);
                if (cmd != null) {
                    System.load(cmd);
                }
            } catch (Throwable ignored) {
                // 加载失败时磁盘桩调用会抛 UnsatisfiedLinkError，被 doDiskSample 的
                // catch(Throwable) 捕获并忽略，不影响 CPU/内存采样
            }
        }
    }

    /** CPU 占用率（百分比，-1 表示尚未采样到有效值） */
    private static volatile double cpuPercent = -1;
    /** 系统物理内存占用率（百分比） */
    private static volatile double memPercent = -1;
    /** 磁盘读取速率（MB/s） */
    private static volatile double diskReadMBps = -1;
    /** 磁盘写入速率（MB/s） */
    private static volatile double diskWriteMBps = -1;

    // 磁盘速率采样状态（仅采样线程访问，无需 volatile）
    private static long lastReadBytes = -1;
    private static long lastWriteBytes = -1;
    private static long lastSampleNanos = -1;

    // OSHI 懒初始化（仅采样线程访问）。首次 new SystemInfo() + 枚举磁盘在 Windows 上
    // 可能耗时数秒甚至卡住，因此只能在后台采样线程中执行，禁止在调用方线程同步执行。
    private static SystemInfo systemInfo;

    // ===== Native Image 磁盘速率桩（native/management_stub.c 的 PDH 实现） =====
    // Native Image 下 OSHI/JNA 不可用（运行期反射生成代理被关闭），Java 侧回退到这两个
    // JNI 桩读取 PDH 性能计数器（物理磁盘总读写速率）。JVM 模式下这两个方法无实现，
    // 仅在 OSHI 失败时才会被调用（抛 UnsatisfiedLinkError，被 Throwable 捕获）。
    private static native double nativeDiskReadMBps();
    private static native double nativeDiskWriteMBps();

    /** 内部采样线程（单线程串行，避免与 UI 线程竞争） */
    private static final ScheduledExecutorService SAMPLER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "system-info-sampler");
                t.setDaemon(true);
                return t;
            });

    private SystemInfoMonitor() {}

    /**
     * 闪屏期间调用：同步采样 CPU/内存建立基线（JDK 内置 API，毫秒级，不阻塞），
     * 磁盘采样（OSHI 首次初始化较慢）交由后台线程异步完成，延迟 1 秒/3 秒各采样一次
     * 以建立磁盘速率基线。
     */
    public static void warmUp() {
        doQuickSample();
        SAMPLER.schedule(SystemInfoMonitor::doFullSample, 1, TimeUnit.SECONDS);
        SAMPLER.schedule(SystemInfoMonitor::doFullSample, 3, TimeUnit.SECONDS);
    }

    /** 提交一次采样：CPU/内存同步执行（毫秒级），磁盘采样异步执行，不阻塞调用方 */
    public static void sample() {
        doQuickSample();
        SAMPLER.execute(SystemInfoMonitor::doDiskSample);
    }

    /** 快速采样：CPU + 物理内存（JDK 内置 API，毫秒级，可在任意线程同步执行） */
    private static void doQuickSample() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                // CPU：系统整体负载
                double load = sunOsBean.getCpuLoad();
                if (load >= 0) cpuPercent = load * 100;
                // 内存：系统物理内存占用率（与任务管理器"内存"一致）
                long totalMem = sunOsBean.getTotalMemorySize();
                long freeMem = sunOsBean.getFreeMemorySize();
                if (totalMem > 0) memPercent = (double) (totalMem - freeMem) / totalMem * 100;
            } else {
                double sysLoad = osBean.getSystemLoadAverage();
                int cores = osBean.getAvailableProcessors();
                if (sysLoad >= 0 && cores > 0) cpuPercent = sysLoad / cores * 100;
            }
        } catch (Throwable ignored) {
            // 必须捕获 Throwable：Native Image 下 JMX native 桩若链接失败抛的是
            // UnsatisfiedLinkError（Error 子类），Exception 捕不到会杀死采样线程
        }
    }

    /** 全量采样：快速采样 + 磁盘采样（仅供后台线程调用） */
    private static void doFullSample() {
        doQuickSample();
        doDiskSample();
    }

    /** 磁盘读写速率：JVM 下优先 OSHI，Native Image 下直走 PDH 桩（OSHI 行为不可靠） */
    private static void doDiskSample() {
        if (IS_NATIVE) {
            // Native Image：OSHI/JNA 不可用且行为不可靠（首次初始化抛异常、之后可能返回空数据
            // 覆盖真实值），因此直接调用 PDH 桩，绝不经 OSHI 路径
            try {
                double r = nativeDiskReadMBps();
                double w = nativeDiskWriteMBps();
                if (r >= 0 && w >= 0) {
                    diskReadMBps = r;
                    diskWriteMBps = w;
                }
            } catch (Throwable ignored) {
                // 桩不可用（如 JNI 注册失败）时保持上次值；PDH 桩内部自带 1 秒节流缓存
            }
            return;
        }
        try {
            SystemInfo si = systemInfo;
            if (si == null) {
                si = new SystemInfo();
                systemInfo = si;
            }
            // 每次调用 getDiskStores() 都会刷新各磁盘的读写字节计数器（OSHI 推荐用法），
            // 该调用在 Windows 上涉及 WMI/性能计数器查询，因此只在后台采样线程执行
            long read = 0, write = 0;
            for (HWDiskStore store : si.getHardware().getDiskStores()) {
                read += store.getReadBytes();
                write += store.getWriteBytes();
            }
            long now = System.nanoTime();
            if (lastReadBytes >= 0 && lastSampleNanos >= 0) {
                long dtNanos = now - lastSampleNanos;
                if (dtNanos > 0) {
                    double toMBps = 1_000_000_000.0 / dtNanos / (1024.0 * 1024.0);
                    diskReadMBps = (read - lastReadBytes) * toMBps;
                    diskWriteMBps = (write - lastWriteBytes) * toMBps;
                }
            }
            lastReadBytes = read;
            lastWriteBytes = write;
            lastSampleNanos = now;
        } catch (Throwable ignored) {
            // Native Image 模式：OSHI/JNA 不可用（初始化抛 Error/Exception），回退 PDH 桩。
            // 注意必须捕获 Throwable：JNA 初始化失败在 native 下抛的是 Error 子类。
            try {
                // PDH 桩内部自带 1 秒节流缓存：同一采样周期内 read/write 返回同一份数据，
                // 首次 collect 无数据时返回 -1，下一周期（3 秒后）即返回有效值
                double r = nativeDiskReadMBps();
                double w = nativeDiskWriteMBps();
                if (r >= 0 && w >= 0) {
                    diskReadMBps = r;
                    diskWriteMBps = w;
                }
            } catch (Throwable ignored2) {
                // 保持上次值
            }
        }
    }

    public static double getCpuPercent() { return cpuPercent; }

    public static double getMemPercent() { return memPercent; }

    public static double getDiskReadMBps() { return diskReadMBps; }

    public static double getDiskWriteMBps() { return diskWriteMBps; }
}

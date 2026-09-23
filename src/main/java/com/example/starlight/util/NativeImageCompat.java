package com.example.starlight.util;

/**
 * OSHI / JNA 在 GraalVM Native Image 下的兜底开关。
 *
 * <p>背景：本项目打包成单文件 exe 走的是 GraalVM 17 + GluonFX 的老式原生编译流程。
 * JNA 的 jnidispatch.dll 在 Native Image 下通过 JNI_OnLoad 回调查找 {@code java.nio.Buffer}
 * 等 JDK 内部类，而老式 jni-config 流程很难把这些 JDK 内部类全部注册进去，
 * 初始化失败会抛 {@code NoClassDefFoundError: java/nio/Buffer}。
 * 该错误发生在 {@code com.sun.jna.Native.<clinit>} / OSHI 类静态初始化链里，
 * <b>即使调用方包了 try-catch(Throwable) 也可能拦不住，并直接打死调用线程</b>
 * ——实测会把 JavaFX UI 线程干死，启动器卡死在闪屏（动画定格）。
 *
 * <p>因此所有 OSHI / JNA 入口在使用前必须先经过本类的 {@link #isNativeImage()} 守卫：
 * Native Image 下走备用实现（JDK MXBean / PDH 桩 / PowerShell），绝不触碰 JNA/OSHI 类；
 * 普通 JVM（java -jar / jpackage）下保持原有行为。
 */
public final class NativeImageCompat {

    private NativeImageCompat() {
    }

    /**
     * 是否运行在 GraalVM Native Image 下。
     *
     * <p>{@code org.graalvm.nativeimage.imagecode} 在 substrate 0.0.69 下可能缺失，
     * 因此叠加 {@code java.vm.name == "Substrate VM"} 双保险判断。
     */
    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null
                || "Substrate VM".equals(System.getProperty("java.vm.name"));
    }
}

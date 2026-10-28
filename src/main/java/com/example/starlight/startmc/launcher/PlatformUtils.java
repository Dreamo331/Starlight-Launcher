/** (C) Copyright 2026 Starlight. All rights reserved. */
// 跨平台工具类 //
// 集中管理所有平台相关的逻辑，包括：
//   - 操作系统检测
//   - 路径分隔符
//   - Native 文件扩展名
//   - 环境变量适配
//   - Shell 命令包装
//
// 使用方式: PlatformUtils.isWindows() / PlatformUtils.getClassPathSeparator() ...
package com.example.starlight.startmc.launcher;


import com.example.starlight.util.I18N;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 跨平台工具类（单例模式，纯静态方法）
 *
 * 自动检测当前运行的操作系统，并提供全局一致的平台适配方法。
 * 所有启动器类应该通过此类获取平台信息，而非硬编码 Windows 逻辑。
 * 参考 HMCL 的 OperatingSystem / Platform 实现。
 */
public final class PlatformUtils {

    // 操作系统枚举 //

    public enum OS {
        WINDOWS,
        LINUX,
        MACOS,
        FREEBSD,
        UNKNOWN
    }

    // 运行时检测 //

    private static final OS CURRENT;
    private static final String OS_NAME;
    /** 平台本地编码（用于 cmd.exe 等原生工具交互） */
    private static final Charset NATIVE_CHARSET;

    static {
        OS_NAME = System.getProperty("os.name", "unknown").toLowerCase();
        if (OS_NAME.contains("win")) {
            CURRENT = OS.WINDOWS;
        } else if (OS_NAME.contains("mac")) {
            CURRENT = OS.MACOS;
        } else if (OS_NAME.contains("linux") || OS_NAME.contains("unix")) {
            CURRENT = OS.LINUX;
        } else if (OS_NAME.contains("freebsd")) {
            CURRENT = OS.FREEBSD;
        } else {
            CURRENT = OS.UNKNOWN;
        }

        // 检测平台本地编码（参考 HMCL OperatingSystem.NATIVE_CHARSET）
        Charset detected = StandardCharsets.UTF_8;
        try {
            String nativeEncoding = System.getProperty("native.encoding");
            if (nativeEncoding != null) {
                detected = Charset.forName(nativeEncoding);
            }
            if (detected == StandardCharsets.UTF_8 || detected == StandardCharsets.US_ASCII) {
                detected = StandardCharsets.UTF_8;
            } else if ("GBK".equalsIgnoreCase(detected.name()) || "GB2312".equalsIgnoreCase(detected.name())) {
                detected = Charset.forName("GB18030");
            }
        } catch (UnsupportedCharsetException e) {
            detected = StandardCharsets.UTF_8;
        }
        NATIVE_CHARSET = detected;
    }

    private PlatformUtils() {}

    // 平台查询 //

    public static OS getCurrent() {
        return CURRENT;
    }

    public static String getOsNameString() {
        return OS_NAME;
    }

    public static boolean isWindows() {
        return CURRENT == OS.WINDOWS;
    }

    public static boolean isLinux() {
        return CURRENT == OS.LINUX;
    }

    public static boolean isMacOS() {
        return CURRENT == OS.MACOS;
    }

    public static boolean isUnixLike() {
        return CURRENT == OS.LINUX || CURRENT == OS.MACOS || CURRENT == OS.FREEBSD;
    }

    /**
     * @return 平台的本地编码（参考 HMCL 的 NATIVE_CHARSET）
     */
    public static Charset getNativeCharset() {
        return NATIVE_CHARSET;
    }

    // 类路径分隔符 //

    /**
     * @return Windows 返回 ";", Linux/macOS 返回 ":"
     */
    public static String getClassPathSeparator() {
        return isWindows() ? ";" : ":";
    }

    /**
     * @return Java 模块路径分隔符（同样遵循 OS 规则）
     */
    public static String getModulePathSeparator() {
        return getClassPathSeparator();
    }

    // Native 文件扩展名 //

    /**
     * @return Windows: ".dll", macOS: ".dylib", Linux: ".so"
     */
    public static String getNativeExtension() {
        if (isWindows()) return ".dll";
        if (isMacOS())   return ".dylib";
        return ".so"; // Linux & others
    }

    /**
     * @return Native JAR 文件名中的关键词，如 "natives-windows"
     */
    public static String getNativeJarKeyword() {
        if (isWindows()) return "natives-windows";
        if (isMacOS())   return "natives-macos";
        return "natives-linux";
    }

    // OS 规则名称 //

    /**
     * @return 对应 Minecraft version.json 中 os.name 的值
     */
    public static String getOsRuleName() {
        if (isWindows()) return "windows";
        if (isMacOS())   return "osx";
        return "linux";
    }

    // 架构检测 //

    /**
     * 检测当前系统架构（用于 natives 筛选）
     * @return "x86_64", "arm64", "x86" 等
     */
    public static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        if (arch.contains("amd64") || arch.contains("x86_64"))  return "x86_64";
        if (arch.contains("x86"))                               return "x86";
        return arch;
    }

    /**
     * 判断是否为 64 位 JVM
     */
    public static boolean is64Bit() {
        String arch = getArch();
        return "x86_64".equals(arch) || "arm64".equals(arch) || "amd64".equals(arch);
    }

    // Native 目录候选名 //

    /**
     * @return 平台对应的 natives 目录候选名列表（按优先级排序）
     */
    public static List<String> getNativesDirCandidates() {
        String arch = getArch();
        if (isWindows()) {
            return Arrays.asList(
                "natives-windows-" + arch,
                "natives-windows"
            );
        }
        if (isMacOS()) {
            return Arrays.asList(
                "natives-macos-" + arch,
                "natives-macos",
                "natives-osx"
            );
        }
        // Linux
        return Arrays.asList(
            "natives-linux-" + arch,
            "natives-linux"
        );
    }

    // Shell 命令执行 //

    /**
     * 根据当前平台创建可执行系统命令的 ProcessBuilder
     *
     * @param cmd 原始命令字符串（如 "echo hello"）
     * @return 已配置好的 ProcessBuilder
     */
    public static ProcessBuilder createShellProcessBuilder(String cmd) {
        if (isWindows()) {
            return new ProcessBuilder("cmd.exe", "/c", cmd);
        } else {
            return new ProcessBuilder("/bin/sh", "-c", cmd);
        }
    }

    // 环境变量适配 //

    /**
     * 获取平台对应的环境变量补充
     *
     * Windows 下不再覆盖 APPDATA：游戏数据目录已由 --gameDir 参数控制，
     * 覆盖 %APPDATA% 反而可能让依赖它的 mod/工具找不到真实系统路径
     * （进程默认继承启动器的真实 APPDATA）。Linux/macOS 下规范化
     * XDG 兼容变量。参数 gameDirParent 仅为兼容旧调用方保留。
     *
     * @param gameDirParent 游戏目录父路径（当前未使用，保留以兼容旧签名）
     * @return 环境变量键值对 Map
     */
    public static java.util.Map<String, String> getPlatformEnvVars(String gameDirParent) {
        java.util.Map<String, String> env = new java.util.HashMap<>();

        if (isWindows()) {
            // 不覆盖 APPDATA，继承启动器进程的真实环境变量
        } else {
            // Linux/macOS: 设置 XDG 兼容变量
            String home = System.getProperty("user.home", "");
            env.put("HOME", home);

            String dataHome;
            if (isMacOS()) {
                dataHome = home + "/Library/Application Support";
            } else {
                dataHome = System.getenv().getOrDefault("XDG_DATA_HOME",
                    home + "/.local/share");
            }
            env.put("XDG_DATA_HOME", dataHome);
        }

        return env;
    }

    // 日志工具 //

    /**
     * 获取平台的路径分隔符（用于路径拼接）
     */
    public static String getFileSeparator() {
        return System.getProperty("file.separator");
    }

    /**
     * 获取平台的换行符
     */
    public static String getLineSeparator() {
        return System.getProperty("line.separator");
    }

    /**
     * 判断指定的文件扩展名是否应被排除（如 .sha1, .git 等）
     * 参考 HMCL 的 Unzipper 过滤逻辑
     */
    public static boolean isExcludedNativeFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".sha1") || lower.endsWith(".git");
    }

    /**
     * @return 系统总内存大小（字节）
     */
    public static long getTotalMemoryBytes() {
        java.lang.management.OperatingSystemMXBean osBean =
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            return sunOsBean.getTotalMemorySize();
        }
        // fallback: 返回 4GB
        return 4L * 1024 * 1024 * 1024;
    }

    /**
     * 把「进程被系统 / 原生库异常终止」的退出码翻译成人话。
     *
     * <p>为什么需要：Java 层异常会留下 crash-report / 堆栈，而原生崩溃（显卡驱动、JNI、原生库）
     * 只会让进程直接消失，Java 这边唯一拿到的证据就是退出码。Windows 上它是以带符号
     * 32 位整数形式返回的 NTSTATUS —— 例如 {@code -1073741819} 就是 {@code 0xC0000005}
     * （{@code 4294967296 - 1073741819 = 3221225477 = 0xC0000005}），即访问冲突。
     * 不翻译的话，诊断面板只能写「未知错误」，对排查毫无帮助。
     *
     * @param exitCode 游戏进程退出码
     * @return 「访问冲突（0xC0000005）」这类描述；不认识的码返回 null
     */
    public static String describeExitCode(int exitCode) {
        if (exitCode == 0) return null;
        // Unix：被信号杀死时 JVM 以 128+信号 返回
        if (isUnixLike() && exitCode > 128) {
            return switch (exitCode - 128) {
                case 4 -> I18N.get("launcher.t0010");
                case 6 -> I18N.get("launcher.t0011");
                case 8 -> I18N.get("launcher.t0012");
                case 9 -> I18N.get("launcher.t0013");
                case 11 -> I18N.get("launcher.t0014");
                case 15 -> I18N.get("launcher.t0015");
                default -> null;
            };
        }
        if (exitCode >= 0) return null;
        // Windows NTSTATUS（带符号 32 位）
        return switch (exitCode) {
            case -1073741819 -> I18N.get("launcher.t0016");        // ACCESS_VIOLATION
            case -1073741818 -> I18N.get("launcher.t0017");      // IN_PAGE_ERROR（驱动 / 磁盘）
            case -1073741795 -> I18N.get("launcher.t0018");        // ILLEGAL_INSTRUCTION
            case -1073741801 -> I18N.get("launcher.t0019");        // NO_MEMORY
            case -1073741571 -> I18N.get("launcher.t0020");          // STACK_OVERFLOW
            case -1073740791 -> I18N.get("launcher.t0021"); // STACK_BUFFER_OVERRUN
            case -1073741515 -> I18N.get("launcher.t0022");    // DLL_NOT_FOUND
            case -1073741511 -> I18N.get("launcher.t0023"); // ENTRYPOINT_NOT_FOUND
            case -1073741502 -> I18N.get("launcher.t0024"); // DLL_INIT_FAILED
            case -1073741811 -> I18N.get("launcher.t0025");        // INVALID_PARAMETER
            default -> null;
        };
    }

    /**
     * 判定某个 java 可执行文件是不是 GraalVM。
     *
     * <p>为什么要单独判：GraalVM 用 JVMCI 编译器，HotSpot 那一套激进调优参数
     * （{@code -XX:+UnlockExperimentalVMOptions} + G1 细调 + code cache 分段 + NodeLimit…）
     * 与它叠加时的行为不可预期，社区已有多例在字体 / LWJGL 原生路径上直接
     * {@code 0xC0000409}（栈缓冲区溢出）/ {@code 0xC0000005}（访问冲突）且不留 Java 堆栈的记录。
     * 启动器据此对 GraalVM 改用最简参数集。
     *
     * <p>只读路径与 {@code release} 文件，**不启子进程**：启动路径上不能为判版本多付一次 fork。
     *
     * @param javaPath 游戏用的 java / javaw 可执行文件路径
     * @return true 表示 GraalVM（路径名或 release 文件里的 vendor 字段命中）
     */
    public static boolean isGraalVmRuntime(String javaPath) {
        if (javaPath == null || javaPath.isBlank()) return false;
        String normalized = javaPath.replace('/', '\\').toLowerCase(Locale.ROOT);
        // 最常见：解压目录名里就带 graalvm（如 ...\graalvm-jdk-25i2\...\bin\java.exe）
        if (normalized.contains("graal")) return true;

        Path home = javaHomeOf(javaPath);
        if (home == null) return false;
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release) || !Files.isReadable(release)) return false;
        try (BufferedReader reader = Files.newBufferedReader(release, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String upper = line.toUpperCase(Locale.ROOT);
                if ((upper.startsWith("JAVA_VENDOR=") || upper.startsWith("IMPLEMENTOR=")
                        || upper.startsWith("IMPLEMENTOR_VERSION=") || upper.startsWith("JVM_VENDOR="))
                        && upper.contains("GRAAL")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // 读不到 release 就按普通 JVM 处理（宁可多给调优参数，也不要误判成 GraalVM 丢功能）
        }
        return false;
    }

    /** 由 {@code <home>/bin/java.exe} 反推 {@code <home>}；结构不符返回 null */
    private static Path javaHomeOf(String javaPath) {
        try {
            Path bin = Paths.get(javaPath).toAbsolutePath().getParent();
            if (bin == null || bin.getFileName() == null
                    || !bin.getFileName().toString().equalsIgnoreCase("bin")) {
                return null;
            }
            return bin.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return 当前 Java 的主版本号（如 17, 21）
     */
    public static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version == null) return 8;
        // 处理格式如 "17.0.1" 或 "1.8.0_321"
        if (version.startsWith("1.")) {
            try {
                // 取 "1." 之后到下一个 '.' 之间的完整次版本号（如 "1.8" → 8，兼容 "1.10" → 10）
                int dotIndex = version.indexOf('.', 2);
                String minor = dotIndex > 0 ? version.substring(2, dotIndex) : version.substring(2);
                return Integer.parseInt(minor);
            } catch (NumberFormatException e) {
                return 8;
            }
        }
        try {
            int dotIndex = version.indexOf('.');
            if (dotIndex > 0) {
                return Integer.parseInt(version.substring(0, dotIndex));
            }
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            return 8;
        }
    }
}

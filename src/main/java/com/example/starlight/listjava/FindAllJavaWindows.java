package com.example.starlight.listjava;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 平台 Java 安装自动检测工具。
 * 参考 HMCL-main 实现，扫描注册表、常用安装目录、JAVA_HOME 和 PATH 来发现所有 Java 安装。
 * 提供直接 API 查询（推荐）和命令行两种使用方式。
 *
 * 改进（参考 HMCL）：
 * - 解析 Java 安装目录下的 release 文件获取更可靠的版本信息
 * - 追踪供应商（vendor）信息
 * - JDK / JRE 区分
 * - 更好的版本号解析与排序
 */
public class FindAllJavaWindows {

    /** 查找到的 Java 安装条目 */
    public static class JavaEntry implements Comparable<JavaEntry> {
        public final String homePath;
        public final String version;
        public final int majorVersion;
        public final String vendor;
        public final boolean isJDK;

        public JavaEntry(String homePath, String version) {
            this(homePath, version, null, false);
        }

        public JavaEntry(String homePath, String version, String vendor, boolean isJDK) {
            this.homePath = homePath;
            this.version = version != null ? version : "未知";
            this.majorVersion = parseMajorVersion(this.version);
            this.vendor = vendor;
            this.isJDK = isJDK;
        }

        public String getJavaExePath() {
            return Paths.get(homePath, "bin", "java.exe").toString();
        }

        public String getJavacExePath() {
            return Paths.get(homePath, "bin", "javac.exe").toString();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getJavaExePath()).append("  (").append(version).append(")");
            if (vendor != null) sb.append(" [").append(vendor).append("]");
            if (isJDK) sb.append(" [JDK]");
            return sb.toString();
        }

        @Override
        public int compareTo(JavaEntry that) {
            // 按主版本降序排列
            int c = Integer.compare(that.majorVersion, this.majorVersion);
            if (c != 0) return c;
            // 同版本下 JDK 优先
            if (this.isJDK != that.isJDK) return this.isJDK ? -1 : 1;
            // 最后按路径
            return this.homePath.compareTo(that.homePath);
        }

        /**
         * 参考 HMCL JavaInfo.parseVersion()
         * 解析版本号中的主版本号：
         * - "1.8.0_202" -> 8
         * - "17.0.1" -> 17
         * - "21" -> 21
         */
        private static int parseMajorVersion(String v) {
            if (v == null || v.isEmpty()) return -1;
            int startIndex = v.startsWith("1.") ? 2 : 0;
            int endIndex = startIndex;
            while (endIndex < v.length()) {
                char ch = v.charAt(endIndex);
                if (ch >= '0' && ch <= '9') endIndex++;
                else break;
            }
            try {
                return endIndex > startIndex
                        ? Integer.parseInt(v.substring(startIndex, endIndex))
                        : -1;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * 查找本机所有 Java 安装（直接 API，推荐使用）
     */
    public static List<JavaEntry> findAll() {
        Set<String> javaHomes = new LinkedHashSet<>();

        // 1. 通过注册表查找 JDK / JRE
        javaHomes.addAll(queryRegistry("HKLM\\SOFTWARE\\JavaSoft\\JDK"));
        javaHomes.addAll(queryRegistry("HKLM\\SOFTWARE\\JavaSoft\\JRE"));
        javaHomes.addAll(queryRegistry("HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JDK"));
        javaHomes.addAll(queryRegistry("HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JRE"));

        // 2. 扫描常见安装目录
        javaHomes.addAll(scanCommonDirs());

        // 3. 检查 JAVA_HOME 环境变量
        String javaHomeEnv = System.getenv("JAVA_HOME");
        if (javaHomeEnv != null && isValidJavaHome(javaHomeEnv)) {
            javaHomes.add(javaHomeEnv);
        }

        // 4. 检查 JDK_HOME 环境变量
        String jdkHomeEnv = System.getenv("JDK_HOME");
        if (jdkHomeEnv != null && isValidJavaHome(jdkHomeEnv)) {
            javaHomes.add(jdkHomeEnv);
        }

        // 5. 从 PATH 中提取 Java 路径
        javaHomes.addAll(findJavaFromPath());

        // 6. 扫描 Program Files\Eclipse Adoptium, Program Files\BellSoft 等常见目录
        javaHomes.addAll(scanAdditionalDirs());

        // 7. 扫描各磁盘根目录下名称含 java/jdk/jre 的自定义目录（如 D:\java_8_or_17+\java8，
        //    便携解压版 Java 常用目录，不在注册表/Program Files 中）
        javaHomes.addAll(scanDiskRootJavaDirs());

        List<JavaEntry> result = new ArrayList<>();
        for (String home : javaHomes) {
            JavaInfo info = getJavaInfo(home);
            result.add(new JavaEntry(home, info.version, info.vendor, info.isJDK));
        }

        // 按版本排序
        Collections.sort(result);
        return result;
    }

    public static void main(String[] args) {
        List<JavaEntry> entries = findAll();
        System.out.println("Found Java paths (up to java.exe):");
        for (JavaEntry entry : entries) {
            System.out.println(entry);
        }
        if (entries.isEmpty()) {
            System.out.println("No Java installation found.");
        }
    }

    // ================================================================
    //  Java 信息采集（参考 HMCL JavaInfo.fromReleaseFile）
    // ================================================================

    /** Java 信息聚合 */
    private static class JavaInfo {
        final String version;
        final String vendor;
        final boolean isJDK;

        JavaInfo(String version, String vendor, boolean isJDK) {
            this.version = version != null ? version : "未知";
            this.vendor = vendor;
            this.isJDK = isJDK;
        }

        static final JavaInfo UNKNOWN = new JavaInfo("未知", null, false);
    }

    /**
     * 获取指定 Java 主目录的信息。
     * 优先读取 release 文件（更可靠），降级到 java -version。
     */
    private static JavaInfo getJavaInfo(String javaHome) {
        if (javaHome == null) return JavaInfo.UNKNOWN;

        // 检测 JDK / JRE
        Path javacPath = Paths.get(javaHome, "bin", "javac.exe");
        boolean isJDK = Files.isRegularFile(javacPath) || Files.isExecutable(javacPath);

        // 1. 尝试读取 release 文件
        Path releaseFile = Paths.get(javaHome, "release");
        if (Files.isRegularFile(releaseFile) && Files.isReadable(releaseFile)) {
            try (BufferedReader reader = Files.newBufferedReader(releaseFile, StandardCharsets.UTF_8)) {
                String version = null;
                String vendor = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("JAVA_VERSION=")) {
                        version = extractReleaseValue(line);
                    } else if (line.startsWith("IMPLEMENTOR=")) {
                        vendor = normalizeVendor(extractReleaseValue(line));
                    }
                }
                if (version != null && !version.isEmpty()) {
                    return new JavaInfo(version, vendor, isJDK);
                }
            } catch (IOException ignored) {
                // 降级到 java -version
            }
        }

        // 2. 降级：通过 java -version 获取版本
        String versionFromCmd = getJavaVersionFromCommand(javaHome);
        return new JavaInfo(versionFromCmd, null, isJDK);
    }

    /** 从 release 文件行提取值（去除引号） */
    private static String extractReleaseValue(String line) {
        int eq = line.indexOf('=');
        if (eq < 0) return null;
        String val = line.substring(eq + 1).trim();
        if (val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        return val.isEmpty() ? null : val;
    }

    /**
     * 供应商名称规范化（参考 HMCL JavaInfo.normalizeVendor）
     */
    private static String normalizeVendor(String vendor) {
        if (vendor == null) return null;
        return switch (vendor) {
            case "N/A" -> null;
            case "Oracle Corporation" -> "Oracle";
            case "Azul Systems, Inc." -> "Azul";
            case "IBM Corporation", "International Business Machines Corporation", "Eclipse OpenJ9" -> "IBM";
            case "Eclipse Adoptium" -> "Adoptium";
            case "Amazon.com Inc." -> "Amazon";
            default -> vendor;
        };
    }

    /** 通过 java -version 命令获取版本字符串 */
    private static String getJavaVersionFromCommand(String javaHome) {
        Path javaExe = Paths.get(javaHome, "bin", "java.exe");
        if (!Files.isExecutable(javaExe)) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaExe.toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null) {
                    Pattern versionPattern = Pattern.compile("version\\s+\"([^\"]+)\"");
                    Matcher m = versionPattern.matcher(line);
                    if (m.find()) return m.group(1);
                }
            }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            // ignore
        }
        return null;
    }

    // ================================================================
    //  注册表查询
    // ================================================================

    /** 查询注册表指定键下的所有子键，提取 JavaHome 值 */
    private static List<String> queryRegistry(String regKey) {
        List<String> homes = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "reg", "query", regKey, "/s"
            });
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), System.getProperty("sun.stdout.encoding", "GBK")))) {
                String line;
                Pattern homePattern = Pattern.compile("^\\s*JavaHome\\s+REG_SZ\\s+(.+)$", Pattern.CASE_INSENSITIVE);
                while ((line = reader.readLine()) != null) {
                    Matcher homeMatcher = homePattern.matcher(line);
                    if (homeMatcher.find()) {
                        String home = homeMatcher.group(1).trim();
                        if (isValidJavaHome(home)) {
                            homes.add(home);
                        }
                    }
                }
            }
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            // 忽略错误
        }
        return homes;
    }

    // ================================================================
    //  文件系统扫描
    // ================================================================

    /** 扫描 Program Files 下的常见 Java 目录 */
    private static List<String> scanCommonDirs() {
        List<String> homes = new ArrayList<>();
        for (String base : new String[]{
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")
        }) {
            if (base == null) continue;
            Path javaDir = Paths.get(base, "Java");
            if (Files.isDirectory(javaDir)) {
                scanDir(javaDir, homes);
            }
        }
        return homes;
    }

    /** 扫描其他常见安装商目录 */
    private static List<String> scanAdditionalDirs() {
        List<String> homes = new ArrayList<>();
        for (String base : new String[]{
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")
        }) {
            if (base == null) continue;
            // Adoptium
            scanDir(Paths.get(base, "Eclipse Adoptium"), homes);
            // BellSoft
            scanDir(Paths.get(base, "BellSoft"), homes);
            // Liberica
            scanDir(Paths.get(base, "Liberica"), homes);
            // Zulu
            scanDir(Paths.get(base, "Zulu"), homes);
            // Microsoft
            scanDir(Paths.get(base, "Microsoft"), homes);
            // Amazon Corretto
            scanDir(Paths.get(base, "Amazon Corretto"), homes);
            // GraalVM
            scanDir(Paths.get(base, "GraalVM"), homes);
            // SapMachine
            scanDir(Paths.get(base, "SapMachine"), homes);
            // Tencent Kona
            scanDir(Paths.get(base, "Tencent Kona"), homes);
            // Alibaba Dragonwell
            scanDir(Paths.get(base, "Alibaba Dragonwell"), homes);
        }
        return homes;
    }

    /**
     * 扫描各磁盘根目录下名称含 java/jdk/jre 的目录（含二级子目录）。
     * 覆盖便携解压版 Java 的常见摆放位置（如 D:\java_8_or_17+\java8），
     * 这类安装既不在注册表也不在 Program Files 下。
     */
    private static List<String> scanDiskRootJavaDirs() {
        List<String> homes = new ArrayList<>();
        for (File root : File.listRoots()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root.toPath())) {
                for (Path sub : stream) {
                    if (!Files.isDirectory(sub)) continue;
                    String name = sub.getFileName().toString().toLowerCase();
                    if (!(name.contains("java") || name.contains("jdk") || name.contains("jre"))) continue;
                    if (isValidJavaHome(sub.toString())) {
                        homes.add(sub.toString());
                    } else {
                        // 二级子目录（如 java_8_or_17+ 下的 java8 / java17+ / java21）
                        try (DirectoryStream<Path> inner = Files.newDirectoryStream(sub)) {
                            for (Path sub2 : inner) {
                                if (Files.isDirectory(sub2) && isValidJavaHome(sub2.toString())) {
                                    homes.add(sub2.toString());
                                }
                            }
                        } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }
        return homes;
    }

    /** 扫描目录下的子目录，找到有效的 JavaHome 就添加 */
    private static void scanDir(Path dir, List<String> homes) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path sub : stream) {
                if (Files.isDirectory(sub) && isValidJavaHome(sub.toString())) {
                    homes.add(sub.toString());
                }
            }
        } catch (IOException ignored) {}
    }

    /** 从 PATH 中提取 Java 路径 */
    private static List<String> findJavaFromPath() {
        List<String> homes = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return homes;

        Set<String> seenHomes = new HashSet<>();
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path javaExe = Paths.get(dir, "java.exe");
            if (Files.isExecutable(javaExe)) {
                Path parent = Paths.get(dir).getParent();
                if (parent != null) {
                    String home = parent.toString();
                    if (isValidJavaHome(home) && seenHomes.add(home)) {
                        homes.add(home);
                    }
                }
            }
        }
        return homes;
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /** 判断目录是否是一个有效的 Java 安装主目录 */
    private static boolean isValidJavaHome(String dir) {
        if (dir == null) return false;
        Path javaExe = Paths.get(dir, "bin", "java.exe");
        Path javaWExe = Paths.get(dir, "bin", "javaw.exe");
        return Files.isExecutable(javaExe) || Files.isExecutable(javaWExe);
    }
}
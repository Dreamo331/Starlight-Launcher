package com.example.starlight.crash;


import com.example.starlight.util.I18N;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Parses Minecraft crash report files (-report.txt or hs_err_pid files).
 * Extracts structured information such as title, description, cause,
 * Java version, stack trace, and full raw content.
 */
public class CrashReportParser {

    /**
     * Parse a crash report file and extract structured information.
     *
     * @param filePath absolute or relative path to the crash report file
     * @return a {@link CrashInfo} containing parsed sections, or {@code null} if parsing fails
     */
    public static CrashInfo parse(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return null;
        }

        try {
            String fullContent = Files.readString(path);
            if (fullContent.isBlank()) {
                return null;
            }

            String[] lines = fullContent.split("\\R", -1);

            String title = extractTitle(lines);
            String description = extractSection(lines, "-- Head --", "--");
            String cause = extractCause(lines);
            String javaVersion = extractJavaVersion(lines);
            String stackTrace = extractStackTrace(lines);

            return new CrashInfo(
                    title != null ? title : "",
                    description != null ? description : "",
                    cause != null ? cause : "",
                    javaVersion != null ? javaVersion : "",
                    stackTrace != null ? stackTrace : "",
                    fullContent
            );
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Find the latest crash report file inside {@code gameDir}/crash-reports/.
     * Crash reports are named like {@code crash-2025-01-15_12.30.45-client.txt}.
     *
     * @param gameDir path to the Minecraft game directory
     * @return the absolute path of the most recent crash report, or {@code null} if none exist
     */
    public static String findLatestCrashReport(String gameDir) {
        return findLatestCrashReport(gameDir, null, 0L);
    }

    /**
     * 版本感知 + 时间过滤的崩溃报告查找。
     *
     * <p>修复「崩溃诊断串档」：旧实现永远只看全局 {@code crash-reports/} 并取 mtime 最新，
     * 当版本隔离开启时崩溃报告落在 {@code versions/<版本>/crash-reports/}，而当前选中版本
     * 那次启动又没产出报告时，会把上次其它版本（如 1.12.2 Forge）的旧报告抓来拼到
     * 「26w14a Vanilla」上，造成版本隔离失效的假象。
     *
     * <ul>
     *   <li>{@code version} 非空且隔离目录存在时优先搜版本目录，回退全局；</li>
     *   <li>{@code sinceMillis} &gt; 0 时只取该时间之后写出的报告，避免抓到历史残留；</li>
     *   <li>仍按 mtime 取最新。</li>
     * </ul>
     *
     * @param gameDir     game directory root
     * @param version     崩溃对应的版本 ID（可为 null，表示只看全局）
     * @param sinceMillis 只取此时间戳(ms)之后写出的报告；≤0 表示不过滤
     * @return 最匹配的崩溃报告绝对路径，或 null
     */
    public static String findLatestCrashReport(String gameDir, String version, long sinceMillis) {
        List<Path> dirs = new ArrayList<>();
        // 版本隔离目录优先：隔离开启时崩溃报告落在 versions/<版本>/crash-reports
        if (version != null && !version.isBlank()) {
            Path versionDir = Paths.get(gameDir, "versions", version, "crash-reports");
            if (Files.isDirectory(versionDir)) dirs.add(versionDir);
        }
        Path globalDir = Paths.get(gameDir, "crash-reports");
        if (Files.isDirectory(globalDir)) dirs.add(globalDir);
        if (dirs.isEmpty()) return null;

        for (Path dir : dirs) {
            Path found = latestCrashReportIn(dir, sinceMillis);
            if (found != null) return found.toString();
        }
        return null;
    }

    /** 在单个目录内取最新（且满足时间过滤）的 crash-*.txt；无则返回 null */
    private static Path latestCrashReportIn(Path crashReportsDir, long sinceMillis) {
        try (Stream<Path> stream = Files.list(crashReportsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("crash-.*\\.txt"))
                    .filter(p -> sinceMillis <= 0 || p.toFile().lastModified() >= sinceMillis)
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 查找本次启动之后写出的 {@code hs_err_pid*.log}（JVM 原生崩溃的唯一现场证据）。
     *
     * <p>Java 层崩溃会留 crash-report，而 {@code EXCEPTION_ACCESS_VIOLATION} 这类原生崩溃由
     * HotSpot 的 signal handler 兜住，只写下这份 hs_err 然后进程直接消失；面板不主动找它，
     * 用户就只看到一句「未知错误」。JVM 把 ErrorFile 写在**进程工作目录**，本启动器把工作目录
     * 设成版本隔离目录 / 游戏目录，所以两处都搜，再拿启动器自身工作目录兜底。
     *
     * @param gameDir     game directory root
     * @param version     版本 ID（可为 null）
     * @param sinceMillis 只取此时间戳(ms)之后写出的文件；≤0 表示不过滤
     * @return 最匹配的 hs_err 绝对路径，或 null
     */
    public static String findLatestHsErr(String gameDir, String version, long sinceMillis) {
        List<Path> dirs = new ArrayList<>();
        if (gameDir != null && !gameDir.isBlank()) {
            if (version != null && !version.isBlank()) {
                dirs.add(Paths.get(gameDir, "versions", version));
            }
            dirs.add(Paths.get(gameDir));
        }
        dirs.add(Paths.get("."));

        Path newest = null;
        long newestTime = Long.MIN_VALUE;
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> stream = Files.list(dir)) {
                for (Path p : stream
                        .filter(f -> f.getFileName().toString().matches("hs_err_pid\\d*\\.log"))
                        .toList()) {
                    long time = p.toFile().lastModified();
                    if (sinceMillis > 0 && time < sinceMillis) continue;
                    if (time > newestTime) {
                        newestTime = time;
                        newest = p;
                    }
                }
            } catch (IOException ignored) {
                // 目录不可读就跳过，继续看下一处
            }
        }
        return newest == null ? null : newest.toAbsolutePath().toString();
    }

    /**
     * 从 hs_err 里摘出「崩在哪」：fatal error 标题 + 异常类型行 + Problematic frame 那一帧。
     * hs_err 是 {@code #} 注释风格，直接套 {@link #parse(String)} 只会拿到一个孤零零的 "#"。
     *
     * @return 两三行摘要；文件不可读或没有可识别内容时返回 null
     */
    public static String summarizeHsErr(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null;
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            return null;
        }

        String header = null, exception = null, frame = null;
        for (int i = 0; i < lines.size() && i < 400; i++) {
            String body = stripHsErrComment(lines.get(i));
            if (body.isEmpty()) continue;
            String lower = body.toLowerCase(Locale.ROOT);
            if (header == null && lower.contains("fatal error has been detected")) {
                header = body;
            } else if (exception == null && (body.startsWith("EXCEPTION_") || body.startsWith("SIGSEGV")
                    || body.startsWith("SIGILL") || body.startsWith("SIGBUS") || body.startsWith("SIGFPE"))) {
                exception = body;
            } else if (frame == null && lower.startsWith("problematic frame:")) {
                // 帧信息紧跟其后（形如 "C  [nvoglv64.dll+0x17f5b3b]" 或 "J 1234 c2 ..."）
                for (int j = i + 1; j < lines.size() && j < i + 6; j++) {
                    String next = stripHsErrComment(lines.get(j));
                    if (!next.isEmpty()) {
                        frame = next;
                        break;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (header != null) appendLine(sb, header);
        if (exception != null) appendLine(sb, exception);
        if (frame != null) appendLine(sb, I18N.get("crash.p0031", frame));
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 去掉 hs_err 的 {@code #} 注释前缀并 trim */
    private static String stripHsErrComment(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        while (trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
        return trimmed;
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(line);
    }

    /**
     * Produce a short human-readable summary of a crash report.
     * Uses the title line and the "Caused by:" message when available.
     *
     * @param filePath path to the crash report file
     * @return one or two line summary, or a fallback message
     */
    public static String summarize(String filePath) {
        CrashInfo info = parse(filePath);
        if (info == null) {
            return "Unable to parse crash report.";
        }

        StringBuilder sb = new StringBuilder();
        if (!info.title.isEmpty()) {
            sb.append(info.title);
        }
        if (!info.cause.isEmpty()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("Caused by: ").append(info.cause);
        }
        if (sb.isEmpty()) {
            sb.append("Crash report contains no recognizable title or cause.");
        }
        return sb.toString();
    }

    // Internal helpers //

    /**
     * The first non-empty line of the file is usually the report title
     * (e.g. "---- Minecraft Crash Report ----" or something similar).
     * We try to skip past that decorative line and grab the meaningful title
     * (e.g. "// There are four lights!").
     */
    private static String extractTitle(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Skip the standard header line
            if (trimmed.startsWith("----")) continue;
            return trimmed;
        }
        return "";
    }

    /**
     * Extract the content of a section bounded by startMarker (inclusive) and
     * endMarker (exclusive).  {@code endMarker} may be {@code null} to mean
     * "until end of file".
     */
    private static String extractSection(String[] lines, String startMarker, String endMarker) {
        boolean inSection = false;
        StringBuilder section = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            if (!inSection) {
                if (trimmed.startsWith(startMarker)) {
                    inSection = true;
                }
                continue;
            }

            if (endMarker != null && trimmed.startsWith(endMarker)) {
                break;
            }

            if (section.length() > 0) section.append(System.lineSeparator());
            section.append(line);
        }

        String result = section.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Locate the "Caused by:" line and any subsequent lines that are part of
     * the same throwable chain.  Returns the first "Caused by:" text found.
     */
    private static String extractCause(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * Try to extract the Java version from lines that look like
     * "Java Version: 17.0.1" or "Java VM: Java HotSpot(TM) ...".
     */
    private static String extractJavaVersion(String[] lines) {
        for (String line : lines) {
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("java version") || trimmed.startsWith("java vm")) {
                return line.trim();
            }
        }
        return null;
    }

    /**
     * Extract the full stack trace.  This is everything from a line that looks
     * like an exception class name to the end of the file (or until a known
     * section marker appears).
     *
     * In a standard Minecraft crash report, the stack trace appears near the
     * top, starting with a line like {@code java.lang.NullPointerException ...}
     * and continuing until the {@code -- Head --} section or a blank line
     * followed by a section header.
     */
    private static String extractStackTrace(String[] lines) {
        StringBuilder sb = new StringBuilder();
        boolean inTrace = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Start when we see what looks like an exception class name
            if (!inTrace && (trimmed.startsWith("java.") || trimmed.startsWith("net.minecraft.")
                    || trimmed.startsWith("com.mojang.") || trimmed.matches("^[a-zA-Z_][\\w.]*(Exception|Error|Throwable).*"))) {
                inTrace = true;
            }

            if (!inTrace) continue;

            // Stop at known section markers
            if (trimmed.startsWith("--") || trimmed.startsWith("----")) break;

            if (sb.length() > 0) sb.append(System.lineSeparator());
            sb.append(line);
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // Data class //

    /**
     * Structured information extracted from a Minecraft crash report.
     */
    public static class CrashInfo {
        public final String title;
        public final String description;
        public final String cause;
        public final String javaVersion;
        public final String stackTrace;
        public final String fullContent;

        public CrashInfo(String title, String description, String cause,
                         String javaVersion, String stackTrace, String fullContent) {
            this.title = title;
            this.description = description;
            this.cause = cause;
            this.javaVersion = javaVersion;
            this.stackTrace = stackTrace;
            this.fullContent = fullContent;
        }

        @Override
        public String toString() {
            return "CrashInfo{title='" + title + "', cause='" + cause + "'}";
        }
    }
}

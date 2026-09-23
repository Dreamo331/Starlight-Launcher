package com.example.starlight.crash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
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
        Path crashReportsDir = Paths.get(gameDir, "crash-reports");
        if (!Files.isDirectory(crashReportsDir)) {
            return null;
        }

        try (Stream<Path> stream = Files.list(crashReportsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("crash-.*\\.txt"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
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

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Data class
    // -----------------------------------------------------------------------

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

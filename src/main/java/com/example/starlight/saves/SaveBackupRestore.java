package com.example.starlight.saves;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backup and restore Minecraft world saves.
 * <p>
 * Backups are created as ZIP archives stored under a configurable backup
 * directory with a timestamp-based naming scheme.  Restoration extracts the
 * archive back into the world save folder.
 */
public class SaveBackupRestore {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    /**
     * Backup a single world save folder into a timestamped ZIP archive.
     *
     * @param worldPath path to the world save directory (e.g. {@code gameDir/saves/New World})
     * @param backupDir path to the directory where backups will be stored
     * @return an {@link ActionResult} with the backup file path on success,
     *         or an error message on failure
     */
    public static ActionResult backupSave(String worldPath, String backupDir) {
        Path source = Paths.get(worldPath);
        if (!Files.isDirectory(source)) {
            return ActionResult.failure("World directory does not exist: " + worldPath);
        }

        Path backupRoot = Paths.get(backupDir);
        try {
            Files.createDirectories(backupRoot);
        } catch (IOException e) {
            return ActionResult.failure("Cannot create backup directory: " + e.getMessage());
        }

        String worldName = source.getFileName().toString();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        // Sanitize world name for use in a file name
        String safeName = worldName.replaceAll("[\\\\/:*?\"<>|]", "_");
        String archiveName = safeName + "_" + timestamp + ".zip";
        Path archivePath = backupRoot.resolve(archiveName);

        try {
            ZipUtil.zip(source, archivePath);
            return ActionResult.success("Backup created successfully.", archivePath.toString());
        } catch (IOException e) {
            return ActionResult.failure("Failed to create backup: " + e.getMessage());
        }
    }

    /**
     * Backup every sub-directory found under {@code gameDir/saves/}.
     *
     * @param gameDir   path to the Minecraft game directory containing a {@code saves/} folder
     * @param backupDir path to the directory where backups will be stored
     * @return a list of result messages, one per world processed
     */
    public static List<String> backupAllSaves(String gameDir, String backupDir) {
        Path savesDir = Paths.get(gameDir, "saves");
        if (!Files.isDirectory(savesDir)) {
            return Collections.singletonList("No saves directory found at " + savesDir);
        }

        List<String> results = new ArrayList<>();
        try (Stream<Path> worlds = Files.list(savesDir)) {
            List<Path> worldDirs = worlds.filter(Files::isDirectory).collect(Collectors.toList());

            if (worldDirs.isEmpty()) {
                results.add("No world saves found in " + savesDir);
                return results;
            }

            for (Path world : worldDirs) {
                ActionResult result = backupSave(world.toString(), backupDir);
                results.add(result.success
                        ? "Backed up " + world.getFileName() + " -> " + result.data
                        : "Failed to backup " + world.getFileName() + ": " + result.message);
            }
        } catch (IOException e) {
            results.add("Error listing saves directory: " + e.getMessage());
        }

        return results;
    }

    /**
     * List all backup archives in the given backup directory, sorted with the
     * most recent first.
     *
     * @param backupDir path to the backup directory
     * @return a list of absolute paths to backup archives, or an empty list
     */
    public static List<String> listBackups(String backupDir) {
        Path dir = Paths.get(backupDir);
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Restore a backup archive into {@code gameDir/saves/}.
     * <p>
     * The original world name is inferred from the archive file name (everything
     * before the first timestamp separator), and the archive is extracted into
     * {@code gameDir/saves/<worldName>}.
     *
     * @param backupPath path to the backup ZIP archive
     * @param gameDir    path to the Minecraft game directory
     * @return an {@link ActionResult} indicating success or failure
     */
    public static ActionResult restoreBackup(String backupPath, String gameDir) {
        Path archive = Paths.get(backupPath);
        if (!Files.isRegularFile(archive)) {
            return ActionResult.failure("Backup file does not exist: " + backupPath);
        }

        // Derive world name from archive file name
        // File format: WorldName_2025-01-15_123045.zip
        String fileName = archive.getFileName().toString();
        // Remove .zip extension
        String base = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        // Strip the timestamp suffix (everything from the last occurrence of yyyy-MM-dd_HHmmss pattern)
        // Simpler approach: strip the trailing _<timestamp>
        int underscoreIdx = base.lastIndexOf('_');
        if (underscoreIdx > 0) {
            String possibleTimestamp = base.substring(underscoreIdx + 1);
            // Rough check: does it look like a timestamp (has digits and underscores/hyphens)?
            if (possibleTimestamp.matches("\\d{4}-\\d{2}-\\d{2}_\\d{6}")) {
                base = base.substring(0, underscoreIdx);
            }
        }

        String worldName = base;
        Path targetDir = Paths.get(gameDir, "saves", worldName);

        try {
            // Remove existing world directory if present (after confirmation from caller)
            if (Files.exists(targetDir)) {
                deleteDirectory(targetDir);
            }
            Files.createDirectories(targetDir.getParent());
            ZipUtil.unzip(archive, targetDir);
            return ActionResult.success("Restore completed successfully.", targetDir.toString());
        } catch (IOException e) {
            return ActionResult.failure("Failed to restore backup: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Internal utilities
    // -----------------------------------------------------------------------

    /**
     * Recursively delete a directory tree.
     */
    private static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // -----------------------------------------------------------------------
    // Simple lightweight ZIP utility (no external dependency)
    // -----------------------------------------------------------------------

    private static final class ZipUtil {
        private ZipUtil() {
        }

        /**
         * Recursively ZIP a directory into the given archive path.
         */
        static void zip(Path sourceDir, Path zipFile) throws IOException {
            try (java.io.OutputStream os = Files.newOutputStream(zipFile);
                 java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os)) {

                Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Path relative = sourceDir.relativize(file);
                        // Use forward slashes for ZIP entries
                        String entryName = relative.toString().replace('\\', '/');
                        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path relative = sourceDir.relativize(dir);
                        if (!relative.toString().isEmpty()) {
                            String entryName = relative.toString().replace('\\', '/') + "/";
                            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                            zos.closeEntry();
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }

        /**
         * Extract a ZIP archive into the target directory.
         */
        static void unzip(Path zipFile, Path targetDir) throws IOException {
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                    Files.newInputStream(zipFile))) {

                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path resolved = targetDir.resolve(entry.getName()).normalize();
                    // Prevent ZIP-slip
                    if (!resolved.startsWith(targetDir)) {
                        throw new IOException("Zip-slip detected: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(resolved);
                    } else {
                        Files.createDirectories(resolved.getParent());
                        Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                    }
                    zis.closeEntry();
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Result data class
    // -----------------------------------------------------------------------

    /**
     * Wraps the outcome of a backup or restore operation.
     */
    public static class ActionResult {
        public final boolean success;
        public final String message;
        public final Object data;

        private ActionResult(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public static ActionResult success(String message, Object data) {
            return new ActionResult(true, message, data);
        }

        public static ActionResult failure(String message) {
            return new ActionResult(false, message, null);
        }

        @Override
        public String toString() {
            return "ActionResult{success=" + success + ", message='" + message + "'}";
        }
    }
}

/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class MacOSProvider extends AbstractTerracottaProvider {
    private final Path executable, installer;

    public MacOSProvider(TerracottaBundleStub bundle, Path executable, Path installer) {
        super(bundle);
        this.executable = executable;
        this.installer = installer;
    }

    @Override
    public Status status() throws IOException {
        if (!Files.exists(Path.of("/Applications/terracotta.app"))) {
            return Status.NOT_EXIST;
        }
        return bundle.status();
    }

    @Override
    public void install(Path pkg) throws IOException {
        bundle.install(pkg);

        // macOS specific: run osascript for admin installation
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("mac")) return;

        Path osascript = findExecutable("osascript");
        if (osascript == null) {
            throw new IllegalStateException("Cannot locate 'osascript' system executable on MacOS for installing Terracotta.");
        }

        Path tempDir = Files.createTempDirectory("terracotta-pkg");
        Path movedInstaller = tempDir.resolve(installer.getFileName().toString());
        try {
            Files.copy(installer, movedInstaller, StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder pb = new ProcessBuilder(
                    osascript.toString(), "-e",
                    String.format("do shell script \"installer -pkg '%s' -target /\" with prompt \"正在安装 Terracotta，需要管理员权限\" with administrator privileges",
                            movedInstaller)
            );
            Process process = pb.inheritIO().start();
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Terracotta installation was interrupted", e);
            }

            if (exitCode != 0) {
                throw new IOException(String.format(
                        "Cannot install Terracotta %s: system installer exited with code %d",
                        movedInstaller, exitCode
                ));
            }
        } finally {
            try {
                deleteDirectory(tempDir);
            } catch (IOException e) {
                System.err.println("Warning: Cannot remove temporary Terracotta package file: " + e.getMessage());
            }
        }
    }

    @Override
    public List<String> ofCommandLine(Path path) {
        return List.of(executable.toString(), "--hmcl", path.toString());
    }

    private static Path findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        for (String dir : pathEnv.split(System.getProperty("path.separator"))) {
            Path p = Path.of(dir, name);
            if (Files.isExecutable(p)) return p.toAbsolutePath();
        }
        return null;
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) {
                    deleteDirectory(entry);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}

/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import org.starlight.terracotta.provider.AbstractTerracottaProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public final class TerracottaLifecycle {

    private static final AtomicReference<Process> RUNNING_PROCESS = new AtomicReference<>();

    private TerracottaLifecycle() {}

    public static void launch(TerracottaState.Launching state, boolean removeLegacy) {
        AbstractTerracottaProvider provider = TerracottaMetadata.PROVIDER;
        if (provider == null) {
            Platform.runLater(() ->
                    TerracottaManager.tryTransition(state,
                            new TerracottaState.Fatal(TerracottaState.Fatal.Type.OS))
            );
            return;
        }
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("starlight-terracotta-" + ThreadLocalRandom.current().nextLong());
        } catch (IOException e) {
            Platform.runLater(() ->
                    TerracottaManager.tryTransition(state,
                            new TerracottaState.Fatal(TerracottaState.Fatal.Type.TERRACOTTA))
            );
            return;
        }
        Path portFile = tempDir.resolve("http").toAbsolutePath();
        Thread worker = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(provider.ofCommandLine(portFile));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                RUNNING_PROCESS.set(process);

                int port = waitForPortFile(portFile, process);
                if (port <= 0) {
                    throw new IllegalStateException("Failed to detect Terracotta port, exit=" + process.exitValue());
                }

                Platform.runLater(() -> {
                    TerracottaState next = new TerracottaState.Unknown(port);
                    if (removeLegacy) {
                        TerracottaMetadata.removeLegacyVersionFiles();
                    }
                    TerracottaManager.tryTransition(state, next);
                    TerracottaStatePoller.setActive(true);
                });
            } catch (Exception e) {
                System.err.println("Terracotta launch failed: " + e.getMessage());
                Platform.runLater(() ->
                        TerracottaManager.tryTransition(state,
                                new TerracottaState.Fatal(TerracottaState.Fatal.Type.TERRACOTTA))
                );
            }
        }, "Terracotta-Launch");
        worker.setDaemon(true);
        worker.start();
    }

    private static int waitForPortFile(Path portFile, Process process) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        long interval = 50;
        long exitCheckStart = -1;

        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(portFile)) {
                try {
                    String content = Files.readString(portFile);
                    JsonObject obj = new Gson().fromJson(content, JsonObject.class);
                    if (obj != null && obj.has("port")) {
                        int port = obj.get("port").getAsInt();
                        if (port > 0) return port;
                    }
                } catch (Exception ignored) {
                    // file may still being written, retry
                }
            }
            if (!process.isAlive()) {
                if (exitCheckStart == -1) {
                    exitCheckStart = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - exitCheckStart >= 2000) {
                    return -1;
                }
            } else {
                exitCheckStart = -1;
            }
            Thread.sleep(Math.min(interval, deadline - System.currentTimeMillis()));
            interval = Math.min(interval + 30, 500);
        }
        return -1;
    }

    public static boolean isRunning() {
        Process p = RUNNING_PROCESS.get();
        return p != null && p.isAlive();
    }

    public static void stop() {
        Process p = RUNNING_PROCESS.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
}

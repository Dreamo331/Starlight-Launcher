/* Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import org.starlight.terracotta.provider.AbstractTerracottaProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CancellationException;

public final class TerracottaDownloader {

    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home", "."),
            ".starlight", "terracotta-cache"
    );

    private TerracottaDownloader() {}

    static void startDownload(AbstractTerracottaProvider provider) {
        TerracottaState.Preparing prep = findPreparing();
        if (prep == null) return;

        Thread worker = new Thread(() -> {
            try {
                Files.createDirectories(CACHE_DIR);
                Path cached = findCachedPackage();
                Path pkg;

                if (cached != null && Files.size(cached) > 0) {
                    pkg = cached;
                } else {
                    pkg = provider.download(prep);
                    if (pkg != null) cachePackage(pkg);
                }

                if (prep.requestInstallFence() && pkg != null) {
                    provider.install(pkg);
                }

                Platform.runLater(() -> {
                    TerracottaState.Launching launching = new TerracottaState.Launching();
                    if (TerracottaManager.tryTransition(prep, launching)) {
                        TerracottaLifecycle.launch(launching, true);
                    }
                });
            } catch (CancellationException | IOException e) {
                // 本地安装取消了下载流�?            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                System.err.println("Terracotta download/install failed:");
                System.err.println(sw);
                Platform.runLater(() ->
                        TerracottaManager.tryTransition(prep,
                                new TerracottaState.Fatal(TerracottaState.Fatal.Type.DOWNLOAD)));
            }
        }, "Terracotta-Download");
        worker.setDaemon(true);
        worker.start();
    }

    static void startLocalInstall(AbstractTerracottaProvider provider, Path bundle) {
        TerracottaState.Preparing prep = findPreparing();
        if (prep == null) return;

        Thread worker = new Thread(() -> {
            try {
                provider.install(bundle);
                Platform.runLater(() -> {
                    TerracottaState.Launching launching = new TerracottaState.Launching();
                    if (TerracottaManager.tryTransition(prep, launching)) {
                        TerracottaLifecycle.launch(launching, true);
                    }
                });
            } catch (Exception e) {
                System.err.println("Local install failed: " + e.getMessage());
                Platform.runLater(() ->
                        TerracottaManager.tryTransition(prep,
                                new TerracottaState.Fatal(TerracottaState.Fatal.Type.INSTALL)));
            }
        }, "Terracotta-Install");
        worker.setDaemon(true);
        worker.start();
    }

    private static TerracottaState.Preparing findPreparing() {
        TerracottaState s = TerracottaStatePoller.getState();
        if (s instanceof TerracottaState.Preparing p) return p;
        return null;
    }

    static Path findCachedPackage() throws IOException {
        if (!Files.isDirectory(CACHE_DIR)) return null;
        String hash = TerracottaMetadata.getPackageHash();
        if (hash == null) return null;
        String cacheName = hashToFilename(hash) + ".tar.gz";
        Path cached = CACHE_DIR.resolve(cacheName);
        if (Files.exists(cached) && Files.size(cached) > 0) return cached;
        return null;
    }

    static void cachePackage(Path pkg) throws IOException {
        String hash = TerracottaMetadata.getPackageHash();
        if (hash == null) return;
        Files.createDirectories(CACHE_DIR);
        String cacheName = hashToFilename(hash) + ".tar.gz";
        Path cached = CACHE_DIR.resolve(cacheName);
        Files.copy(pkg, cached, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hashToFilename(String hash) {
        return hash.length() > 32 ? hash.substring(0, 32) : hash;
    }

    static Path cacheDir() {
        return CACHE_DIR;
    }
}

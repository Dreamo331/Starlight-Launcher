/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta.provider;

import com.example.starlight.newui.AppConfig;

import javafx.beans.value.ObservableDoubleValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TerracottaBundleStub {
    public record IntegrityCheck(String algorithm, String checksum) {
        public IntegrityCheck {
            if (algorithm == null || algorithm.isBlank())
                algorithm = "SHA-512";
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String USER_AGENT = AppConfig.USER_AGENT;

    private final Path root;
    private final List<URI> links;
    private final IntegrityCheck hash;
    private final Map<String, IntegrityCheck> files;

    public TerracottaBundleStub(Path root, List<URI> links, IntegrityCheck hash, Map<String, IntegrityCheck> files) {
        this.root = root;
        this.links = links;
        this.hash = hash;
        this.files = files;
    }

    /** 并行下载：同时向多个源发起请求，哪个先成功用哪个 */
    public Path download(AbstractTerracottaProvider.DownloadContext context) throws IOException {
        if (links.isEmpty()) throw new IOException("No download sources available");

        if (links.size() == 1) {
            return downloadSingle(links.get(0), context);
        }

        List<CompletableFuture<Path>> futures = new ArrayList<>();
        AtomicReference<Path> winner = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean(false);

        for (URI url : links) {
            CompletableFuture<Path> f = CompletableFuture.supplyAsync(() -> {
                if (done.get()) return null;
                try {
                    Path tmp = Files.createTempFile("terracotta-", ".tar.gz");
                    downloadTo(url, tmp, context);
                    if (winner.compareAndSet(null, tmp)) {
                        done.set(true);
                        return tmp;
                    }
                    // Another source already won, clean up this temp file
                    Files.deleteIfExists(tmp);
                    return null;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });
            futures.add(f);
        }

        try {
            CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                    .get(120, TimeUnit.SECONDS);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("All download sources failed", e);
        }

        Path result = winner.get();
        if (result == null) throw new IOException("All download sources failed");
        return result;
    }

    private Path downloadSingle(URI url, AbstractTerracottaProvider.DownloadContext context) throws IOException {
        Path tmp = Files.createTempFile("terracotta-", ".tar.gz");
        try {
            downloadTo(url, tmp, context);
            return tmp;
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private void downloadTo(URI url, Path target, AbstractTerracottaProvider.DownloadContext ctx) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(url)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        try {
            HttpResponse<Path> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(target));
            if (resp.statusCode() != 200) {
                throw new IOException(url + " returned HTTP " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            if (ctx != null) ctx.checkCancellation();
        }
    }

    public void install(Path pkg) throws IOException {
        Files.createDirectories(root);
        cleanDirectory(root);

        try (var tarIn = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
                new org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(
                        Files.newInputStream(pkg)))) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/")) name = name.substring(1);

                IntegrityCheck check = files.get(name);
                if (check == null) continue;

                Path targetPath = root.resolve(name);
                Files.createDirectories(targetPath.getParent());

                MessageDigest digest = getDigest(check.algorithm());
                try (OutputStream os = new DigestOutputStream(Files.newOutputStream(targetPath), digest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = tarIn.read(buf)) >= 0) os.write(buf, 0, n);
                }

                String actual = HexFormat.of().formatHex(digest.digest());
                if (!check.checksum().equalsIgnoreCase(actual)) {
                    throw new IOException("Checksum mismatch for " + name
                            + ": expected " + check.checksum() + ", got " + actual);
                }

                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
                    if (!targetPath.toFile().setExecutable(true))
                        System.err.println("Warning: could not set executable on " + targetPath);
                }
            }
        } catch (Exception e) {
            deleteDirectory(root);
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Failed to install terracotta bundle", e);
        }
    }

    public Path locate(String file) {
        if (!files.containsKey(file)) {
            throw new AssertionError("Expecting " + file + " in terracotta bundle.");
        }
        return root.resolve(file).toAbsolutePath();
    }

    public AbstractTerracottaProvider.Status status() throws IOException {
        if (Files.exists(root) && isLocalBundleValid()) {
            return AbstractTerracottaProvider.Status.READY;
        }
        return AbstractTerracottaProvider.Status.NOT_EXIST;
    }

    private boolean isLocalBundleValid() throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        for (Map.Entry<String, IntegrityCheck> e : files.entrySet()) {
            Path p = root.resolve(e.getKey());
            IntegrityCheck check = e.getValue();
            if (!Files.isReadable(p)) return false;

            MessageDigest d = getDigest(check.algorithm());
            try (InputStream is = new DigestInputStream(Files.newInputStream(p), d)) {
                int n;
                while ((n = is.read(buf)) >= 0) {
                    total += n;
                    if (total >= 50 * 1024 * 1024) return false;
                }
            }
            if (!HexFormat.of().formatHex(d.digest()).equalsIgnoreCase(check.checksum())) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest getDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unsupported hash: " + algorithm, e);
        }
    }

    private static void cleanDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) deleteDirectory(entry);
            }
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path entry : stream) deleteDirectory(entry);
            }
        }
        Files.deleteIfExists(dir);
    }
}

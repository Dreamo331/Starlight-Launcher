/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import org.starlight.terracotta.provider.AbstractTerracottaProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class TerracottaManager {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private TerracottaManager() {}

    static {
        Thread init = new Thread(() -> {
            try {
                if (TerracottaMetadata.PROVIDER == null) {
                    TerracottaStatePoller.setState(new TerracottaState.Fatal(TerracottaState.Fatal.Type.OS));
                    return;
                }
                switch (TerracottaMetadata.PROVIDER.status()) {
                    case NOT_EXIST ->
                            TerracottaStatePoller.setState(new TerracottaState.Uninitialized(false));
                    case LEGACY_VERSION ->
                            TerracottaStatePoller.setState(new TerracottaState.Uninitialized(true));
                    case READY -> {
                        TerracottaState.Launching launching = new TerracottaState.Launching();
                        TerracottaStatePoller.setState(launching);
                        TerracottaLifecycle.launch(launching, false);
                    }
                }
            } catch (Exception e) {
                System.err.println("Terracotta init failed: " + e.getMessage());
                TerracottaStatePoller.setState(new TerracottaState.Fatal(TerracottaState.Fatal.Type.UNKNOWN));
            }
        }, "Terracotta-Init");
        init.setDaemon(true);
        init.start();
    }

    public static ReadOnlyObjectProperty<TerracottaState> stateProperty() {
        return TerracottaStatePoller.stateProperty();
    }

    public static boolean isInvalidBundle(Path file) {
        String name = file.getFileName().toString();
        return !name.equalsIgnoreCase(TerracottaMetadata.PACKAGE_NAME);
    }

    public static TerracottaState.Preparing download() {
        if (!Platform.isFxApplicationThread()) return null;
        TerracottaState s = TerracottaStatePoller.getState();
        if (!(s instanceof TerracottaState.Uninitialized
                || s instanceof TerracottaState.Fatal f && f.isRecoverable()))
            return null;
        if (TerracottaMetadata.PROVIDER == null) return null;

        TerracottaState.Preparing prep = new TerracottaState.Preparing(new ReadOnlyDoubleWrapper(-1), true);
        if (!tryTransition(s, prep)) return null;

        TerracottaDownloader.startDownload(TerracottaMetadata.PROVIDER);
        return prep;
    }

    public static TerracottaState.Preparing install(Path bundle) {
        if (!Platform.isFxApplicationThread()) return null;
        if (isInvalidBundle(bundle)) return null;
        if (TerracottaMetadata.PROVIDER == null) return null;

        TerracottaState s = TerracottaStatePoller.getState();
        TerracottaState.Preparing prep;
        if (s instanceof TerracottaState.Preparing p && p.requestInstallFence()) {
            prep = p;
        } else if (s instanceof TerracottaState.Uninitialized
                || s instanceof TerracottaState.Fatal f && f.isRecoverable()) {
            prep = new TerracottaState.Preparing(new ReadOnlyDoubleWrapper(-1), false);
            if (!tryTransition(s, prep)) return null;
        } else {
            return null;
        }

        TerracottaDownloader.startLocalInstall(TerracottaMetadata.PROVIDER, bundle);
        return prep;
    }

    public static TerracottaState recover() {
        if (!Platform.isFxApplicationThread()) return null;
        TerracottaState s = TerracottaStatePoller.getState();
        if (!(s instanceof TerracottaState.Fatal f && f.isRecoverable())) return null;
        if (TerracottaMetadata.PROVIDER == null) {
            return setState(new TerracottaState.Fatal(TerracottaState.Fatal.Type.OS));
        }
        try {
            return switch (TerracottaMetadata.PROVIDER.status()) {
                case NOT_EXIST, LEGACY_VERSION -> download();
                case READY -> {
                    TerracottaState.Launching l = setState(new TerracottaState.Launching());
                    TerracottaLifecycle.launch(l, false);
                    yield l;
                }
            };
        } catch (IOException e) {
            return setState(new TerracottaState.Fatal(TerracottaState.Fatal.Type.UNKNOWN));
        }
    }

    public static String exportLogs() throws IOException {
        TerracottaState s = TerracottaStatePoller.getState();
        if (s instanceof TerracottaState.PortSpecific ps) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + ps.port + "/log?fetch=true"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            try {
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) return resp.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
        return null;
    }

    public static void switchDaemon(boolean active) {
        TerracottaStatePoller.setActive(active);
    }

    public static TerracottaState.Waiting setWaiting() {
        TerracottaState s = TerracottaStatePoller.getState();
        if (s instanceof TerracottaState.PortSpecific ps) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + ps.port + "/state/ide"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("setWaiting failed: " + e.getMessage());
            }
            return new TerracottaState.Waiting(-1, -1, null);
        }
        return null;
    }

    public static TerracottaState.HostScanning setScanning() {
        TerracottaState s = TerracottaStatePoller.getState();
        if (s instanceof TerracottaState.PortSpecific ps) {
            String player = URLEncoder.encode(getPlayerName(), StandardCharsets.UTF_8);
            List<URI> nodes = TerracottaNodeList.fetch();
            StringBuilder q = new StringBuilder("player=").append(player);
            for (URI n : nodes) q.append("&public_nodes=").append(URLEncoder.encode(n.toString(), StandardCharsets.UTF_8));

            String uri = "http://127.0.0.1:" + ps.port + "/state/scanning?" + q;
            sendGetAsync(uri, 5);
            return new TerracottaState.HostScanning(-1, -1, null);
        }
        return null;
    }

    public static void setGuesting(String room) {
        TerracottaState s = TerracottaStatePoller.getState();
        if (s instanceof TerracottaState.PortSpecific ps) {
            String player = URLEncoder.encode(getPlayerName(), StandardCharsets.UTF_8);
            String roomEnc = URLEncoder.encode(room, StandardCharsets.UTF_8);
            List<URI> nodes = TerracottaNodeList.fetch();
            StringBuilder q = new StringBuilder("room=").append(roomEnc).append("&player=").append(player);
            for (URI n : nodes) q.append("&public_nodes=").append(URLEncoder.encode(n.toString(), StandardCharsets.UTF_8));

            String uri = "http://127.0.0.1:" + ps.port + "/state/guesting?" + q;
            sendGetAsync(uri, 10);
            Platform.runLater(() ->
                    setState(new TerracottaState.GuestConnecting(-1, -1, null)));
        }
    }

    // ---- internal helpers ----

    public static boolean tryTransition(TerracottaState prev, TerracottaState next) {
        return TerracottaStatePoller.compareAndSet(prev, next);
    }

    private static <T extends TerracottaState> T setState(T value) {
        TerracottaStatePoller.setState(value);
        return value;
    }

    private static String getPlayerName() {
        return System.getProperty("user.name", "Player");
    }

    private static void sendGetAsync(String uri, int timeoutSec) {
        Thread t = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .GET().build();
                HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                System.err.println("HTTP call failed: " + e.getMessage());
            }
        }, "Terracotta-HTTP");
        t.setDaemon(true);
        t.start();
    }
}

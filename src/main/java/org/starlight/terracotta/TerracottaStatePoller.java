/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.starlight.terracotta.profile.TerracottaProfile;
import org.starlight.terracotta.profile.ProfileKind;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

public final class TerracottaStatePoller {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ReadOnlyObjectWrapper<TerracottaState> STATE =
            new ReadOnlyObjectWrapper<>(TerracottaState.Bootstrap.INSTANCE);

    private static final Thread DAEMON = new Thread(TerracottaStatePoller::runLoop, "Terracotta-Poller");
    private static volatile boolean active = false;

    static {
        DAEMON.setDaemon(true);
        DAEMON.start();
    }

    private TerracottaStatePoller() {}

    public static ReadOnlyObjectProperty<TerracottaState> stateProperty() {
        return STATE.getReadOnlyProperty();
    }

    public static void setActive(boolean on) {
        active = on;
        if (on) LockSupport.unpark(DAEMON);
    }

    static boolean compareAndSet(TerracottaState prev, TerracottaState next) {
        if (next == null) throw new AssertionError();
        if (prev == STATE.get()) {
            STATE.set(next);
            return true;
        }
        return false;
    }

    static void setState(TerracottaState state) {
        if (state == null) throw new AssertionError();
        STATE.set(state);
    }

    static TerracottaState getState() {
        return STATE.get();
    }

    private static void runLoop() {
        long activeNs = Duration.ofMillis(500).toNanos();
        long idleMs = 15_000;

        while (true) {
            if (active) {
                LockSupport.parkNanos(activeNs);
            } else {
                LockSupport.parkUntil(System.currentTimeMillis() + idleMs);
            }

            TerracottaState current = STATE.get();
            if (!(current instanceof TerracottaState.PortSpecific ps)) continue;

            int port = ps.port;
            int index = (current instanceof TerracottaState.Ready r) ? r.index : Integer.MIN_VALUE;

            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/state"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonObject json = new Gson().fromJson(resp.body(), JsonObject.class);
                    int newIndex = json.get("index").getAsInt();
                    if (newIndex > index) {
                        TerracottaState next = deserialize(port, newIndex, json);
                        if (next != null) {
                            STATE.set(next);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Terracotta poll error: " + e.getMessage());
            }
        }
    }

    private static TerracottaState deserialize(int port, int index, JsonObject json) {
        String state = json.get("state").getAsString();
        return switch (state) {
            case "waiting" -> new TerracottaState.Waiting(port, index, state);
            case "host-scanning" -> new TerracottaState.HostScanning(port, index, state);
            case "host-starting" -> new TerracottaState.HostStarting(port, index, state);
            case "host-ok" -> {
                String code = json.has("room") ? json.get("room").getAsString() : "";
                int pi = json.has("profile_index") ? json.get("profile_index").getAsInt() : -1;
                List<TerracottaProfile> profiles = parseProfiles(json);
                yield new TerracottaState.HostOK(port, index, state, code, pi, profiles);
            }
            case "guest-connecting" -> new TerracottaState.GuestConnecting(port, index, state);
            case "guest-starting" -> {
                String d = json.has("difficulty") ? json.get("difficulty").getAsString() : "UNKNOWN";
                TerracottaState.GuestStarting.Difficulty diff;
                try {
                    diff = TerracottaState.GuestStarting.Difficulty.valueOf(d.toUpperCase());
                } catch (Exception e) {
                    diff = TerracottaState.GuestStarting.Difficulty.UNKNOWN;
                }
                yield new TerracottaState.GuestStarting(port, index, state, diff);
            }
            case "guest-ok" -> {
                String url = json.has("url") ? json.get("url").getAsString() : "";
                int pi = json.has("profile_index") ? json.get("profile_index").getAsInt() : -1;
                List<TerracottaProfile> profiles = parseProfiles(json);
                yield new TerracottaState.GuestOK(port, index, state, url, pi, profiles);
            }
            case "exception" -> {
                int t = json.has("type") ? json.get("type").getAsInt() : 0;
                yield new TerracottaState.Exception(port, index, state, t);
            }
            default -> null;
        };
    }

    private static List<TerracottaProfile> parseProfiles(JsonObject json) {
        if (!json.has("profiles") || !json.get("profiles").isJsonArray()) {
            return List.of();
        }
        JsonArray arr = json.getAsJsonArray("profiles");
        List<TerracottaProfile> result = new java.util.ArrayList<>();
        for (JsonElement elem : arr) {
            if (!elem.isJsonObject()) continue;
            JsonObject obj = elem.getAsJsonObject();
            String machineId = obj.has("machine_id") ? obj.get("machine_id").getAsString() : "";
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String vendor = obj.has("vendor") ? obj.get("vendor").getAsString() : "";
            String kindStr = obj.has("kind") ? obj.get("kind").getAsString() : "LOCAL";
            ProfileKind kind;
            try { kind = ProfileKind.valueOf(kindStr.toUpperCase()); }
            catch (Exception e) { kind = ProfileKind.LOCAL; }
            result.add(TerracottaProfile.create(machineId, name, vendor, kind));
        }
        return List.copyOf(result);
    }
}

/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.Gson
 *  com.google.gson.GsonBuilder
 *  com.google.gson.reflect.TypeToken
 *  org.slf4j.LoggerFactory
 */
package com.example.starlight.service;

import com.example.starlight.model.LogUploadRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogUploadStore {
    private static final String STORE_FILE = com.example.starlight.config.AppDirs
            .uploadedLogsJson().toString();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<LogUploadRecord>>(){}.getType();
    private static final Object LOCK = new Object();

    private LogUploadStore() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static List<LogUploadRecord> load() {
        Object object = LOCK;
        synchronized (object) {
            Path p = Paths.get(STORE_FILE, new String[0]);
            if (!Files.exists(p, new LinkOption[0])) {
                return new ArrayList<LogUploadRecord>();
            }
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                List list = (List)GSON.fromJson(json, LIST_TYPE);
                return list != null ? new ArrayList<LogUploadRecord>(list) : new ArrayList();
            }
            catch (Throwable t) {
                try {
                    Files.move(p, p.resolveSibling("uploaded_logs.json.corrupt-" + System.currentTimeMillis()), new CopyOption[0]);
                }
                catch (IOException iOException) {
                    // empty catch block
                }
                return new ArrayList<LogUploadRecord>();
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void save(List<LogUploadRecord> records) {
        Object object = LOCK;
        synchronized (object) {
            Path p = Paths.get(STORE_FILE, new String[0]);
            try {
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent(), new FileAttribute[0]);
                }
                Files.write(p, GSON.toJson((Object)(records == null ? new ArrayList() : records)).getBytes(StandardCharsets.UTF_8), new OpenOption[0]);
            }
            catch (IOException e) {
                LoggerFactory.getLogger(LogUploadStore.class).warn("Failed to save uploaded log records: {}", (Object)e.getMessage());
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static LogUploadRecord add(LogUploadRecord record) {
        if (record == null) {
            return null;
        }
        Object object = LOCK;
        synchronized (object) {
            List<LogUploadRecord> list = LogUploadStore.load();
            list.removeIf(r -> r.id != null && record.id != null && r.id.equals(record.id));
            list.add(0, record);
            LogUploadStore.save(list);
        }
        return record;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean remove(String id) {
        if (id == null) {
            return false;
        }
        Object object = LOCK;
        synchronized (object) {
            List<LogUploadRecord> list = LogUploadStore.load();
            boolean removed = list.removeIf(r -> id.equals(r.id));
            if (removed) {
                LogUploadStore.save(list);
            }
            return removed;
        }
    }

    public static Path storePath() {
        return Paths.get(STORE_FILE, new String[0]).toAbsolutePath();
    }
}

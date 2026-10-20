/*
 * Decompiled with CFR 0.152.
 */
package com.example.starlight.model;

public class LogUploadRecord {
    public String id;
    public String url;
    public String token;
    public String name;
    public long time;
    public int fileCount;
    public long contentBytes;

    public LogUploadRecord() {
    }

    public LogUploadRecord(String id, String url, String token, String name, long time, int fileCount, long contentBytes) {
        this.id = id;
        this.url = url;
        this.token = token;
        this.name = name;
        this.time = time;
        this.fileCount = fileCount;
        this.contentBytes = contentBytes;
    }
}

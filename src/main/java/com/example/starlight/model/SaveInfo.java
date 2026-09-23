/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** 存档信息 */
public class SaveInfo {
    public final String worldName;
    public final String folderName;
    public final String path;
    public final String version;
    public SaveInfo(String worldName, String folderName, String path, String version) {
        this.worldName = worldName;
        this.folderName = folderName;
        this.path = path;
        this.version = version;
    }
}

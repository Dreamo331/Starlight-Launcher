/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** 配置文件信息 */
public class ConfigFileInfo {
    public final String name;
    public final String path;
    public final long size;
    public ConfigFileInfo(String name, String path, long size) {
        this.name = name;
        this.path = path;
        this.size = size;
    }
}

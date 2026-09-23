/* Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** 模组信息 */
public class ModInfo {
    public final String name;
    public final String path;
    public final boolean enabled;    // true=启用, false=禁用(.disabled)
    public final String origin;      // "[全局]" �?"[版本]"
    public ModInfo(String name, String path, boolean enabled, String origin) {
        this.name = name;
        this.path = path;
        this.enabled = enabled;
        this.origin = origin;
    }
}

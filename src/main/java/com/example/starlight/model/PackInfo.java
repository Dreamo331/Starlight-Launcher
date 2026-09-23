/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** 资源包/光影包信息 */
public class PackInfo {
    public final String name;
    public final String path;
    public final boolean active;     // 是否已启用
    public PackInfo(String name, String path, boolean active) {
        this.name = name;
        this.path = path;
        this.active = active;
    }
}

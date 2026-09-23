/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

import java.util.List;

/** 原生库状态 */
public class NativesStatus {
    public final boolean ready;
    public final int fileCount;
    public final List<String> fileNames;
    public NativesStatus(boolean ready, int fileCount, List<String> fileNames) {
        this.ready = ready;
        this.fileCount = fileCount;
        this.fileNames = fileNames;
    }
}

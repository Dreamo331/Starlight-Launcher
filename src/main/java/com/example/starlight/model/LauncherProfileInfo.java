/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

import java.util.Map;

/** 启动器档案信息 */
public class LauncherProfileInfo {
    public final String username;
    public final String displayName;
    public final int accountCount;
    public final Map<String, String> profiles;  // profile名 -> 版本ID
    public LauncherProfileInfo(String username, String displayName, int accountCount,
                                Map<String, String> profiles) {
        this.username = username;
        this.displayName = displayName;
        this.accountCount = accountCount;
        this.profiles = profiles;
    }
}

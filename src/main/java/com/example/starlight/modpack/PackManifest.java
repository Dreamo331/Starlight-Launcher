package com.example.starlight.modpack;

import java.util.List;

/** 整合包清单：从 Modrinth .mrpack 或 CurseForge zip 解析出的统一结构 */
public record PackManifest(String mcVersionRaw, String loaderType, String loaderVersion,
                           String overridesDir, List<PackFile> files) {
}

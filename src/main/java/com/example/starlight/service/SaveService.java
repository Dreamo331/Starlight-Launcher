/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.service;

import com.example.starlight.model.*;
import com.example.starlight.listsaves.listsaves;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 存档管理服务
 */
public class SaveService {

    /** 列出存档 */
    public static List<SaveInfo> listSaves(String gameDir, String versionName) {
        String savesDir = gameDir + "/saves";
        String verDir = gameDir + "/versions/" + versionName;
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            List<String> capturedLines = new ArrayList<>();
            PrintStream capturingPs = new PrintStream(new OutputStream() {
                final StringBuilder sb = new StringBuilder();
                @Override public void write(int b) {
                    sb.append((char) b);
                    if (b == '\n') { capturedLines.add(sb.toString().stripTrailing()); sb.setLength(0); }
                }
                @Override public void write(byte[] b, int off, int len) {
                    for (int i = off; i < off + len; i++) write(b[i]);
                }
            }, true, StandardCharsets.UTF_8);
            System.setOut(capturingPs);
            System.setErr(capturingPs);
            listsaves.main(new String[]{savesDir, verDir});
            System.out.flush();
            System.err.flush();
            return parseSavesFromOutput(capturedLines, versionName);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** 快速列出存档（自动读取配置）*/
    public static List<SaveInfo> listSaves() {
        Map<String, String> cfg = com.example.starlight.config.StarlightConfig.readConfig();
        String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
        String version = cfg.getOrDefault("Version", "");
        return version.isEmpty() ? Collections.emptyList() : listSaves(gameDir, version);
    }

    private static List<SaveInfo> parseSavesFromOutput(List<String> lines, String versionName) {
        List<SaveInfo> saves = new ArrayList<>();
        String currentVersion = versionName;
        String currentName = null;
        String currentPath = null;
        for (String line : lines) {
            if (line.startsWith("Game version:")) currentVersion = line.substring(line.indexOf(':') + 1).trim();
            else if (line.startsWith("Save name:")) currentName = line.substring(line.indexOf(':') + 1).trim();
            else if (line.startsWith("Save path:")) {
                currentPath = line.substring(line.indexOf(':') + 1).trim();
                if (currentName != null && currentPath != null) {
                    saves.add(new SaveInfo(currentName, new File(currentPath).getName(), currentPath, currentVersion));
                    currentName = null;
                }
            }
        }
        return saves;
    }
}

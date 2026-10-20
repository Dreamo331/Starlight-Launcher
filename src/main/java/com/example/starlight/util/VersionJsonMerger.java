/** (C) Copyright 2026 Starlight. All rights reserved. */
// 版本 JSON 父子合并工具 //
// 安装期（把原版吞进加载器版本，使其自包含）与启动期（解析 inheritsFrom 继承链）
// 共用同一套合并语义，避免两处各写一份、其中一处漏拼数组参数。
package com.example.starlight.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Minecraft 版本 JSON 的父子合并规则。
 *
 * <p>合并分两类，判据完全不同，混用会静默丢参数：
 * <ul>
 *   <li>标量键（assets / assetIndex / javaVersion 等）：子版本有该键就以子版本为准，
 *       没有才继承父版本。</li>
 *   <li>{@code arguments} 下的数组（game / jvm）：必须父在前、子在后拼接。
 *       加载器（Fabric 等）的 profile 自带一个 game 为空数组的 {@code arguments}，
 *       按标量判据处理会让原版的 --username / --gameDir 等参数全部消失。</li>
 * </ul>
 */
public final class VersionJsonMerger {

    /** 需要拼接的 arguments 数组节名 */
    private static final String[] ARGUMENT_SECTIONS = {"game", "jvm"};

    private VersionJsonMerger() {}

    /**
     * 子版本缺失该键时整体继承父版本的值，适用于标量键与旧版 {@code minecraftArguments}。
     * 父版本也没有的键保持缺失。
     */
    public static void inheritMissing(JsonObject child, JsonObject parent, String... keys) {
        for (String key : keys) {
            if (!child.has(key) && parent.has(key)) {
                child.add(key, parent.get(key));
            }
        }
    }

    /**
     * 拼接 {@code arguments.game} 与 {@code arguments.jvm}：父版本元素在前、子版本在后。
     *
     * <p>重复参数由游戏侧按后者覆盖，与官方启动器的处理方式一致。
     * 子版本没有 {@code arguments} 时直接沿用父版本的节点；父版本没有该节时保持子版本原样。
     */
    public static void mergeArguments(JsonObject child, JsonObject parent) {
        if (!parent.has("arguments") || !parent.get("arguments").isJsonObject()) return;
        if (!child.has("arguments")) {
            child.add("arguments", parent.get("arguments"));
            return;
        }
        if (!child.get("arguments").isJsonObject()) return;

        JsonObject childArgs = child.getAsJsonObject("arguments");
        JsonObject parentArgs = parent.getAsJsonObject("arguments");
        for (String section : ARGUMENT_SECTIONS) {
            if (!parentArgs.has(section) || !parentArgs.get(section).isJsonArray()) continue;
            JsonArray merged = new JsonArray();
            for (JsonElement elem : parentArgs.getAsJsonArray(section)) {
                merged.add(elem);
            }
            if (childArgs.has(section) && childArgs.get(section).isJsonArray()) {
                for (JsonElement elem : childArgs.getAsJsonArray(section)) {
                    merged.add(elem);
                }
            }
            childArgs.add(section, merged);
        }
    }
}

package com.example.starlight.util;

import com.google.gson.JsonObject;
import com.startgame.LaunchInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 老版本（1.7.x / 1.12.x）游戏参数的补齐。
 *
 * <p>核心 jar 的 {@code BaseLauncher.buildGameArgs} 会丢掉 {@code --userProperties}
 * 这一对参数：版本 JSON 的 {@code minecraftArguments} 里明明写着
 * {@code --userProperties ${user_properties}}（替换表里也有 {@code ${user_properties} → {}}），
 * 但构建出来的参数列表里就是没有它。1.7.10 / 1.12.2 的
 * {@code net.minecraft.client.main.Main} 把该参数声明为必填，于是启动到最后一步抛
 * {@code joptsimple.MissingRequiredOptionException: Missing required option(s) ['userProperties']}，
 * 表现为游戏窗口一闪而过、日志只剩一句 {@code Exception in thread "main"}。
 *
 * <p>本工程改不到核心 jar（见项目备忘），因此在这里按需把参数补进 {@link LaunchInfo#getGameArgs()}；
 * 实测补上后启动参数列表里就有了该项，其余参数不受影响。
 */
public final class LegacyLaunchArgs {

    private LegacyLaunchArgs() {
    }

    /**
     * 若版本 JSON 声明了 {@code --userProperties} 而核心 jar 会漏掉它，就把
     * {@code --userProperties {}} 追加到 {@code info} 的额外游戏参数里（原地修改）。
     *
     * @return 实际追加的参数；无需补充时返回空列表
     */
    public static List<String> apply(LaunchInfo info, JsonObject versionJson) {
        List<String> extra = requiredGameArgs(versionJson);
        if (info == null || extra.isEmpty()) return List.of();
        List<String> merged = new ArrayList<>();
        List<String> existing = info.getGameArgs();
        if (existing != null) merged.addAll(existing);
        // 用户自定义参数里已经写过就不重复补，避免同名选项出现两次
        if (!containsOption(existing, extra.get(0))) {
            merged.addAll(extra);
        } else {
            return List.of();
        }
        info.setGameArgs(merged);
        return extra;
    }

    /** 需要补充的游戏参数（当前只有 {@code --userProperties}） */
    public static List<String> requiredGameArgs(JsonObject versionJson) {
        if (versionJson == null || !versionJson.has("minecraftArguments")) return List.of();
        var element = versionJson.get("minecraftArguments");
        if (element == null || !element.isJsonPrimitive()) return List.of();
        // 老格式的 minecraftArguments 里声明了该参数才需要补；1.13+ 走 arguments 数组，不受影响
        return element.getAsString().contains("--userProperties")
                ? List.of("--userProperties", "{}")
                : List.of();
    }

    /** 参数列表中是否已有该选项（含 {@code --opt=value} 写法） */
    private static boolean containsOption(List<String> args, String option) {
        if (args == null || option == null) return false;
        for (String arg : args) {
            if (arg != null && (arg.equals(option) || arg.startsWith(option + "="))) return true;
        }
        return false;
    }
}

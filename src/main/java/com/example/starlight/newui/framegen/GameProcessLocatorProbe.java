/**
 * 验证 {@link GameProcessLocator} 的解析行为。
 *
 * 这个探针专门回答一个问题：开发者页点「开始采样」时，
 * 到底能不能拿到 PID，拿不到是因为什么。
 *
 * 用法：
 *   java -cp target\classes com.example.starlight.newui.framegen.GameProcessLocatorProbe
 */
package com.example.starlight.newui.framegen;

import com.example.starlight.service.GameInstanceRegistry;

import java.util.List;

public final class GameProcessLocatorProbe {

    public static void main(String[] args) {
        System.out.println("=== GameProcessLocator 探针 ===\n");

        System.out.println("[1] GameInstanceRegistry");
        List<GameInstanceRegistry.Instance> alive = GameInstanceRegistry.aliveInstances();
        System.out.println("    存活实例: " + alive.size());
        for (GameInstanceRegistry.Instance ins : alive) {
            System.out.println("      pid=" + ins.pid + " " + ins.displayName());
        }

        System.out.println("\n[2] 命令行特征判定自检");
        System.out.println("    minecraft 主类  -> "
                + GameProcessLocator.looksLikeMinecraft(
                        "javaw.exe -cp ... net.minecraft.client.main.Main --gameDir ."));
        System.out.println("    fabric 加载器  -> "
                + GameProcessLocator.looksLikeMinecraft(
                        "javaw.exe net.fabricmc.loader.impl.launch.knot.KnotClient"));
        System.out.println("    IDE 进程       -> "
                + GameProcessLocator.looksLikeMinecraft(
                        "java.exe -Didea.launcher.port=7532 com.intellij.idea.Main"));
        System.out.println("    空命令行       -> "
                + GameProcessLocator.looksLikeMinecraft(""));

        System.out.println("\n[3] locate() 结果");
        GameProcessLocator.Target t = GameProcessLocator.locate();
        System.out.println("    有效: " + t.isValid());
        System.out.println("    pid : " + t.pid());
        System.out.println("    描述: " + t.description());
        System.out.println("    来源: " + t.source());

        System.out.println("\n[4] 结论");
        if (t.isValid()) {
            System.out.println("    ✔ 能拿到 PID，开发者页点「开始采样」会正常启动");
        } else {
            System.out.println("    ✘ 拿不到 PID：" + t.description());
            System.out.println("      → 需要先启动 Minecraft（窗口化），或从注册表/进程扫描能识别的入口拉起");
        }
    }
}

package com.example.starlight.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行中的游戏实例注册表：记录本启动器拉起的每个 Minecraft 进程。
 *
 * <p>两条启动路径都在 {@code BaseLauncher} 的 {@code onProcessStarted} 回调里登记，
 * 进程退出时注销：新版 UI 走 {@code gui.UIGeneralControlClass}，插件 API 走
 * {@link GameLauncherService}。「关闭游戏进程」弹窗据此列出全部实例，支持逐个或批量结束。
 *
 * <p>只登记本启动器拉起的进程 —— 系统里其它 java 进程既拿不到版本信息（Windows 上
 * {@code ProcessHandle.commandLine()} 对别的进程为空），误关风险也高。
 */
public final class GameInstanceRegistry {

    /** 一个正在运行的游戏实例 */
    public static final class Instance {
        public final long pid;
        public final String version;
        public final String gameDir;
        public final String userName;
        /** 加载器名（Vanilla / Forge / Fabric ...），拿不到时为空串 */
        public final String loader;
        public final long startTime;

        /** 是否被用户从关闭弹窗主动结束：用于抑制「游戏崩溃诊断」面板的误报 */
        private volatile boolean stoppedByUser;

        public Instance(long pid, String version, String gameDir, String userName,
                        String loader, long startTime) {
            this.pid = pid;
            this.version = version == null ? "" : version;
            this.gameDir = gameDir == null ? "" : gameDir;
            this.userName = userName == null ? "" : userName;
            this.loader = loader == null ? "" : loader;
            this.startTime = startTime;
        }

        /** 列表里显示的名称 */
        public String displayName() {
            return version.isBlank() ? "Minecraft（未知版本）" : "Minecraft " + version;
        }

        /** 已运行时长（毫秒） */
        public long uptimeMillis() {
            return Math.max(0L, System.currentTimeMillis() - startTime);
        }

        public boolean isStoppedByUser() {
            return stoppedByUser;
        }

        /** 标记为「用户主动关闭」（关闭弹窗在杀进程前调用，用来抑制崩溃诊断面板） */
        public void markStoppedByUser() {
            stoppedByUser = true;
        }
    }

    private static final Map<Long, Instance> INSTANCES = new ConcurrentHashMap<>();

    private GameInstanceRegistry() {
    }

    /** 进程启动时登记（在 onProcessStarted 回调里调用） */
    public static void register(Instance instance) {
        if (instance != null) INSTANCES.put(instance.pid, instance);
    }

    /** 进程退出 / 启动失败时注销 */
    public static void unregister(long pid) {
        INSTANCES.remove(pid);
    }

    /**
     * 仍在运行的实例，按启动时间倒序（最近启动的排最前）。
     *
     * <p>顺带清掉已经死掉的条目：外部把进程结束后，启动流程的注销回调可能还没跑到。
     */
    public static List<Instance> aliveInstances() {
        List<Instance> result = new ArrayList<>();
        for (Map.Entry<Long, Instance> entry : INSTANCES.entrySet()) {
            if (isAlive(entry.getKey())) {
                result.add(entry.getValue());
            } else {
                INSTANCES.remove(entry.getKey(), entry.getValue());
            }
        }
        result.sort(Comparator.comparingLong((Instance i) -> i.startTime).reversed());
        return result;
    }

    /** 是否有本启动器拉起的游戏在运行（悬浮按钮据此显隐） */
    public static boolean hasRunning() {
        for (Long pid : INSTANCES.keySet()) {
            if (isAlive(pid)) return true;
        }
        return false;
    }

    /**
     * 结束给定实例：先收子进程再杀主进程，并标记「用户主动关闭」。
     *
     * <p>标记打在实例对象本身上 —— 启动流程的闭包持有同一个对象，进程退出时会读它来决定
     * 要不要弹崩溃诊断面板。
     */
    public static void kill(Collection<Instance> targets) {
        if (targets == null) return;
        for (Instance instance : targets) {
            instance.stoppedByUser = true;
            killPid(instance.pid);
        }
    }

    /** 结束全部正在运行的实例 */
    public static void killAll() {
        kill(aliveInstances());
    }

    /** 结束指定 PID 的进程（含其拉起的子进程） */
    public static void killPid(long pid) {
        ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(handle -> {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        });
    }

    /** 按 PID 取实例；不在注册表里（已退出）返回 null */
    public static Instance find(long pid) {
        return INSTANCES.get(pid);
    }

    public static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}

package com.example.starlight.newui.framegen;

import com.example.starlight.service.GameInstanceRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 解析「当前要接管的 Minecraft 进程」。
 *
 * <p><b>为什么单独抽一个类</b>：上一版把取 PID 的逻辑直接写在
 * {@code DevPage.currentGamePid()} 里，读的是配置键
 * {@code GamePid / LastGamePid} —— 但启动器<b>从不写这些键</b>，
 * 于是恒返回 -1，开发者页的图表永远拿不到数据。
 * 这是「数据源找错了」而不是「链路坏了」。
 *
 * <p>真实数据源按可信度排序：
 * <ol>
 *   <li>{@link GameInstanceRegistry#aliveInstances()} —— 本启动器拉起的实例，
 *       有权威 PID。这是主来源。</li>
 *   <li>WMI 命令行匹配 —— 覆盖「游戏不是本启动器拉起」的情况。</li>
 * </ol>
 *
 * <p><b>为什么不能只靠可执行文件名扫 javaw/java</b>：
 * 机器上通常同时跑着好几个 java.exe（IDE、工具、别的 Minecraft 启动器）。
 * 实测按「找到第一个 javaw 就用它」的逻辑会随手抓一个无关进程当捕获目标，
 * 结果是把别人的窗口插值到屏幕上——比没有数据更糟。
 * 所以这里必须读命令行，确认它真的是 Minecraft 客户端。
 */
public final class GameProcessLocator {

    /** 一次解析结果 */
    public record Target(int pid, String description, Source source) {
        public boolean isValid() {
            return pid > 0;
        }
    }

    public enum Source {
        /** 本启动器注册的实例 */
        REGISTRY,
        /** WMI 命令行扫描命中 */
        SCAN,
        /** 没找到 */
        NONE
    }

    /** Minecraft 客户端命令行里必然出现的特征（主类或启动器包装类） */
    private static final String[] GAME_MARKERS = {
            "net.minecraft.client.main.main",
            "net.minecraft.launchwrapper.launch",
            "net.fabricmc.loader.impl.launch.knot.knotclient",
            "org.multimc.main",
            "minecraft.client",
    };

    private GameProcessLocator() {
    }

    /**
     * 解析当前游戏进程。
     *
     * @return 永远不返 null；没找到时 {@link Target#isValid()} 为 false
     */
    public static Target locate() {
        final Target fromRegistry = fromRegistry();
        if (fromRegistry.isValid()) {
            return fromRegistry;
        }
        final Target fromWmi = fromWmi();
        if (fromWmi.isValid()) {
            return fromWmi;
        }
        return new Target(-1, "未找到 Minecraft 进程（启动器注册表与命令行扫描都没命中）",
                Source.NONE);
    }

    private static Target fromRegistry() {
        try {
            final List<GameInstanceRegistry.Instance> alive =
                    GameInstanceRegistry.aliveInstances();
            if (alive.isEmpty()) {
                return new Target(-1, "启动器注册表里没有运行中的实例", Source.NONE);
            }
            // 多个实例时取最近启动的那个（用户刚开的那局才是要测的）
            GameInstanceRegistry.Instance best = alive.get(0);
            for (GameInstanceRegistry.Instance ins : alive) {
                if (ins.startTime > best.startTime) {
                    best = ins;
                }
            }
            String desc = best.displayName();
            if (best.userName != null && !best.userName.isEmpty()) {
                desc += " · " + best.userName;
            }
            if (best.version != null && !best.version.isEmpty()) {
                desc += " · " + best.version;
            }
            return new Target((int) best.pid, desc, Source.REGISTRY);
        } catch (Throwable t) {
            System.err.println("[FrameGen] 读取 GameInstanceRegistry 失败: " + t);
            return new Target(-1, "读取注册表异常", Source.NONE);
        }
    }

    /**
     * 用 WMI 读其它 java 进程的命令行，靠特征串确认是 Minecraft 客户端。
     *
     * <p>为什么不用 {@link ProcessHandle.Info#commandLine()}：JDK 17 在 Windows
     * 上对非本进程通常返回空 Optional（需要额外权限）。工程里
     * {@code org.framegen.GameMonitor} 也踩过同一个坑，同样改用 WMI。
     *
     * <p><b>为什么用 -EncodedCommand 而不是 -Command</b>：
     * {@code ProcessBuilder} 把参数交给 Windows 重组命令行时，含空格与双引号的
     * 脚本会被拆散——PowerShell 收到的是裸 {@code -Filter Name=...}，
     * 直接抛 {@code PositionalParameterNotFound}，而 stderr 又被我们忽略，
     * 于是表现为「静默查不到任何进程」。实测 {@code -Command} 与
     * {@code -File} 两种方式在带引号脚本下都不可靠，只有 Base64 编码
     * （{@code -EncodedCommand}）能完整传递脚本，且不受引号影响。
     */
    private static Target fromWmi() {
        final String script = String.join("\n",
                "$ErrorActionPreference = \"SilentlyContinue\"",
                "Get-CimInstance Win32_Process |",
                "  Where-Object { $_.Name -eq \"javaw.exe\" -or $_.Name -eq \"java.exe\" } |",
                "  ForEach-Object { \"$($_.ProcessId)|$($_.CommandLine)\" }");

        final List<String> lines = runPowerShell(script);
        if (lines.isEmpty()) {
            return new Target(-1, "WMI 查询没有返回任何 java 进程", Source.NONE);
        }

        final long ownPid = ProcessHandle.current().pid();
        int bestPid = -1;
        String bestDesc = "";
        boolean haveWithGameDir = false;

        for (String line : lines) {
            final int bar = line.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            final String pidStr = line.substring(0, bar).trim();
            final String cmd = line.substring(bar + 1);
            final int pid;
            try {
                pid = Integer.parseInt(pidStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == ownPid || !looksLikeMinecraft(cmd)) {
                continue;
            }
            // 优先取带 --gameDir 的那个（最像客户端主进程）
            final boolean hasGameDir = cmd.contains("--gameDir");
            if (bestPid < 0 || (hasGameDir && !haveWithGameDir)) {
                bestPid = pid;
                bestDesc = "命令行命中 Minecraft 客户端 (PID " + pid + ")";
                haveWithGameDir = hasGameDir;
            }
        }

        if (bestPid < 0) {
            return new Target(-1,
                    "扫到 " + lines.size() + " 个 java 进程，但没有一个命令行像 Minecraft 客户端",
                    Source.NONE);
        }
        return new Target(bestPid, bestDesc, Source.SCAN);
    }

    /** 命令行是否像 Minecraft 客户端（包可见，便于测试） */
    static boolean looksLikeMinecraft(String cmd) {
        if (cmd == null || cmd.isEmpty()) {
            return false;
        }
        final String lower = cmd.toLowerCase(Locale.ROOT);
        for (String marker : GAME_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 跑一段 PowerShell 脚本，返回非空输出行；失败返回空列表 */
    private static List<String> runPowerShell(String script) {
        final List<String> out = new ArrayList<>();
        Process proc = null;
        try {
            // PowerShell 要求 UTF-16LE 的 Base64 脚本
            final String encoded = Base64.getEncoder().encodeToString(
                    script.getBytes(StandardCharsets.UTF_16LE));

            final ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);   // 合并 stderr，出错时能看清原因
            proc = pb.start();

            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        out.add(trimmed);
                    }
                }
            }

            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                System.err.println("[FrameGen] WMI 查询超时");
            }
        } catch (Throwable t) {
            System.err.println("[FrameGen] WMI 查询失败: " + t);
            if (proc != null) {
                proc.destroyForcibly();
            }
        }
        return out;
    }

    /**
     * 判断给定 PID 是否仍然存活。
     * 用于图表在游戏退出后自动停止采样。
     */
    public static boolean isAlive(int pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            final Optional<ProcessHandle> ph = ProcessHandle.of(pid);
            return ph.isPresent() && ph.get().isAlive();
        } catch (Throwable t) {
            return false;
        }
    }
}

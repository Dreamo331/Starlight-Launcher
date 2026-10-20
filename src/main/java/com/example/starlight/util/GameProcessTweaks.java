package com.example.starlight.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 游戏进程微调：自定义游戏窗口标题 + 进程优先级。
 *
 * <p>两项都是「启动后」的进程级副作用，统一在这里落地，由
 * {@code startmc.launcher.BaseLauncher#startProcessWithMonitoring} 在 {@code pb.start()}
 * 之后调用一次（新版 UI 与插件 API 两条启动路径都经过那里，不会漏）。
 *
 * <h3>窗口标题</h3>
 * Minecraft 官方没有任何「自定义窗口标题」的启动参数或系统属性：标题由游戏自己在
 * {@code Window.setTitle()} 里写死成 {@code Minecraft* <版本>}。所以这里走系统层面的做法 ——
 * 找到游戏进程的主窗口（GLFW 窗口类名以 {@code GLFW} 开头，或标题里含 {@code Minecraft}），
 * 直接改窗口文字；并在启动后的一段时间里反复重写，覆盖游戏稍后自行设置标题的时机。
 *
 * <p>窗口标题只在 Windows 上生效（其余系统没有稳定的无依赖 API，静默跳过）。
 * 另外：改了标题之后，「色盲辅助」与其它按窗口标题匹配的工具也要跟着改（默认匹配
 * {@code Minecraft}），否则会找不到游戏窗口。
 *
 * <h3>进程优先级</h3>
 * Windows 走 {@code SetPriorityClass}（JNA 直接调用，微秒级）；Linux / macOS 退化为
 * {@code renice}（负 nice 值需要权限，失败静默）。优先级只在进程活着时有效，游戏退出即失效。
 *
 * <p><b>GraalVM Native Image 兼容</b>：JNA/OSHI 在本项目的老式 native 编译流程下无法工作，
 * 触碰 JNA 类会打死调用线程（见 {@link NativeImageCompat}）。因此 native 模式下全部改走
 * PowerShell + Add-Type 的 P/Invoke 备用通道，所有 JNA 引用都收在 {@link Win32} 里，
 * 只在非 native 分支被触达。
 */
public final class GameProcessTweaks {

    private static final Logger LOG = LoggerFactory.getLogger(GameProcessTweaks.class);

    /** 配置键：自定义游戏窗口标题（空 = 不改，用游戏默认标题） */
    public static final String CONFIG_WINDOW_TITLE = "GameWindowTitle";

    /** 配置键：游戏进程优先级 */
    public static final String CONFIG_PRIORITY = "GameProcessPriority";

    /** 优先级默认值（不调整） */
    public static final String PRIORITY_NORMAL = "normal";

    /** 优先级选项：{值, 界面文案}（顺序即下拉框顺序） */
    public static final String[][] PRIORITY_OPTIONS = {
            {"low", "低"},
            {"below_normal", "低于正常"},
            {"normal", "正常（默认）"},
            {"above_normal", "高于正常"},
            {"high", "高"},
            {"realtime", "实时（可能影响系统响应，不建议）"}
    };

    /** 窗口标题长度上限：Windows 窗口标题本身也有长度限制，超长会被截断，这里主动截 */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 标题重写轮询次数与间隔：覆盖「游戏进程起来 → 创建窗口 → 设置标题」这段窗口期 */
    private static final int TITLE_WATCH_ROUNDS = 60;
    private static final long TITLE_WATCH_INTERVAL_MS = 500;

    private GameProcessTweaks() {
    }

    // 对外入口 //

    /**
     * 游戏进程启动后应用窗口标题与进程优先级（best-effort：任何失败只记日志，绝不影响启动）。
     *
     * @param pid            游戏进程 PID
     * @param rawTitle       自定义窗口标题，空/null = 保持游戏默认标题
     * @param rawPriority    优先级值（见 {@link #PRIORITY_OPTIONS}），空/null = 正常
     */
    public static void applyOnProcessStarted(long pid, String rawTitle, String rawPriority) {
        if (pid <= 0) return;
        String priority = normalizePriority(rawPriority);
        if (!PRIORITY_NORMAL.equals(priority)) {
            try {
                boolean ok = setPriority(pid, priority);
                LOG.info("游戏进程优先级设置{}：PID {} → {}", ok ? "成功" : "失败（可能权限不足）",
                        pid, priorityLabel(priority));
            } catch (Throwable t) {
                LOG.warn("设置游戏进程优先级异常：{}", t.getMessage());
            }
        }

        String title = normalizeTitle(rawTitle);
        if (title.isEmpty()) return;
        // 进程刚起来时窗口还没创建，交给监视线程在窗口出现后再改
        startTitleWatcher(pid, title);
    }

    /** 规范化窗口标题：去首尾空白与换行（ini 单行值），限长 */
    public static String normalizeTitle(String raw) {
        if (raw == null) return "";
        // 换行（含 \r\n）折成单个空格：ini 单行值不允许换行，窗口标题里出现裸 \r\n 也会被截断
        String t = raw.replaceAll("\\r\\n|\\r|\\n", " ").trim();
        if (t.length() > MAX_TITLE_LENGTH) t = t.substring(0, MAX_TITLE_LENGTH);
        return t;
    }

    // 窗口标题变量 //

    /** 版本名称变量：如 {@code Minecraft-{version}} → {@code Minecraft 1.20.1} */
    public static final String VAR_VERSION = "{version}";

    /** 玩家名变量：{@code {username}} → 当前登录玩家名 */
    public static final String VAR_USERNAME = "{username}";

    /** 所有支持的变量（UI 提示文案用） */
    public static final String[] TITLE_VARS = {VAR_VERSION, VAR_USERNAME};

    /**
     * 展开窗口标题里的变量（保存在 ini 里的是含变量的原始模板，展开只发生在运行期）。
     *
     * <p>支持的变量：
     * <ul>
     *   <li>{@code {version}} — 本次启动的游戏版本名（如 {@code 1.20.1-Forge_47.4.16}）</li>
     *   <li>{@code {username}} — 当前玩家名</li>
     * </ul>
     * 未知变量原样保留，方便发现问题；长度在展开后再截断，保证最终生效值不超限。
     *
     * @param template ini 里保存的标题模板（可为 null）
     * @param version  本次启动的游戏版本名（可为 null，此时 {version} 展开为空）
     * @param username 当前玩家名（可为 null，此时 {username} 展开为空）
     * @return 展开并规范化后的标题；模板为空时返回空串
     */
    public static String expandTitle(String template, String version, String username) {
        String t = normalizeTitle(template);
        if (t.isEmpty()) return "";
        // 值缺失（null/空）时把变量展开为空串而不是原样保留：版本名读不到时，
        // 标题停在「Minecraft-{version}」会让人以为变量不生效；未知变量（用户手打错的）
        // 仍原样保留，方便发现写法问题
        t = t.replace(VAR_VERSION, version == null ? "" : version)
             .replace(VAR_USERNAME, username == null ? "" : username);
        // 展开后可能超长，再截一次
        if (t.length() > MAX_TITLE_LENGTH) t = t.substring(0, MAX_TITLE_LENGTH);
        return t;
    }

    /** 规范化优先级值：无法识别时回落到「正常」 */
    public static String normalizePriority(String raw) {
        if (raw == null) return PRIORITY_NORMAL;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String[] option : PRIORITY_OPTIONS) {
            if (option[0].equals(v)) return v;
        }
        return PRIORITY_NORMAL;
    }

    /** 优先级值 → 界面文案 */
    public static String priorityLabel(String value) {
        String v = normalizePriority(value);
        for (String[] option : PRIORITY_OPTIONS) {
            if (option[0].equals(v)) return option[1];
        }
        return value;
    }

    /** 界面文案 → 优先级值（未知文案返回「正常」） */
    public static String priorityValueOf(String label) {
        if (label != null) {
            for (String[] option : PRIORITY_OPTIONS) {
                if (option[1].equals(label.trim())) return option[0];
            }
        }
        return PRIORITY_NORMAL;
    }

    // 窗口标题 //

    /**
     * 启动标题监视线程：游戏进程启动后的前 {@value #TITLE_WATCH_ROUNDS} ×
     * {@value #TITLE_WATCH_INTERVAL_MS} 毫秒内反复把主窗口标题改成自定义值。
     *
     * <p>反复写是因为游戏自己也会设置标题（窗口创建时一次，个别版本加载世界/断线后再设一次），
     * 只改一次会被覆盖回去；写同样的值没有副作用，代价可以忽略。
     */
    private static void startTitleWatcher(long pid, String title) {
        Thread t = new Thread(() -> {
            boolean logged = false;
            for (int i = 0; i < TITLE_WATCH_ROUNDS; i++) {
                if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return;
                try {
                    if (applyWindowTitle(pid, title)) {
                        // 只记一次：轮询期间反复重写同值属正常行为，全打出来会把启动日志灌满
                        if (!logged) {
                            logged = true;
                            LOG.info("游戏窗口标题已设置为：{}（PID {}）", title, pid);
                        }
                    }
                } catch (Throwable e) {
                    LOG.debug("设置游戏窗口标题失败：{}", e.getMessage());
                }
                try {
                    Thread.sleep(TITLE_WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "game-window-title");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把游戏进程主窗口的标题改成 {@code title}。
     *
     * @return 是否确实改到了一个窗口
     */
    public static boolean applyWindowTitle(long pid, String title) {
        if (pid <= 0) return false;
        String value = normalizeTitle(title);
        if (value.isEmpty()) return false;
        if (!isWindows()) return false;
        if (NativeImageCompat.isNativeImage()) {
            return applyWindowTitleViaPowerShell(pid, value);
        }
        return Win32.setWindowTitleForProcess(pid, value);
    }

    // 进程优先级 //

    /** 设置进程优先级。@return 是否成功 */
    public static boolean setPriority(long pid, String rawPriority) {
        if (pid <= 0) return false;
        String level = normalizePriority(rawPriority);
        if (PRIORITY_NORMAL.equals(level)) return true;
        if (isWindows()) {
            if (NativeImageCompat.isNativeImage()) return setPriorityViaPowerShell(pid, level);
            return Win32.setPriorityClass(pid, level);
        }
        return setPriorityViaRenice(pid, level);
    }

    /** Linux / macOS：renice（负值需要权限，失败返回 false） */
    private static boolean setPriorityViaRenice(long pid, String level) {
        int nice = switch (level) {
            case "low" -> 19;
            case "below_normal" -> 10;
            case "above_normal" -> -5;
            case "high" -> -10;
            case "realtime" -> -20;
            default -> 0;
        };
        if (nice == 0) return true;
        try {
            Process p = new ProcessBuilder("renice", "-n", String.valueOf(nice), "-p", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                while (in.read() != -1) {
                    // 读空输出，避免管道填满导致进程阻塞
                }
            }
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // PowerShell 备用通道（GraalVM Native Image / 无 JNA 环境） //

    /**
     * 用 PowerShell + Add-Type 调 Windows API 完成「设优先级 / 改窗口标题」。
     *
     * <p>与 JNA 版语义一致（OpenProcess + SetPriorityClass / EnumWindows + SetWindowTextW）。
     * 全部逻辑（含 EnumWindows 回调）都写在 C# 里：PowerShell 侧只调用一个静态方法，
     * 不做脚本块到委托的转换 —— 那条路径在 Windows PowerShell 5.1 下依赖变量作用域捕获，
     * 很容易静默失效（错误被 SilentlyContinue 吞掉后看起来「成功」但什么都没改）。
     *
     * <p>代价是每次要起一个隐藏的 powershell.exe（约 0.5~1 秒）——对启动时一次性的设置可接受。
     *
     * <p>判定结果靠解析 C# 打印的 {@code PRI=True|False} / {@code TITLE=True|False}，
     * 不看 powershell 退出码：{@code $ErrorActionPreference=SilentlyContinue} 下编译或调用失败
     * 也照样退 0，用退出码判成败会把「什么都没做」报成「做好了」。
     */
    private static boolean windowsApiViaPowerShell(long pid, int priorityClass, String title) {
        String script = POWER_SHELL_PREAMBLE
                + "[StarTweaks]::Run(" + pid + ", " + priorityClass + ", "
                + (title == null ? "$null" : ("'" + title.replace("'", "''") + "'")) + ")";
        try {
            // -EncodedCommand（Base64 + UTF-16LE）而不是 -Command：脚本里全是双引号，
            // 走 -Command 时命令行解析会先吃掉一层引号，Add-Type 拿到的源码变成
            // [DllImport(kernel32.dll, ...)]（编译错误），而 $ErrorActionPreference 又把错误
            // 吞掉，最后表现为「powershell 退 0、实际什么都没做」的假成功。
            // -EncodedCommand 只有一个 base64 token，不经过任何引号处理（PowerShell 3.0+，Win8 起内置）。
            String encoded = java.util.Base64.getEncoder().encodeToString(
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder(128);
            try (var in = p.getInputStream()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            String text = out.toString();
            if (!done) return false;
            // C# 侧打印 PRI=True/False 与 TITLE=True/False/SKIP（PowerShell 布尔拼串为 True/False），
            // 只校验本次真正请求的那一项
            if (priorityClass != 0 && !text.contains("PRI=True")) return false;
            if (title != null && !text.contains("TITLE=True")) return false;
            return true;
        } catch (Exception e) {
            LOG.debug("PowerShell 进程微调失败：{}", e.getMessage());
            return false;
        }
    }

    private static boolean setPriorityViaPowerShell(long pid, String level) {
        return windowsApiViaPowerShell(pid, priorityClassValue(level), null);
    }

    private static boolean applyWindowTitleViaPowerShell(long pid, String title) {
        return windowsApiViaPowerShell(pid, 0, title);
    }

    /**
     * Add-Type 的 C# 源码：与 {@link Win32} 的 JNA 调用语义一一对应。
     *
     * <p>{@code Run} 一次完成「设优先级 + 改窗口标题」，并把结果打印成 {@code RESULT=OK/PARTIAL/NONE}，
     * Java 侧据此判定，避免「powershell 退 0 但其实什么都没做」的假成功。
     */
    private static final String POWER_SHELL_PREAMBLE =
            "$ErrorActionPreference = 'SilentlyContinue';\n"
            + "$src = @'\n"
            + "using System;\n"
            + "using System.Runtime.InteropServices;\n"
            + "using System.Text;\n"
            + "public static class StarTweaks {\n"
            + "  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);\n"
            + "  [DllImport(\"kernel32.dll\", SetLastError=true)] public static extern IntPtr OpenProcess(int access, bool inherit, int pid);\n"
            + "  [DllImport(\"kernel32.dll\", SetLastError=true)] public static extern bool CloseHandle(IntPtr h);\n"
            + "  [DllImport(\"kernel32.dll\", SetLastError=true)] public static extern bool SetPriorityClass(IntPtr h, int cls);\n"
            + "  [DllImport(\"user32.dll\")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);\n"
            + "  [DllImport(\"user32.dll\")] public static extern int GetWindowThreadProcessId(IntPtr hWnd, out int pid);\n"
            + "  [DllImport(\"user32.dll\")] public static extern bool IsWindowVisible(IntPtr hWnd);\n"
            + "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder s, int max);\n"
            + "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern int GetClassNameW(IntPtr hWnd, StringBuilder s, int max);\n"
            + "  [DllImport(\"user32.dll\", CharSet=CharSet.Unicode)] public static extern bool SetWindowTextW(IntPtr hWnd, string s);\n"
            + "  static int _pid; static string _want; static bool _titleDone;\n"
            + "  static bool Cb(IntPtr h, IntPtr l) {\n"
            + "    int w; GetWindowThreadProcessId(h, out w);\n"
            + "    if (w != _pid) return true;\n"
            + "    if (!IsWindowVisible(h)) return true;\n"
            + "    StringBuilder t = new StringBuilder(512);\n"
            + "    GetWindowTextW(h, t, 512);\n"
            + "    string cur = t.ToString();\n"
            + "    if (cur.Length == 0) return true;\n"
            + "    StringBuilder c = new StringBuilder(256);\n"
            + "    GetClassNameW(h, c, 256);\n"
            + "    string cls = c.ToString().ToLower();\n"
            + "    bool game = cls.StartsWith(\"glfw\") || cur.Contains(\"Minecraft\") || cur == _want;\n"
            + "    if (!game) return true;\n"
            + "    if (cur == _want) { _titleDone = true; return false; }\n"
            + "    _titleDone = SetWindowTextW(h, _want);\n"
            + "    return false;\n"
            + "  }\n"
            + "  public static void Run(int pid, int cls, string want) {\n"
            + "    bool pri = false, tit = false;\n"
            + "    if (cls != 0) {\n"
            + "      IntPtr h = OpenProcess(0x200, false, pid);\n"
            + "      if (h == IntPtr.Zero) h = OpenProcess(0x1000, false, pid);\n"
            + "      if (h != IntPtr.Zero) { pri = SetPriorityClass(h, cls); CloseHandle(h); }\n"
            + "    }\n"
            + "    if (want != null && want.Length > 0) {\n"
            + "      _pid = pid; _want = want; _titleDone = false;\n"
            + "      EnumWindows(Cb, IntPtr.Zero);\n"
            + "      tit = _titleDone;\n"
            + "    }\n"
            + "    Console.WriteLine(\"PRI=\" + (cls != 0 ? pri.ToString() : \"SKIP\")\n"
            + "      + \" TITLE=\" + ((want != null && want.Length > 0) ? tit.ToString() : \"SKIP\"));\n"
            + "  }\n"
            + "}\n"
            + "'@;\n"
            + "Add-Type -TypeDefinition $src -Language CSharp;\n";

    // 平台判断 //

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 优先级值 → Windows SetPriorityClass 常量（0 = 不改） */
    private static int priorityClassValue(String level) {
        return switch (level) {
            case "low" -> 0x00000040;           // IDLE_PRIORITY_CLASS
            case "below_normal" -> 0x00004000;  // BELOW_NORMAL_PRIORITY_CLASS
            case "above_normal" -> 0x00008000;  // ABOVE_NORMAL_PRIORITY_CLASS
            case "high" -> 0x00000080;          // HIGH_PRIORITY_CLASS
            case "realtime" -> 0x00000100;      // REALTIME_PRIORITY_CLASS
            case "normal" -> 0x00000020;        // NORMAL_PRIORITY_CLASS
            default -> 0;
        };
    }

    /**
     * JNA 实现（仅 JVM 模式可达）。
     *
     * <p>单独放一个嵌套类：native 模式下 {@link NativeImageCompat#isNativeImage()} 分支
     * 直接返回，不会触达本类，JNA 的静态初始化也就不会发生（否则会打死调用线程）。
     */
    private static final class Win32 {

        /** PROCESS_SET_INFORMATION（改优先级）/ PROCESS_QUERY_LIMITED_INFORMATION（兜底打开） */
        private static final int PROCESS_SET_INFORMATION = 0x0200;
        private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

        private Win32() {
        }

        /** user32 里没有被 JNA 平台层封装的部分（SetWindowText 只在 JNA 的 User32 之外） */
        private interface User32Ext extends com.sun.jna.win32.StdCallLibrary {
            User32Ext INSTANCE = com.sun.jna.Native.load("user32", User32Ext.class,
                    com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);

            boolean SetWindowText(com.sun.jna.platform.win32.WinDef.HWND hWnd, String text);
        }

        /** 改指定进程的优先级。@return 是否成功 */
        static boolean setPriorityClass(long pid, String level) {
            int cls = priorityClassValue(level);
            if (cls == 0) return true;
            com.sun.jna.platform.win32.WinNT.HANDLE handle = null;
            try {
                com.sun.jna.platform.win32.Kernel32 k32 = com.sun.jna.platform.win32.Kernel32.INSTANCE;
                handle = k32.OpenProcess(PROCESS_SET_INFORMATION, false, (int) pid);
                if (handle == null) {
                    handle = k32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, (int) pid);
                }
                if (handle == null) return false;
                return k32.SetPriorityClass(handle, new com.sun.jna.platform.win32.WinDef.DWORD(cls));
            } catch (Throwable t) {
                return false;
            } finally {
                if (handle != null) {
                    try {
                        com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /**
         * 把指定进程的主窗口标题改成 {@code title}。
         *
         * <p>主窗口判定：可见、有标题，且「窗口类名以 GLFW 开头」或「标题里含 Minecraft」
         * 或「标题已经是我们要写的值」（重写时标题已被改过，必须能再次命中，
         * 否则第二次轮询就找不到窗口了）。
         */
        static boolean setWindowTitleForProcess(long pid, String title) {
            final int target = (int) pid;
            final boolean[] changed = {false};
            try {
                com.sun.jna.platform.win32.User32 u32 = com.sun.jna.platform.win32.User32.INSTANCE;
                com.sun.jna.platform.win32.WinUser.WNDENUMPROC callback = (hwnd, data) -> {
                    try {
                        com.sun.jna.ptr.IntByReference owner = new com.sun.jna.ptr.IntByReference();
                        u32.GetWindowThreadProcessId(hwnd, owner);
                        if (owner.getValue() != target) return true;
                        if (!u32.IsWindowVisible(hwnd)) return true;

                        char[] buf = new char[512];
                        int len = u32.GetWindowText(hwnd, buf, buf.length);
                        String current = len > 0 ? new String(buf, 0, len) : "";
                        if (current.isEmpty()) return true;

                        char[] classBuf = new char[256];
                        int classLen = u32.GetClassName(hwnd, classBuf, classBuf.length);
                        String className = classLen > 0 ? new String(classBuf, 0, classLen) : "";

                        boolean looksLikeGameWindow = className.toLowerCase(java.util.Locale.ROOT).startsWith("glfw")
                                || current.contains("Minecraft")
                                || current.equals(title);
                        if (!looksLikeGameWindow) return true;
                        if (current.equals(title)) {
                            changed[0] = true;
                            return false;
                        }

                        changed[0] = User32Ext.INSTANCE.SetWindowText(hwnd, title);
                        return false;   // 主窗口只可能有一个，命中即停
                    } catch (Throwable t) {
                        return true;
                    }
                };
                u32.EnumWindows(callback, null);
            } catch (Throwable t) {
                LOG.debug("枚举窗口失败：{}", t.getMessage());
                return false;
            }
            return changed[0];
        }
    }
}

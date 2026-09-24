package com.example.starlight;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.listjava.JavaCacheManager;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.main.ConsoleMode;
import com.example.starlight.newui.LauncherView;
import com.example.starlight.newui.SplashProgress;
import com.example.starlight.newui.SplashView;
import com.example.starlight.newui.ui.AppFonts;
import com.example.starlight.plugin.PluginManager;
import com.example.starlight.pluginapi.API;
import com.example.starlight.pluginapi.server.PluginApiServer;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.util.IcoImageLoader;
import com.example.starlight.util.SystemInfoMonitor;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class JavaFXLauncher extends Application {

    private static final Logger log = LoggerFactory.getLogger(JavaFXLauncher.class);

    private static final int EXPECTED_JAVA_VERSION = 17;
    private static final String EXPECTED_JAVAFX_VERSION_PREFIX = "17";

    /** 闪屏后台初始化最大等待时间（秒）：超时后强制进入主界面，避免后台任务卡死时动画无限加载 */
    private static final int SPLASH_MAX_WAIT_SECONDS = 15;

    private static Stage primaryStage;

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * 退出清理：启动器关窗走的是 {@code Platform.exit()}，JavaFX 会在这里回调。
     * 色盲辅助的矫正工具进程与本地调节网页服务都要显式收掉 —— 后者的调度线程不是守护线程，
     * 不停止会让 JVM 卡住，表现为「窗口关了但进程还在」。
     */
    @Override
    public void stop() {
        com.example.starlight.colorblind.ColorBlindOverlayManager.shutdown();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 注册 UI 字体：必须在创建任何 Scene 之前，否则 CSS 里的字体家族名匹配不到
        AppFonts.install();

        checkRuntimeEnvironment();

        Image icon = loadIcon();
        if (icon != null) {
            primaryStage.getIcons().add(icon);
        }

        JavaFXLauncher.primaryStage = primaryStage;

        // ============================================================
        // 必须先设置无边框透明窗口（必须在 show() 之前调用）
        // ============================================================
        primaryStage.initStyle(StageStyle.TRANSPARENT);

        // ============================================================
        // 先显示启动动画（Splash），再进入主界面
        // 开发者选项可关闭启动画面（ShowSplash=false），此时直接进入主界面
        // ============================================================
        boolean showSplash = true;
        try {
            showSplash = !"false".equalsIgnoreCase(
                    ConfigManager.readClientConfig().getOrDefault("ShowSplash", "true"));
        } catch (Exception ignored) {}

        if (showSplash) {
            showSplashPage(primaryStage);
        } else {
            // 跳过闪屏：后台仍执行预热初始化，不阻塞界面进入
            UIGeneralControlClass.ASYNC_POOL.submit(JavaFXLauncher::runBackgroundInit);
            enterMain(primaryStage);
        }
    }

    /**
     * 闪屏期间/跳过闪屏时执行的后台预热初始化。
     * 每个步骤通过 {@link SplashProgress} 汇报状态文字与进度，闪屏逐帧轮询显示。
     * @return 初始化耗时（毫秒）
     */
    private static long runBackgroundInit() {
        long t0 = System.currentTimeMillis();
        SplashProgress.reset();

        SplashProgress.update("正在加载配置…", 0.05);
        // 预加载配置
        Map<String, String> cfg = new java.util.HashMap<>();
        try {
            cfg.putAll(ConfigManager.readConfig());
            cfg.putAll(ConfigManager.readClientConfig());
        } catch (Exception ignored) {}

        SplashProgress.update("正在读取账号…", 0.12);
        // 预初始化 AccountManager（读取账号信息）
        try { AccountManager.listAccounts(); } catch (Exception ignored) {}

        SplashProgress.update("正在检查联机组件…", 0.18);
        // 预检查 Terracotta 状态（触发静态初始化，确保 Provider 元数据就绪）
        try { org.starlight.terracotta.TerracottaManager.stateProperty(); } catch (Exception ignored) {}

        SplashProgress.update("正在读取系统信息…", 0.25);
        // 预热系统信息采样（CPU 采样窗口、物理内存、磁盘读写速率），进入首页时数据已就绪
        try { SystemInfoMonitor.warmUp(); } catch (Exception ignored) {}

        // 静默登录（模式二「开启启动器静默登录」）：闪屏后台提前刷新过期令牌，
        // 游戏启动时直接命中有效令牌，不再等待网络刷新
        if (SilentLoginManager.isStartupMode()) {
            SplashProgress.update("正在静默登录…", 0.35);
            try { SilentLoginManager.refreshIfNeeded(null); } catch (Exception ignored) {}
        }

        SplashProgress.update("正在扫描 Java 运行时…", 0.60);
        // 预热 Java 扫描缓存：全盘扫描一次后写入 json，后续自动选择 Java / 设置页列表直接读缓存
        try { JavaCacheManager.warmUp(); } catch (Exception ignored) {}

        SplashProgress.update("正在启动插件…", 0.85);
        // ============================================================
        // 插件系统初始化（顺序固定，不可颠倒）：
        //   1) 先启动本地 API 服务 —— 绑定 0 端口，由操作系统分配空闲端口，
        //      服务启动成功后立即把实际端口写入 .minecraft/starlight_api.port；
        //   2) 再初始化插件管理器并扫描插件元数据（只读 JSON，不加载类）；
        //   3) 最后自动启动所有【已启用】的插件子进程（java -jar，进程隔离）。
        //   顺序颠倒会导致插件子进程读不到端口文件而握手失败。
        // 插件系统异常不影响启动器主功能（整体兜底，不阻断 UI）。
        // ============================================================
        try {
            String gameDir = cfg.getOrDefault("GameDir", ".minecraft");
            java.nio.file.Path minecraftDir = java.nio.file.Path.of(gameDir);

            PluginApiServer apiServer = new PluginApiServer(API.getInstance(), minecraftDir);
            apiServer.start();
            log.info("插件 API 服务已启动: http://localhost:{} (端口文件: {})",
                    apiServer.getPort(), minecraftDir.resolve(PluginApiServer.PORT_FILE_NAME));

            PluginManager.init(java.nio.file.Path.of("").toAbsolutePath(), minecraftDir);
            PluginManager.getInstance().load();
            PluginManager.getInstance().startAll();
        } catch (Throwable t) {
            log.error("插件系统初始化失败（不影响启动器主功能）", t);
        }

        SplashProgress.update("准备就绪", 1.0);

        long elapsed = System.currentTimeMillis() - t0;
        log.info("Splash initialization took: {}ms", elapsed);
        return elapsed;
    }

    /**
     * 闪屏结束/跳过闪屏后进入主界面
     */
    private void enterMain(Stage stage) {
        // 闪屏的置顶到这里结束：主界面是正常窗口，不该一直压着别的程序
        stage.setAlwaysOnTop(false);
        stage.setResizable(true);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        try {
            showMainPage(stage);
        } catch (Throwable t) {
            // 主界面构建失败不能无声卡死：记录异常 + 显示降级错误窗口，否则用户只看到永远定格的闪屏
            log.error("主界面构建失败", t);
            Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            showFatalError(stage, t);
        }
    }

    /** 主界面构建失败时的降级提示窗口（保留窗口可见性，避免“看起来卡死”） */
    private void showFatalError(Stage stage, Throwable t) {
        try {
            javafx.scene.control.Label label = new javafx.scene.control.Label(
                    "主界面初始化失败：\n" + t + "\n\n详情见 logs/launcher-crash.log");
            label.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px; -fx-padding: 30; -fx-wrap-text: true;");
            javafx.scene.Scene scene = new javafx.scene.Scene(label, 560, 360);
            scene.setFill(Color.web("#13162b"));
            stage.setScene(scene);
            stage.centerOnScreen();
            stage.show();
        } catch (Throwable ignored) {
            // 连错误窗口都显示不出来就只好放弃
        }
    }

    /**
     * 显示启动动画页面
     */
    private void showSplashPage(Stage stage) {
        SplashView splash = new SplashView();
        Scene scene = new Scene((Parent) splash.getView());
        scene.setFill(Color.TRANSPARENT);

        stage.setTitle("Starlight Launcher");
        stage.setScene(scene);
        stage.setResizable(false);
        // 开屏动画始终在屏幕中心显示
        centerStage(stage, SplashView.SPLASH_WIDTH, SplashView.SPLASH_HEIGHT);
        // 闪屏期间置顶：避免启动过程被其它窗口盖住（进入主界面时会关掉，见 enterMain）
        stage.setAlwaysOnTop(true);
        stage.show();

        // 在闪屏期间异步执行初始化（加载配置、检查环境等）。
        // 注意：初始化任务异常或卡死时也必须能进入主界面（否则动画无限加载且无任何报错），
        // 因此 1) 初始化整体包 try-catch(Throwable)，异常不阻断；
        //     2) 由独立线程用 Future.get(超时) 等待初始化，超时后强制结束闪屏。
        final long initStartMillis = System.currentTimeMillis();
        java.util.concurrent.Future<?> initFuture = UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                runBackgroundInit();
            } catch (Throwable t) {
                log.error("Splash background init failed", t);
            }
        });

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                initFuture.get(SPLASH_MAX_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                // 初始化超时（网络/磁盘卡死等）或中断：不再等待，直接结束闪屏
                log.warn("Splash init did not finish within {}s, entering main page anyway",
                        SPLASH_MAX_WAIT_SECONDS);
            }
            // 动画至少播放 minDuration（2 秒），初始化耗时更长则立即结束
            long elapsed = System.currentTimeMillis() - initStartMillis;
            double remaining = Math.max(0, Duration.seconds(2.0).toMillis() - elapsed);
            Platform.runLater(() -> splash.playAnimation(Duration.millis(remaining)));
        });

        splash.setOnFinished(() -> Platform.runLater(() -> enterMain(stage)));
    }

    /**
     * 显示新版主界面（LauncherView）
     */
    private void showMainPage(Stage stage) {
        LauncherView launcherView = new LauncherView(stage);
        Region mainRoot = (Region) launcherView.getView();

        Scene scene = new Scene(mainRoot);
        scene.setFill(Color.TRANSPARENT);

        // 加载 NEWUI 样式表
        var cssUrl = getClass().getResource("/fxml/css/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("Starlight Launcher");
        stage.setScene(scene);
        stage.setMinWidth(920);
        stage.setMinHeight(620);

        // 从配置恢复窗口大小
        double winWidth = 920;
        double winHeight = 620;
        try {
            String w = ConfigManager.readClientConfig().getOrDefault("WindowWidth", "920");
            String h = ConfigManager.readClientConfig().getOrDefault("WindowHeight", "620");
            int width = Integer.parseInt(w);
            int height = Integer.parseInt(h);
            if (width >= 920 && height >= 620) {
                stage.setWidth(width);
                stage.setHeight(height);
                winWidth = width;
                winHeight = height;
            }
        } catch (Exception ignored) {}

        // 从配置恢复窗口位置；「沿用上次关闭的窗口位置」关闭时窗口居中显示
        applyWindowPosition(stage, winWidth, winHeight);

        stage.show();

        // 从配置恢复最大化状态（需在 show() 之后）
        String maximized = ConfigManager.readClientConfig().get("WindowMaximized");
        if ("true".equals(maximized)) {
            stage.setMaximized(true);
        }

        // 窗口状态防抖保存：拖拽/缩放停止 500ms 后写入文件一次
        final Timeline[] debounceTimers = {null};
        javafx.util.Duration debounceDelay = javafx.util.Duration.millis(500);

        Runnable saveWindowState = () -> {
            if (debounceTimers[0] != null) debounceTimers[0].stop();
            debounceTimers[0] = new Timeline(
                new KeyFrame(debounceDelay, e -> {
                    Map<String, String> cfg = new java.util.HashMap<>();
                    cfg.put("WindowWidth", String.valueOf((int) stage.getWidth()));
                    cfg.put("WindowHeight", String.valueOf((int) stage.getHeight()));
                    cfg.put("WindowX", String.valueOf((int) stage.getX()));
                    cfg.put("WindowY", String.valueOf((int) stage.getY()));
                    cfg.put("WindowMaximized", String.valueOf(stage.isMaximized()));
                    ConfigManager.saveClientConfig(cfg);
                })
            );
            debounceTimers[0].play();
        };

        stage.widthProperty().addListener((obs, o, n) -> {
            if (!stage.isMaximized() && n.intValue() > 100) saveWindowState.run();
        });
        stage.heightProperty().addListener((obs, o, n) -> {
            if (!stage.isMaximized() && n.intValue() > 100) saveWindowState.run();
        });
        stage.xProperty().addListener((obs, o, n) -> saveWindowState.run());
        stage.yProperty().addListener((obs, o, n) -> saveWindowState.run());
        stage.maximizedProperty().addListener((obs, was, now) -> saveWindowState.run());

        // 圆角裁剪
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(mainRoot.widthProperty());
        clip.heightProperty().bind(mainRoot.heightProperty());
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        mainRoot.setClip(clip);

        // 窗口阴影
        DropShadow shadow = new DropShadow();
        shadow.setRadius(20);
        shadow.setOffsetX(0);
        shadow.setOffsetY(4);
        shadow.setColor(Color.rgb(0, 0, 0, 0.15));
        mainRoot.setEffect(shadow);
    }

    /**
     * 将窗口定位到目标位置：开启「沿用上次关闭的窗口位置」时恢复 WindowX/WindowY，否则在屏幕中央显示。
     * 闪屏与主界面共用同一套定位逻辑，保证闪屏 → 主界面过渡时窗口位置不跳变。
     *
     * @param width  居中计算用的窗口宽度（主界面为恢复后的宽度，闪屏为 520）
     * @param height 居中计算用的窗口高度（主界面为恢复后的高度，闪屏为 325）
     */
    private static void applyWindowPosition(Stage stage, double width, double height) {
        try {
            boolean keepWindowPosition = !"false".equalsIgnoreCase(
                    ConfigManager.readClientConfig().getOrDefault("KeepWindowPosition", "true"));
            if (keepWindowPosition) {
                String wx = ConfigManager.readClientConfig().get("WindowX");
                String wy = ConfigManager.readClientConfig().get("WindowY");
                if (wx != null && wy != null) {
                    stage.setX(Double.parseDouble(wx));
                    stage.setY(Double.parseDouble(wy));
                    return;
                }
            }
            // 居中：基于屏幕可视区域与已知尺寸计算，不依赖尚未完成布局的 stage 尺寸
            centerStage(stage, width, height);
        } catch (Exception ignored) {}
    }

    /** 将窗口居中到屏幕可视区域（按给定尺寸计算，不依赖尚未完成布局的 stage 尺寸） */
    private static void centerStage(Stage stage, double width, double height) {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setX(bounds.getMinX() + (bounds.getWidth() - width) / 2);
        stage.setY(bounds.getMinY() + (bounds.getHeight() - height) / 2);
    }

    // ==================== 图标加载 ====================

    private Image loadIcon() {
        InputStream pngStream = getClass().getResourceAsStream("/images/icon.png");
        if (pngStream != null) {
            return new Image(pngStream);
        }
        try {
            return IcoImageLoader.loadIcoAsImage(
                    getClass().getResourceAsStream("/images/icon.ico"));
        } catch (IOException e) {
            System.err.println("Failed to load icon: " + e.getMessage());
            return null;
        }
    }

    // ==================== 环境检测 ====================

    private void checkRuntimeEnvironment() {
        String javaVersion = System.getProperty("java.version", "Unknown");
        String javaVmName = System.getProperty("java.vm.name", "Unknown");
        String javaVendor = System.getProperty("java.vendor", "Unknown");
        int actualMajorVersion = parseJavaMajorVersion(javaVersion);

        log.info("Java runtime: {} ({} {} bit)", javaVersion, javaVmName,
                System.getProperty("sun.arch.data.model", "Unknown"));
        log.info("Java vendor: {}", javaVendor);

        if (actualMajorVersion > 0 && actualMajorVersion != EXPECTED_JAVA_VERSION) {
            log.warn("Java version mismatch: current {} (major {}), compiled for Java {}",
                    javaVersion, actualMajorVersion, EXPECTED_JAVA_VERSION);
        }

        if (actualMajorVersion >= 16) {
            log.info("Detected Java {}, ensure launch args include --enable-native-access=ALL-UNNAMED", actualMajorVersion);
        }

        String javafxVersion = System.getProperty("javafx.version", "Unknown");
        log.info("JavaFX version: {}", javafxVersion);

        if (!"Unknown".equals(javafxVersion)
                && !javafxVersion.startsWith(EXPECTED_JAVAFX_VERSION_PREFIX)) {
            log.warn("JavaFX version mismatch: current {}, compiled with {}.*",
                    javafxVersion, EXPECTED_JAVAFX_VERSION_PREFIX);
        }
    }

    private static int parseJavaMajorVersion(String version) {
        if (version == null || version.isEmpty()) return -1;
        try {
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2, 3));
            }
            int dot = version.indexOf('.');
            if (dot > 0) {
                return Integer.parseInt(version.substring(0, dot));
            }
            int dash = version.indexOf('-');
            if (dash > 0) {
                return Integer.parseInt(version.substring(0, dash));
            }
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Java version: {}", version);
            return -1;
        }
    }

    public static void main(String[] args) {
        // IDEA 运行配置若直接指向本类，也要先剥离 --console（命令行启动模式的显式开关）
        launch(ConsoleMode.stripAndMaybeEnable(args));
    }
}
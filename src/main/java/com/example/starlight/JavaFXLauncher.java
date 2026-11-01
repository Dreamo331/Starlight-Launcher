package com.example.starlight;


import com.example.starlight.util.I18N;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.listjava.JavaCacheManager;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.main.ConsoleMode;
import com.example.starlight.newui.IconSplashView;
import com.example.starlight.newui.LauncherView;
import com.example.starlight.newui.SplashProgress;
import com.example.starlight.newui.SplashScreen;
import com.example.starlight.newui.SplashView;
import com.example.starlight.newui.ui.AppFonts;
import com.example.starlight.plugin.PluginManager;
import com.example.starlight.pluginapi.API;
import com.example.starlight.pluginapi.server.PluginApiServer;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.util.IcoImageLoader;
import com.example.starlight.util.SystemInfoMonitor;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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

    /**
     * 主窗口外阴影的留白（逻辑像素）。
     *
     * <p>本窗口是 {@code StageStyle.TRANSPARENT}，系统不会画阴影，而窗口矩形之外的像素
     * 根本画不出来 —— 所以内容必须往里缩一圈，用这圈透明留白给阴影当画布。
     * 留白要明显大于阴影的实际扩散范围（实测半径 20 的 DropShadow 约 15-18px 就衰减完），
     * 否则阴影在窗口边界处还剩一点 alpha，会被硬切成一条「比内容大一圈」的细线。</p>
     */
    private static final double WINDOW_SHADOW_MARGIN = 26;
    private static final double WINDOW_SHADOW_RADIUS = 22;
    private static final double WINDOW_SHADOW_OFFSET_Y = 3;
    private static final Color WINDOW_SHADOW_COLOR = Color.rgb(0, 0, 0, 0.32);

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

        // 必须先设置无边框透明窗口（必须在 show() 之前调用）
        primaryStage.initStyle(StageStyle.TRANSPARENT);

        // 语言要在同意页之前应用：同意页本身也要按用户配置的语言显示
        applyLanguageEarly();

        // 告知—同意门：第一次使用（或法律文档修订后）必须先阅读并主动勾选确认，
        // 同意之前不进入主界面、也不启动插件 API 等后台服务。
        if (!com.example.starlight.legal.LegalConsentStore.isAccepted()) {
            com.example.starlight.legal.LegalConsentGate.show(primaryStage,
                    () -> continueStartup(primaryStage));
            return;
        }
        continueStartup(primaryStage);
    }

    /**
     * 同意之后（或已有有效同意记录时）继续正常的启动流程：闪屏 → 后台初始化 → 主界面。
     *
     * <p>单独抽出来是因为同意页会打断 {@link #start(Stage)}：同意是异步的用户动作，
     * 不能在里面阻塞等待，只能走回调。
     */
    private void continueStartup(Stage stage) {
        // 开发者选项可关闭启动画面（ShowSplash=false），此时直接进入主界面
        boolean showSplash = true;
        try {
            showSplash = !"false".equalsIgnoreCase(
                    ConfigManager.readClientConfig().getOrDefault("ShowSplash", "true"));
        } catch (Exception ignored) {}

        if (showSplash) {
            showSplashPage(stage);
        } else {
            // 跳过闪屏：后台仍执行预热初始化，不阻塞界面进入
            UIGeneralControlClass.ASYNC_POOL.submit(JavaFXLauncher::runBackgroundInit);
            enterMain(stage);
        }
    }

    /**
     * 构造函数最早期同样的做法：只读配置里的 {@code Language} 一个键，不触发磁盘扫描等副作用。
     * 同意页在主界面之前出现，这里必须自己应用一次语言。
     */
    private static void applyLanguageEarly() {
        String lang = "zh_CN";
        try {
            lang = ConfigManager.readConfig().getOrDefault("Language", "zh_CN");
            Map<String, String> clientCfg = ConfigManager.readClientConfig();
            if (clientCfg.containsKey("Language")) lang = clientCfg.get("Language");
        } catch (Exception ignored) {
            // 配置读不出来就用默认语言，绝不因为语言问题阻断启动
        }
        I18N.applyConfig(lang);
    }

    /**
     * 闪屏期间/跳过闪屏时执行的后台预热初始化。
     * 每个步骤通过 {@link SplashProgress} 汇报状态文字与进度，闪屏逐帧轮询显示。
     * @return 初始化耗时（毫秒）
     */
    private static long runBackgroundInit() {
        long t0 = System.currentTimeMillis();
        SplashProgress.reset();

        SplashProgress.update(I18N.get("app.t0001"), 0.05);
        // 预加载配置
        Map<String, String> cfg = new java.util.HashMap<>();
        try {
            cfg.putAll(ConfigManager.readConfig());
            cfg.putAll(ConfigManager.readClientConfig());
        } catch (Exception ignored) {}

        SplashProgress.update(I18N.get("app.t0002"), 0.12);
        // 预初始化 AccountManager（读取账号信息）
        try { AccountManager.listAccounts(); } catch (Exception ignored) {}

        SplashProgress.update(I18N.get("app.t0003"), 0.18);
        // 预检查 Terracotta 状态（触发静态初始化，确保 Provider 元数据就绪）
        try { org.starlight.terracotta.TerracottaManager.stateProperty(); } catch (Exception ignored) {}

        SplashProgress.update(I18N.get("app.t0004"), 0.25);
        // 预热系统信息采样（CPU 采样窗口、物理内存、磁盘读写速率），进入首页时数据已就绪
        try { SystemInfoMonitor.warmUp(); } catch (Exception ignored) {}

        // 静默登录（模式二「开启启动器静默登录」）：闪屏后台提前刷新过期令牌，
        // 游戏启动时直接命中有效令牌，不再等待网络刷新
        if (SilentLoginManager.isStartupMode()) {
            SplashProgress.update(I18N.get("app.t0005"), 0.35);
            try { SilentLoginManager.refreshIfNeeded(null); } catch (Exception ignored) {}
        }

        SplashProgress.update(I18N.get("app.t0006"), 0.60);
        // 预热 Java 扫描缓存：全盘扫描一次后写入 json，后续自动选择 Java / 设置页列表直接读缓存
        try { JavaCacheManager.warmUp(); } catch (Exception ignored) {}

        SplashProgress.update(I18N.get("app.t0007"), 0.85);
        // 插件系统初始化（顺序固定，不可颠倒）： //
        // 1) 先启动本地 API 服务 —— 绑定 0 端口，由操作系统分配空闲端口，
        // 服务启动成功后立即把实际端口写入 .minecraft/starlight_api.port；
        // 2) 再初始化插件管理器并扫描插件元数据（只读 JSON，不加载类）；
        // 3) 最后自动启动所有【已启用】的插件子进程（java -jar，进程隔离）。
        // 顺序颠倒会导致插件子进程读不到端口文件而握手失败。
        // 插件系统异常不影响启动器主功能（整体兜底，不阻断 UI）。
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

        SplashProgress.update(I18N.get("app.t0008"), 1.0);

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
                    I18N.get("jfxl.r0001", t));
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
        SplashScreen splash = createSplashScreen();
        Scene scene = new Scene((Parent) splash.getView());
        scene.setFill(Color.TRANSPARENT);

        stage.setTitle("Starlight Launcher");
        stage.setScene(scene);
        stage.setResizable(false);
        // 开屏动画始终在屏幕中心显示
        centerStage(stage, splash.width(), splash.height());
        // 闪屏期间置顶：避免启动过程被其它窗口盖住（进入主界面时会关掉，见 enterMain）
        stage.setAlwaysOnTop(true);
        stage.show();
        // 场景布局完成后再按窗口的真实尺寸校准一次：
        // 上面那次用的是「声明的尺寸」，若根节点首选尺寸与它对不上（或系统缩放带来取整差异），
        // 图片就会偏离屏幕正中，而透明无边框窗口一旦偏了没人看得出来偏在哪。
        Platform.runLater(() -> centerStage(stage, stage.getWidth(), stage.getHeight()));

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

        splash.setOnFinished(() -> Platform.runLater(() -> {
            splash.stop();
            enterMain(stage);
        }));
    }

    /**
     * 按配置创建开屏动画。
     *
     * <p>两套可选（设置 → 主题与背景 →「启动动画」）：
     * <ul>
     *   <li>{@code starlight}（默认）：{@link SplashView} 星轨动画，Canvas 逐帧绘制星空；</li>
     *   <li>{@code icon}：{@link IconSplashView} 静态开屏，屏幕正中只显示一张启动器图标，无动画。</li>
     * </ul>
     * 配置读不出来（或值不认识）时一律回落星轨动画，绝不因为一个配置项阻断启动。
     */
    private static SplashScreen createSplashScreen() {
        String style = "starlight";
        try {
            style = ConfigManager.readClientConfig().getOrDefault("SplashStyle", "starlight");
        } catch (Exception ignored) {}
        return "icon".equalsIgnoreCase(style) ? new IconSplashView() : new SplashView();
    }

    /**
     * 显示新版主界面（LauncherView）
     */
    private void showMainPage(Stage stage) {
        LauncherView launcherView = new LauncherView(stage);
        Region mainRoot = (Region) launcherView.getView();

        // 从闪屏切过来时，窗口还是闪屏那个「居中尺寸」（图标开屏只有 128×128）。
        // 直接 setScene 会有一两帧把主界面画进这个小窗口里 —— 表现就是「动画闪一下、
        // 然后跳到窗口位置」。所以先把整个窗口置为全透明，等场景 / 尺寸 / 位置都定好再淡入。
        //
        // 不能用 stage.hide()：隐藏最后一个窗口会触发 JavaFX 的隐式退出，后面的 show() 起不来，
        // 表现就是「启动器闪一下就没了」。改透明度不影响 showing 状态，没有这个副作用。
        boolean switchFromSplash = stage.isShowing();
        if (switchFromSplash) {
            stage.setOpacity(0);
        }

        // 外阴影：内容往里缩 WINDOW_SHADOW_MARGIN，阴影画在这圈透明留白里。
        //
        // ⚠ 这里有个坑：JavaFX 会把 "root" 样式类自动挂到「场景根节点」上，而 style.css 里的
        // .root 带背景色、1px 边框和 dropshadow —— 直接把留白容器当场景根节点，它就会被画成
        // 第二张「比内容大一圈、带边框和阴影」的卡片，看上去就是窗口外面多了一道框。
        // 所以留白容器必须用内联样式把 .root 的这几项全部压掉（内联优先级最高）。
        StackPane shadowHost = new StackPane();
        shadowHost.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;"
                + " -fx-border-width: 0; -fx-effect: null;");
        StackPane shadowBox = new StackPane(mainRoot);
        shadowBox.setEffect(createWindowShadow());
        shadowHost.getChildren().add(shadowBox);

        Scene scene = new Scene(shadowHost);
        scene.setFill(Color.TRANSPARENT);

        // 加载 NEWUI 样式表
        var cssUrl = getClass().getResource("/fxml/css/style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        stage.setTitle("Starlight Launcher");
        stage.setScene(scene);
        // 最小尺寸按「内容」给：窗口本身还要再加两侧的阴影留白
        stage.setMinWidth(920 + WINDOW_SHADOW_MARGIN * 2);
        stage.setMinHeight(620 + WINDOW_SHADOW_MARGIN * 2);

        // 从配置恢复窗口大小
        double winWidth = 920;
        double winHeight = 620;
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        // 屏幕放不下的尺寸一律裁掉：窗口比可视区域高时，底边（连同圆角）会跑到屏幕外，
        // 看起来就是「页面下方没有圆角」；同时页面滚动区会被撑得比内容还高，
        // 于是明明内容没显示全，却「滚不动」。
        double maxWinWidth = Math.max(760, visual.getWidth() - 40 - WINDOW_SHADOW_MARGIN * 2);
        double maxWinHeight = Math.max(560, visual.getHeight() - 60 - WINDOW_SHADOW_MARGIN * 2);
        try {
            String w = ConfigManager.readClientConfig().getOrDefault("WindowWidth", "920");
            String h = ConfigManager.readClientConfig().getOrDefault("WindowHeight", "620");
            int width = Integer.parseInt(w);
            int height = Integer.parseInt(h);
            if (width >= 920 && height >= 620) {
                winWidth = Math.min(width, maxWinWidth);
                winHeight = Math.min(height, maxWinHeight);
                // 配置里存的是「内容尺寸」，窗口 = 内容 + 两侧留白（这样存取口径不会每重启一次长 48px）
                stage.setWidth(winWidth + WINDOW_SHADOW_MARGIN * 2);
                stage.setHeight(winHeight + WINDOW_SHADOW_MARGIN * 2);
            }
        } catch (Exception ignored) {}

        // 从配置恢复窗口位置；「沿用上次关闭的窗口位置」关闭时窗口居中显示
        applyWindowPosition(stage, winWidth + WINDOW_SHADOW_MARGIN * 2,
                winHeight + WINDOW_SHADOW_MARGIN * 2);

        stage.show();

        // 从配置恢复最大化状态（需在 show() 之后）
        String maximized = ConfigManager.readClientConfig().get("WindowMaximized");
        if ("true".equals(maximized)) {
            stage.setMaximized(true);
        }

        // 没给过尺寸时，Stage 会按「场景首选尺寸」自己定 —— 首页内容的 pref 高度上千，
        // 于是窗口会自己长到比屏幕还高。这里把尺寸与位置一起夹进可视区；
        // 最大化时窗口由系统接管，不去动它。
        clampWindowToScreen(stage);

        // 窗口状态防抖保存：拖拽/缩放停止 500ms 后写入文件一次
        final Timeline[] debounceTimers = {null};
        javafx.util.Duration debounceDelay = javafx.util.Duration.millis(500);

        Runnable saveWindowState = () -> {
            if (debounceTimers[0] != null) debounceTimers[0].stop();
            debounceTimers[0] = new Timeline(
                new KeyFrame(debounceDelay, e -> {
                    Map<String, String> cfg = new java.util.HashMap<>();
                    // 存内容尺寸（扣掉阴影留白），这样「窗口尺寸」不会把留白也算进去
                    cfg.put("WindowWidth",
                            String.valueOf((int) (stage.getWidth() - WINDOW_SHADOW_MARGIN * 2)));
                    cfg.put("WindowHeight",
                            String.valueOf((int) (stage.getHeight() - WINDOW_SHADOW_MARGIN * 2)));
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

        // 圆角裁剪（窗口矩形紧贴内容，圆角只能靠 clip 画）
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(mainRoot.widthProperty());
        clip.heightProperty().bind(mainRoot.heightProperty());
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        mainRoot.setClip(clip);

        // 最大化时窗口贴满屏幕：再留一圈透明边只会让内容凭空变小，阴影也没地方画 —— 边距归零、阴影摘掉。
        Runnable syncShadow = () -> {
            boolean isMax = stage.isMaximized() || stage.isFullScreen();
            shadowHost.setPadding(isMax ? Insets.EMPTY : new Insets(WINDOW_SHADOW_MARGIN));
            shadowBox.setEffect(isMax ? null : createWindowShadow());
        };
        stage.maximizedProperty().addListener((obs, wasMax, isMax) -> syncShadow.run());
        stage.fullScreenProperty().addListener((obs, wasFs, isFs) -> syncShadow.run());
        syncShadow.run();

        // 圆角裁剪装好之后再淡入：窗口是「凭空出现」的（从闪屏切来尤其明显），硬切很突兀。
        if (switchFromSplash) {
            fadeInWindow(stage);
        }
    }

    /** 主窗口外阴影（画在 {@link #WINDOW_SHADOW_MARGIN} 的透明留白里） */
    private static DropShadow createWindowShadow() {
        DropShadow shadow = new DropShadow();
        shadow.setRadius(WINDOW_SHADOW_RADIUS);
        shadow.setOffsetX(0);
        shadow.setOffsetY(WINDOW_SHADOW_OFFSET_Y);
        shadow.setColor(WINDOW_SHADOW_COLOR);
        return shadow;
    }

    /** 主窗口淡入（200ms，只改窗口透明度，不碰布局与命中测试） */
    private static void fadeInWindow(Stage stage) {
        Timeline fade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(stage.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(200), new KeyValue(stage.opacityProperty(), 1)));
        fade.play();
    }

    /**
     * 把窗口夹进屏幕可视区域：尺寸超了就缩，位置越界就挪回来。
     *
     * <p>为什么必须夹：这个窗口是无边框透明窗，圆角靠根节点的 clip 画出来。窗口一旦比屏幕可视区
     * 还高（或者位置让底边出屏），用户看到的就是「页面下方被切平、没有圆角」，同时滚动区的视口
     * 被撑得比内容还高，明明内容没看全却滚不动。另外 JavaFX 在没显式给尺寸时会按场景首选尺寸
     * 自动定窗口大小，首页内容的 pref 高度上千，正好会踩到这个坑。</p>
     */
    private static void clampWindowToScreen(Stage stage) {
        try {
            if (stage.isMaximized() || stage.isFullScreen()) {
                return;
            }
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            double maxW = Math.max(760, vb.getWidth() - 40);
            double maxH = Math.max(560, vb.getHeight() - 60);
            if (stage.getWidth() > maxW) {
                stage.setWidth(maxW);
            }
            if (stage.getHeight() > maxH) {
                stage.setHeight(maxH);
            }
            double x = stage.getX();
            double y = stage.getY();
            double maxX = vb.getMaxX() - stage.getWidth() - 8;
            double maxY = vb.getMaxY() - stage.getHeight() - 8;
            stage.setX(Math.max(vb.getMinX() + 8, Math.min(x, maxX)));
            stage.setY(Math.max(vb.getMinY() + 8, Math.min(y, maxY)));
        } catch (Exception ignored) {
            // 拿不到屏幕信息（无头环境等）就维持原样
        }
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

    // 图标加载 //

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

    // 环境检测 //

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
        // IDEA 运行配置若直接指向本类（而非 MainApp），也必须先初始化数据目录，
        // 否则用户级配置会落到默认全局根目录、且历史数据不会被迁移。
        String[] afterPortable = com.example.starlight.config.AppDirs.initAndStrip(args);
        // 再剥离 --console（命令行启动模式的显式开关）
        launch(ConsoleMode.stripAndMaybeEnable(afterPortable));
    }
}
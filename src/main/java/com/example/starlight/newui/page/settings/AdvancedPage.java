package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.modfile.ModFileFormatter;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.service.ParallelLaunchManager;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ResourceScanner;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.example.starlight.download.DownloadSettings;
import com.example.starlight.version.VersionDownloadService;
import javafx.scene.control.Slider;
import javafx.scene.layout.FlowPane;

/** 
设置 → 高级设置页（启动 / 窗口 / 版本隔离 / 参数 / 下载 / 内存）
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class AdvancedPage {

    private final LauncherContext host;


    public AdvancedPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(host, "高级设置");

        // 窗口宽度
        String curW = host.config().getOrDefault("WindowWidth", "854");
        TextField widthField = new TextField(curW);
        widthField.getStyleClass().add("input-field");
        widthField.setPrefWidth(100);
        Label xLabel = new Label("×");
        xLabel.setStyle("-fx-font-size: 14px; -fx-padding: 0 4;");
        String curH = host.config().getOrDefault("WindowHeight", "480");
        TextField heightField = new TextField(curH);
        heightField.getStyleClass().add("input-field");
        heightField.setPrefWidth(100);
        Button saveWin = AppIcons.button("save", "保存");
        saveWin.getStyleClass().add("btn-primary");
        saveWin.setOnAction(e -> {
            host.config().put("WindowWidth", widthField.getText());
            host.config().put("WindowHeight", heightField.getText());
            host.saveConfig();
            host.ui().toast("窗口大小已保存");
        });
        HBox winRow = new HBox(10, new Label("宽:"), widthField, xLabel, new Label("高:"), heightField, saveWin);
        winRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("窗口大小", "", winRow));

        // 全屏模式
        CheckBox fullscreenToggle = PageKit.toggle("true".equals(host.config().getOrDefault("Fullscreen", "false")));
        fullscreenToggle.setOnAction(e -> {
            host.config().put("Fullscreen", String.valueOf(fullscreenToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("全屏模式: " + (fullscreenToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("全屏模式", "以全屏模式启动游戏", fullscreenToggle));

        // 游戏语言（启动前写入 options.txt 的 lang 字段；单个版本可在「版本独立设置」页单独覆盖）
        ComboBox<String> gameLangCombo = new ComboBox<>();
        String playerFollow = "不修改（跟随游戏内设置）";
        gameLangCombo.getItems().add(playerFollow);
        gameLangCombo.getItems().addAll(GameLanguage.LANGUAGE_MAP.keySet());
        String curGameLang = host.config().getOrDefault("GameLanguage", "");
        gameLangCombo.setValue(curGameLang == null || curGameLang.isBlank()
                ? playerFollow : GameLanguage.getDisplayNameByCode(curGameLang));
        gameLangCombo.getStyleClass().add("select-field");
        gameLangCombo.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        gameLangCombo.setOnAction(e -> {
            String val = gameLangCombo.getValue();
            if (val == null) return;
            boolean follow = playerFollow.equals(val);
            host.config().put("GameLanguage", follow ? "" : GameLanguage.getCodeByDisplayName(val));
            host.saveConfig();
            host.ui().toast("游戏语言: " + (follow ? "不修改" : val));
        });
        root.getChildren().add(PageKit.settingsCard("游戏语言",
                "启动前写入游戏 options.txt 的 lang 字段；单个版本可在「版本独立设置」页覆盖", gameLangCombo));

        // 版本隔离
        CheckBox viToggle = PageKit.toggle("true".equals(host.config().getOrDefault("VersionIsolation", "false")));
        viToggle.setOnAction(e -> {
            host.config().put("VersionIsolation", String.valueOf(viToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("版本隔离: " + (viToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("版本隔离", "每个版本使用独立的游戏目录", viToggle));

        // 主页快速进入存档
        CheckBox quickSaveToggle = PageKit.toggle("true".equals(host.config().getOrDefault("HomeQuickLaunchSave", "false")));
        quickSaveToggle.setOnAction(e -> {
            host.config().put("HomeQuickLaunchSave", String.valueOf(quickSaveToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("主页快速进入存档: " + (quickSaveToggle.isSelected() ? "开启" : "关闭"));
            // 立即重建主页（当前停留在主页时原地刷新）
            host.refreshHomePage();
        });
        root.getChildren().add(PageKit.settingsCard("主页快速进入存档", "在主页显示存档列表，点击即可启动游戏并快速进入对应存档（1.20.2+ 直接进存档，旧版本仅启动游戏）", quickSaveToggle));

        // 游戏参数
        String curGameArgs = host.config().getOrDefault("GameArgs", "");
        TextField gameArgsField = new TextField(curGameArgs);
        gameArgsField.getStyleClass().add("input-field");
        gameArgsField.setPrefWidth(300);
        Button saveGameArgs = AppIcons.button("save", "保存");
        saveGameArgs.getStyleClass().add("btn-primary");
        saveGameArgs.setOnAction(e -> {
            host.config().put("GameArgs", gameArgsField.getText());
            host.saveConfig();
            host.ui().toast("游戏参数已保存");
        });
        HBox gameArgsRow = new HBox(10, gameArgsField, saveGameArgs);
        root.getChildren().add(PageKit.settingsCard("游戏参数", "传递给 Minecraft 的额外参数", gameArgsRow));

        // 启动前命令
        String curPre = host.config().getOrDefault("PreLaunchCommand", "");
        TextField preField = new TextField(curPre);
        preField.getStyleClass().add("input-field");
        preField.setPrefWidth(300);
        Button savePre = AppIcons.button("save", "保存");
        savePre.getStyleClass().add("btn-primary");
        savePre.setOnAction(e -> {
            host.config().put("PreLaunchCommand", preField.getText());
            host.saveConfig();
            host.ui().toast("启动前命令已保存");
        });
        HBox preRow = new HBox(10, preField, savePre);
        root.getChildren().add(PageKit.settingsCard("启动前命令", "游戏启动前执行的系统命令", preRow));

        // 退出后命令
        String curPost = host.config().getOrDefault("PostExitCommand", "");
        TextField postField = new TextField(curPost);
        postField.getStyleClass().add("input-field");
        postField.setPrefWidth(300);
        Button savePost = AppIcons.button("save", "保存");
        savePost.getStyleClass().add("btn-primary");
        savePost.setOnAction(e -> {
            host.config().put("PostExitCommand", postField.getText());
            host.saveConfig();
            host.ui().toast("退出后命令已保存");
        });
        HBox postRow = new HBox(10, postField, savePost);
        root.getChildren().add(PageKit.settingsCard("退出后命令", "游戏退出后执行的系统命令", postRow));

        // 并行资源准备（加速启动：原生库与游戏资源补全并行执行）
        CheckBox parallelToggle = PageKit.toggle(ParallelLaunchManager.isEnabled());
        parallelToggle.setOnAction(e -> {
            host.config().put(ParallelLaunchManager.CONFIG_KEY, String.valueOf(parallelToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("并行资源准备: " + (parallelToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("并行资源准备",
                "启动时同时补全原生库与游戏资源，缩短启动等待时间", parallelToggle));

        // 并行文件校验（多线程校验已存在的资源文件，加快启动校验速度）
        CheckBox parallelVerifyToggle = PageKit.toggle(VerifySettings.isParallelVerifyEnabled());
        parallelVerifyToggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_PARALLEL, String.valueOf(parallelVerifyToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("并行文件校验: " + (parallelVerifyToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("并行文件校验",
                "多线程校验已存在的资源文件，加快启动校验速度", parallelVerifyToggle));

        // 流式哈希校验（分块读取计算 SHA-1，降低大文件内存占用）
        CheckBox streamingSha1Toggle = PageKit.toggle(VerifySettings.isStreamingSha1Enabled());
        streamingSha1Toggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_STREAMING, String.valueOf(streamingSha1Toggle.isSelected()));
            host.saveConfig();
            host.ui().toast("流式哈希校验: " + (streamingSha1Toggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("流式哈希校验",
                "分块读取计算文件哈希，降低大文件内存占用", streamingSha1Toggle));

        // 快速校验（文件大小一致即视为完整，不计算哈希；下载时仍完整校验）
        CheckBox fastVerifyToggle = PageKit.toggle(VerifySettings.isFastVerifyEnabled());
        fastVerifyToggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_FAST, String.valueOf(fastVerifyToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("快速校验: " + (fastVerifyToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("快速校验（跳过哈希）",
                "文件大小一致即视为完整，不计算哈希；下载时仍做完整性校验", fastVerifyToggle));

        // 静默登录时机（两种模式：启动游戏时静默登录 / 开启启动器静默登录）
        ComboBox<String> loginModeCombo = new ComboBox<>();
        loginModeCombo.getItems().addAll("启动游戏时静默登录", "开启启动器静默登录");
        String curLoginMode = host.config().getOrDefault(SilentLoginManager.CONFIG_KEY, SilentLoginManager.MODE_LAUNCH);
        loginModeCombo.setValue(SilentLoginManager.MODE_LAUNCH.equalsIgnoreCase(curLoginMode)
                ? "启动游戏时静默登录" : "开启启动器静默登录");
        loginModeCombo.getStyleClass().add("select-field");
        loginModeCombo.setOnAction(e -> {
            String val = loginModeCombo.getValue();
            if (val == null) return;
            boolean startupMode = "开启启动器静默登录".equals(val);
            host.config().put(SilentLoginManager.CONFIG_KEY,
                    startupMode ? SilentLoginManager.MODE_STARTUP : SilentLoginManager.MODE_LAUNCH);
            host.saveConfig();
            host.ui().toast("静默登录时机: " + (startupMode ? "开启启动器时" : "启动游戏时"));
        });
        root.getChildren().add(PageKit.settingsCard("静默登录时机",
                "微软账号令牌过期时自动静默刷新：启动游戏时刷新，或开启启动器时提前刷新（启动更快）", loginModeCombo));

        // ==================== 启动选项 ====================
        Label launchSection = new Label("启动选项");
        launchSection.getStyleClass().add("config-section-title");
        root.getChildren().add(launchSection);

        CheckBox gpuToggle = PageKit.toggle(PageKit.boolConfig(host, "UseHighPerformanceGPU", true));
        gpuToggle.setOnAction(e -> {
            boolean on = gpuToggle.isSelected();
            host.config().put("UseHighPerformanceGPU", String.valueOf(on));
            host.saveConfig();
            // 写入 Windows 注册表的显卡偏好，让系统为 Java 进程选用独显
            String javaPath = host.config().getOrDefault("JavaPath", "javaw");
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                boolean ok = com.example.starlight.util.MemoryOptimizer.setGpuPreference(javaPath, on);
                Platform.runLater(() -> host.ui().toast(on
                        ? (ok ? "已登记「使用高性能显卡」" : "登记失败（仅 Windows 支持，或 Java 路径无效）")
                        : (ok ? "已恢复系统自动选择显卡" : "恢复失败")));
            });
        });
        root.getChildren().add(PageKit.settingsCard("使用高性能显卡",
                "在 Windows 显卡设置中把 Java 进程登记为「高性能」，避免笔记本用核显跑游戏"
                        + "（需要 Java 路径为完整路径才生效）", gpuToggle));

        CheckBox preheatToggle = PageKit.toggle(PageKit.boolConfig(host, "JvmPreheat", false));
        preheatToggle.setOnAction(e -> {
            host.config().put("JvmPreheat", String.valueOf(preheatToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("JVM 预热启动: " + (preheatToggle.isSelected() ? "开启" : "关闭"));
            if (preheatToggle.isSelected()) host.preheatJvmAsync();
        });
        VBox preheatBox = new VBox(4, preheatToggle,
                PageKit.hintLabel("空闲时预加载 JVM，加速游戏首次启动（占用约 100MB 内存）"));
        root.getChildren().add(PageKit.settingsCard("JVM 预热启动",
                "启动器启动后在后台跑一次轻量 JVM，把运行时与类库读进系统缓存，"
                        + "让游戏首次启动更快；关闭启动器即回收", preheatBox));

        // ==================== 社区资源 ====================
        Label communitySection = new Label("社区资源");
        communitySection.getStyleClass().add("config-section-title");
        root.getChildren().add(communitySection);

        ComboBox<String> fileNameFormat = new ComboBox<>();
        fileNameFormat.getItems().addAll(ModFileFormatter.FILENAME_FORMATS);
        fileNameFormat.setValue(host.config().getOrDefault("ModFileNameFormat", ModFileFormatter.FILENAME_FORMATS[0]));
        fileNameFormat.getStyleClass().add("select-field");
        fileNameFormat.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        fileNameFormat.setOnAction(e -> {
            host.config().put("ModFileNameFormat", fileNameFormat.getValue());
            host.saveConfig();
            host.ui().toast("文件名格式已保存");
        });
        root.getChildren().add(PageKit.settingsCard("文件名格式",
                "下载模组时写入磁盘的文件名格式（示例：「" + ModFileFormatter.FILENAME_SAMPLE + "」）", fileNameFormat));

        ComboBox<String> modDisplayStyle = new ComboBox<>();
        modDisplayStyle.getItems().addAll(ModFileFormatter.DISPLAY_STYLES);
        modDisplayStyle.setValue(host.config().getOrDefault("ModDisplayStyle", ModFileFormatter.DISPLAY_STYLES[0]));
        modDisplayStyle.getStyleClass().add("select-field");
        modDisplayStyle.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        modDisplayStyle.setOnAction(e -> {
            host.config().put("ModDisplayStyle", modDisplayStyle.getValue());
            host.saveConfig();
            host.ui().toast("Mod 管理样式已保存");
        });
        root.getChildren().add(PageKit.settingsCard("Mod 管理样式",
                "模组列表里标题显示译名还是文件名，详情页显示另一种", modDisplayStyle));

        CheckBox ignoreQuiltToggle = PageKit.toggle(PageKit.boolConfig(host, "IgnoreQuiltLoader", false));
        ignoreQuiltToggle.setOnAction(e -> {
            host.config().put("IgnoreQuiltLoader", String.valueOf(ignoreQuiltToggle.isSelected()));
            host.saveConfig();
            host.ui().toast("忽略 Quilt: " + (ignoreQuiltToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("在显示 Mod 加载器时忽略 Quilt",
                "开启后模组详情页与筛选里不再显示 Quilt 加载器", ignoreQuiltToggle));

        // ==================== 内存优化 ====================
        root.getChildren().add(host.buildMemoryOptimizeCard());

        return root;
    }

}



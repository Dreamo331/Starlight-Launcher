package com.example.starlight.newui.page.settings;

import com.example.starlight.config.AiConfig;
import com.example.starlight.newui.ai.AiModelPickerDialog;
import com.example.starlight.newui.ai.AiProviders;
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
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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

        // ==================== AI 配置 ====================
        Label aiSection = new Label("AI 配置");
        aiSection.getStyleClass().add("config-section-title");
        root.getChildren().add(aiSection);

        addAiConfigCards(root);

        return root;
    }

    // ==================== AI 配置 ====================

    /**
     * AI 配置区：预设服务商 + API 地址 + API Key + 模型。
     *
     * <p>启动器里的 AI 只服务一件事——<b>分析错误脚本</b>：把崩溃日志 / 错误日志交给大模型，
     * 让它给出错误概述、可能原因与解决步骤（见 {@code AIDiagnosisPanel} 与 {@code AIService}）。
     * 所以这里的配置从<b>空</b>开始：不带内置密钥、不带默认模型，谁用谁填。
     *
     * <p>填起来只要两步：点一个服务商图标（自动补 API 地址 + 默认模型），再贴自己的 API Key。
     * 模型栏旁边是「选择模型」，会打开启动器内置的伪弹窗（遮罩 + 卡片），
     * 既能在线问服务商的 {@code /models} 拉取完整模型列表，也能直接用内置常见模型兜底。
     *
     * <p>三项配置都写进 {@code starlight-client.ini}（键名见 {@link AiConfig}），
     * 由 {@code LongCatChat} 在真正发起请求时读取，改完即刻生效、无需重启。
     */
    private void addAiConfigCards(VBox root) {
        // 三个输入框先建出来：服务商图标点完要回填地址与模型
        TextField urlField = new TextField(host.config().getOrDefault(AiConfig.KEY_BASE_URL, ""));
        urlField.getStyleClass().add("input-field");
        urlField.setPrefWidth(300);
        // 窄窗口下别被挤到看不清地址（宁可压缩左侧说明，说明本来就会换行）
        urlField.setMinWidth(180);
        urlField.setPromptText("https://api.deepseek.com/v1");

        TextField keyField = new TextField(host.config().getOrDefault(AiConfig.KEY_API_KEY, ""));
        keyField.getStyleClass().add("input-field");
        keyField.setPrefWidth(300);
        keyField.setMinWidth(180);
        keyField.setPromptText("sk-…");

        TextField modelField = new TextField(host.config().getOrDefault(AiConfig.KEY_MODEL, ""));
        modelField.getStyleClass().add("input-field");
        modelField.setMaxWidth(Double.MAX_VALUE);
        modelField.setPromptText("deepseek-chat");
        HBox.setHgrow(modelField, Priority.ALWAYS);

        // ===== 1. 预设服务商：点图标即填入 API 地址 + 默认模型 =====
        FlowPane providerRow = new FlowPane(8, 8);
        providerRow.setPrefWrapLength(PageKit.SETTINGS_CONTROL_WIDTH);
        List<VBox> chips = new ArrayList<>();
        List<String> chipIds = new ArrayList<>();
        String curProvider = host.config().getOrDefault(AiConfig.KEY_PROVIDER, "");
        for (AiProviders.Provider p : AiProviders.ALL) {
            VBox chip = buildAiProviderChip(p);
            chips.add(chip);
            chipIds.add(p.id());
            chip.setOnMouseClicked(e -> {
                // 地址与模型都填成该服务商的推荐值，用户只剩「贴 Key」一步
                urlField.setText(p.baseUrl());
                modelField.setText(p.defaultModel());
                host.config().put(AiConfig.KEY_PROVIDER, p.id());
                host.config().put(AiConfig.KEY_BASE_URL, p.baseUrl());
                host.config().put(AiConfig.KEY_MODEL, p.defaultModel());
                for (int i = 0; i < chips.size(); i++) {
                    markAiChipSelected(chips.get(i), chipIds.get(i).equals(p.id()));
                }
                host.saveConfig();
                host.ui().toast("已选择 " + p.name() + "，接着填 API Key 即可");
            });
            markAiChipSelected(chip, p.id().equalsIgnoreCase(curProvider));
            providerRow.getChildren().add(chip);
        }
        VBox providerBox = new VBox(8, providerRow, PageKit.hintLabel(
                "点图标自动填入 API 地址与推荐模型，再贴自己的 API Key 就能用；"
                        + "地址随时可以手动改（中转站、本地推理服务都行）"));
        root.getChildren().add(PageKit.settingsCardStacked("服务商预设",
                "内置 " + AiProviders.ALL.size() + " 家常见服务商（图标取自启动器内置 logo）", providerBox));

        // ===== 2. API 地址 =====
        Button saveAiApi = AppIcons.button("save", "保存");
        saveAiApi.getStyleClass().add("btn-primary");
        saveAiApi.setOnAction(e -> {
            host.config().put(AiConfig.KEY_BASE_URL, urlField.getText().trim());
            host.saveConfig();
            host.ui().toast("AI API 地址已保存");
        });
        HBox aiApiRow = new HBox(10, urlField, saveAiApi);
        aiApiRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("API 地址",
                "OpenAI 兼容接口基址（不含 /chat/completions），例如 https://api.deepseek.com/v1", aiApiRow));

        // ===== 3. API Key =====
        Button saveAiApiKey = AppIcons.button("save", "保存");
        saveAiApiKey.getStyleClass().add("btn-primary");
        saveAiApiKey.setOnAction(e -> {
            host.config().put(AiConfig.KEY_API_KEY, keyField.getText().trim());
            host.saveConfig();
            host.ui().toast("AI API Key 已保存");
        });
        HBox aiapiKeyRow = new HBox(10, keyField, saveAiApiKey);
        aiapiKeyRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("API Key",
                "服务商控制台申请的密钥：只写在本机 starlight-client.ini，只会发给你填写的接口地址", aiapiKeyRow));

        // ===== 4. 模型（内置伪弹窗选择，支持在线获取模型列表） =====
        Button pickModel = AppIcons.button("list", "选择模型");
        pickModel.getStyleClass().add("btn-primary");
        pickModel.setMinWidth(Region.USE_PREF_SIZE);
        pickModel.setOnAction(e -> AiModelPickerDialog.show(host,
                urlField.getText().trim(), keyField.getText().trim(), modelField.getText().trim(),
                picked -> {
                    modelField.setText(picked);
                    // 顺手把地址与 Key 一起落盘，免得用户改了地址却被当成没生效
                    host.config().put(AiConfig.KEY_BASE_URL, urlField.getText().trim());
                    host.config().put(AiConfig.KEY_API_KEY, keyField.getText().trim());
                    host.config().put(AiConfig.KEY_MODEL, picked);
                    AiProviders.Provider match = AiProviders.matchByBaseUrl(urlField.getText());
                    if (match != null) host.config().put(AiConfig.KEY_PROVIDER, match.id());
                    host.saveConfig();
                    host.ui().toast("已选择模型: " + picked);
                }));

        Button saveAiModel = AppIcons.button("save", "保存");
        saveAiModel.getStyleClass().add("btn-primary");
        saveAiModel.setOnAction(e -> {
            host.config().put(AiConfig.KEY_MODEL, modelField.getText().trim());
            host.saveConfig();
            host.ui().toast("AI 模型已保存");
        });

        HBox modelRow = new HBox(10, modelField, pickModel, saveAiModel);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        VBox modelBox = new VBox(8, modelRow, PageKit.hintLabel(aiConfigSummary()));
        root.getChildren().add(PageKit.settingsCardStacked("模型",
                "「AI 分析错误脚本」（崩溃日志诊断）时调用的模型；不确定就点「选择模型」在线获取列表",
                modelBox));
    }

    /**
     * 服务商图标卡片：白底圆角 logo 板 + 名称。
     *
     * <p>logo 底板固定白色：OpenAI 这类纯黑 logo 直接放在深色主题上会糊成一片，
     * 垫一层浅色底板后深浅主题都能看清；图标缺失时退化成通用图标，不留裂图。
     */
    private VBox buildAiProviderChip(AiProviders.Provider p) {
        Node visual;
        Image logo = AiProviders.logo(p.logo());
        if (logo != null) {
            ImageView iv = new ImageView(logo);
            iv.setFitWidth(22);
            iv.setFitHeight(22);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            visual = iv;
        } else {
            visual = AppIcons.icon("sparkles", 18, Color.web("#3b82f6"));
        }

        StackPane tile = new StackPane(visual);
        tile.setMinSize(34, 34);
        tile.setPrefSize(34, 34);
        tile.setMaxSize(34, 34);
        tile.setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-background-radius: 9;");

        Label nameLabel = new Label(p.name());
        nameLabel.getStyleClass().add("theme-label");

        VBox chip = new VBox(6, tile, nameLabel);
        chip.setAlignment(Pos.CENTER);
        chip.setMinWidth(84);
        chip.getStyleClass().add("theme-option");   // 复用主题选择器的悬停 / 主题色样式
        chip.setCursor(Cursor.HAND);
        Tooltip.install(chip, new Tooltip(p.name() + "\n" + p.baseUrl() + "\n推荐模型：" + p.defaultModel()));
        return chip;
    }

    /** 高亮当前选中的服务商图标（selected 类管文字色，内联样式管底板） */
    private void markAiChipSelected(VBox chip, boolean selected) {
        if (selected) {
            if (!chip.getStyleClass().contains("selected")) chip.getStyleClass().add("selected");
            chip.setStyle("-fx-background-color: rgba(59,130,246,0.14); -fx-border-color: rgba(59,130,246,0.45);"
                    + " -fx-border-width: 1; -fx-background-radius: 10; -fx-border-radius: 10;");
        } else {
            chip.getStyleClass().remove("selected");
            chip.setStyle("");
        }
    }

    /** 模型卡片下方的一行状态说明：还差什么 / 已经配成什么样（不发网络请求，只读配置） */
    private String aiConfigSummary() {
        String url = host.config().getOrDefault(AiConfig.KEY_BASE_URL, "").trim();
        String model = host.config().getOrDefault(AiConfig.KEY_MODEL, "").trim();
        if (url.isEmpty() && model.isEmpty()) {
            return "默认留空：不配置就不会启用 AI 诊断，也不会把任何日志发出去";
        }
        if (url.isEmpty()) {
            return "还差 API 地址：点上方的服务商图标可以一键填入";
        }
        if (model.isEmpty()) {
            return "还差模型：点「选择模型」挑一个，或直接手打模型名";
        }
        return "已配置：" + url + " · " + model + "（崩溃诊断时会把日志发送给该服务商）";
    }

}



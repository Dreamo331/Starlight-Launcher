package com.example.starlight.newui.page.settings;

import com.example.starlight.config.AiConfig;
import com.example.starlight.config.SelectedVersion;
import com.example.starlight.newui.ai.AiModelPickerDialog;
import com.example.starlight.newui.ai.AiProviders;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.LauncherVisibility;
import com.example.starlight.lang.GameLanguage;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.modfile.ModFileFormatter;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.service.ParallelLaunchManager;
import com.example.starlight.service.SilentLoginManager;
import com.example.starlight.util.I18N;
import com.example.starlight.download.VerifySettings;
import com.example.starlight.util.GameProcessTweaks;

import javafx.application.Platform;
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
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.example.starlight.version.VersionDownloadService;
import javafx.scene.layout.FlowPane;

/**
 * 设置 → 高级设置页（重构 §6.6：分区折叠 + 去重）。
 *
 * <p>分区：启动选项 / 窗口 / 游戏默认值 / 性能与校验 / 社区资源 / 登录 / AI 配置。
 * 「启动选项」「窗口」默认展开，其余默认收起。内存优化卡已迁至「Java 与内存」页（§6.6）。
 */
public final class AdvancedPage {

    private final LauncherContext host;

    /** 折叠分区展开状态（同一会话内保持，页面重建时沿用；重构 §8） */
    private final java.util.Map<String, Boolean> sectionOpen = new java.util.HashMap<>();


    public AdvancedPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(host, I18N.get("advanced.t0001"));

        // 分区 1：启动选项（默认展开，重构 §6.6 验收） //
        VBox startupBox = new VBox(12,
                gpuCard(),
                preheatCard());
        root.getChildren().add(section("startup", I18N.get("advanced.t0002"),
                I18N.get("advanced.t0003"), startupBox, true));

        // 分区 2：窗口（默认展开） //
        VBox windowBox = new VBox(12,
                windowSizeCard(),
                fullscreenCard(),
                windowTitleCard(),
                priorityCard(),
                visibilityCard());
        root.getChildren().add(section("window", I18N.get("advanced.t0004"),
                I18N.get("advanced.t0005"), windowBox, true));

        // 分区 3：游戏默认值（全局默认，可被版本独立设置覆盖） //
        VBox defaultsBox = new VBox(12,
                gameLangCard(),
                versionIsolationCard(),
                quickSaveCard(),
                gameArgsCard(),
                preLaunchCard(),
                postExitCard());
        root.getChildren().add(section("defaults", I18N.get("advanced.t0006"),
                I18N.get("advanced.t0007"), defaultsBox, false));

        // 分区 4：性能与校验 //
        VBox perfBox = new VBox(12,
                parallelCard(),
                parallelVerifyCard(),
                streamingSha1Card(),
                fastVerifyCard(),
                stateCacheCard());
        root.getChildren().add(section("perf", I18N.get("advanced.t0008"),
                I18N.get("advanced.t0009"), perfBox, false));

        // 分区 5：社区资源 //
        VBox communityBox = new VBox(12,
                fileNameFormatCard(),
                modDisplayStyleCard(),
                ignoreQuiltCard());
        root.getChildren().add(section("community", I18N.get("advanced.t0010"),
                I18N.get("advanced.t0011"), communityBox, false));

        // 分区 6：登录 //
        VBox loginBox = new VBox(12, loginModeCard());
        root.getChildren().add(section("login", I18N.get("advanced.t0012"),
                I18N.get("advanced.t0013"), loginBox, false));

        // 分区 7：AI 配置 //
        VBox aiBox = new VBox(12);
        addAiConfigCards(aiBox);
        root.getChildren().add(section("ai", I18N.get("advanced.t0014"),
                I18N.get("advanced.t0015"), aiBox, false));

        return root;
    }

    /** 包装一个折叠分区（展开状态记录到 sectionOpen，同一会话保持） */
    private Node section(String key, String title, String summary, VBox content, boolean defaultOpen) {
        boolean open = sectionOpen.getOrDefault(key, defaultOpen);
        return PageKit.collapsibleSection(title, summary, content, open,
                nowOpen -> sectionOpen.put(key, nowOpen));
    }

    // —— 启动选项 —— //

    private Node gpuCard() {
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
                        ? (ok ? I18N.get("advanced.t0016") : I18N.get("advanced.t0017"))
                        : (ok ? I18N.get("advanced.t0018") : I18N.get("advanced.t0019"))));
            });
        });
        return PageKit.settingsCard(I18N.get("advanced.t0020"),
                I18N.get("advanced.t0109"), gpuToggle);
    }

    private Node preheatCard() {
        CheckBox preheatToggle = PageKit.toggle(PageKit.boolConfig(host, "JvmPreheat", false));
        preheatToggle.setOnAction(e -> {
            host.config().put("JvmPreheat", String.valueOf(preheatToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0004", (preheatToggle.isSelected() ? I18N.get("advanced.t0021") : I18N.get("advanced.t0022"))));
            if (preheatToggle.isSelected()) host.preheatJvmAsync();
        });
        // 卡片说明已经讲了「空闲时预加载 JVM、约 100MB 内存」，
        // 右侧不再重复挂一条 hint：英文变长后它会被挤成一列竖排文字（历史样式问题）
        VBox preheatBox = new VBox(4, preheatToggle);
        return PageKit.settingsCard(I18N.get("advanced.t0024"),
                I18N.get("advanced.t0110"), preheatBox);
    }

    // —— 窗口 —— //

    private Node windowSizeCard() {
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
        Button saveWin = AppIcons.button("save", I18N.get("advanced.t0025"));
        saveWin.getStyleClass().add("btn-primary");
        saveWin.setOnAction(e -> {
            host.config().put("WindowWidth", widthField.getText());
            host.config().put("WindowHeight", heightField.getText());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0026"));
        });
        HBox winRow = new HBox(10, new Label(I18N.get("advanced.t0027")), widthField, xLabel, new Label(I18N.get("advanced.t0028")), heightField, saveWin);
        winRow.setAlignment(Pos.CENTER_LEFT);
        return PageKit.settingsCard(I18N.get("advanced.t0029"),
                I18N.get("advanced.t0030"), winRow);
    }

    private Node fullscreenCard() {
        CheckBox fullscreenToggle = PageKit.toggle("true".equals(host.config().getOrDefault("Fullscreen", "false")));
        fullscreenToggle.setOnAction(e -> {
            host.config().put("Fullscreen", String.valueOf(fullscreenToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0005", (fullscreenToggle.isSelected() ? I18N.get("advanced.t0031") : I18N.get("advanced.t0032"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0033"), I18N.get("advanced.t0034"), fullscreenToggle);
    }

    private Node windowTitleCard() {
        // 自定义游戏窗口标题（支持 {version} / {username} 变量）
        TextField titleField = new TextField(host.config().getOrDefault(GameProcessTweaks.CONFIG_WINDOW_TITLE, ""));
        titleField.getStyleClass().add("input-field");
        titleField.setPrefWidth(300);
        titleField.setMinWidth(200);
        titleField.setMaxWidth(Double.MAX_VALUE);
        titleField.setPromptText(I18N.get("advanced.t0035"));
        // 实时预览：输入时按当前版本/玩家名展开变量，用户不用保存再启动游戏来验证写法
        Label previewLabel = new Label();
        previewLabel.getStyleClass().add("settings-card-desc");
        previewLabel.setWrapText(true);
        previewLabel.setVisible(false);
        previewLabel.setManaged(false);
        Runnable updatePreview = () -> {
            String expanded = GameProcessTweaks.expandTitle(titleField.getText(),
                    SelectedVersion.of(host.config()),
                    host.config().getOrDefault("UserName", ""));
            boolean show = !expanded.isEmpty();
            previewLabel.setText(show ? I18N.get("advanced.k0006", expanded) : "");
            previewLabel.setVisible(show);
            previewLabel.setManaged(show);
        };
        titleField.textProperty().addListener((obs, old, now) -> updatePreview.run());
        updatePreview.run();
        Button saveTitle = AppIcons.button("save", I18N.get("advanced.t0036"));
        saveTitle.getStyleClass().add("btn-primary");
        Runnable commitTitle = () -> {
            // 模板原样保存（变量在启动时才展开），只做规范化（去换行 / 限长）
            String value = GameProcessTweaks.normalizeTitle(titleField.getText());
            titleField.setText(value);
            host.config().put(GameProcessTweaks.CONFIG_WINDOW_TITLE, value);
            host.saveConfig();
            String expanded = GameProcessTweaks.expandTitle(value,
                    SelectedVersion.of(host.config()),
                    host.config().getOrDefault("UserName", ""));
            host.ui().toast(value.isEmpty()
                    ? I18N.get("advanced.t0037")
                    : I18N.get("advanced.k0001", expanded));
            updatePreview.run();
        };
        saveTitle.setOnAction(e -> commitTitle.run());
        // 回车即保存，与其它输入框一致
        titleField.setOnAction(e -> commitTitle.run());
        HBox titleRow = new HBox(10, titleField, saveTitle);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleField, Priority.ALWAYS);
        VBox titleBox = new VBox(8, titleRow, previewLabel);
        return PageKit.settingsCardStacked(I18N.get("advanced.t0038"),
                I18N.get("advanced.p0115", GameProcessTweaks.VAR_VERSION, GameProcessTweaks.VAR_USERNAME),
                titleBox);
    }

    private Node priorityCard() {
        ComboBox<String> priorityCombo = new ComboBox<>();
        for (String[] option : GameProcessTweaks.PRIORITY_OPTIONS) {
            priorityCombo.getItems().add(option[1]);
        }
        priorityCombo.setValue(GameProcessTweaks.priorityLabel(
                host.config().getOrDefault(GameProcessTweaks.CONFIG_PRIORITY, GameProcessTweaks.PRIORITY_NORMAL)));
        priorityCombo.getStyleClass().add("select-field");
        priorityCombo.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        priorityCombo.setOnAction(e -> {
            String val = priorityCombo.getValue();
            if (val == null) return;
            host.config().put(GameProcessTweaks.CONFIG_PRIORITY, GameProcessTweaks.priorityValueOf(val));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.p0116", val));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0039"),
                I18N.get("advanced.t0111"),
                priorityCombo);
    }

    private Node visibilityCard() {
        ComboBox<String> visibilityCombo = new ComboBox<>();
        for (String[] option : LauncherVisibility.OPTIONS) {
            visibilityCombo.getItems().add(option[1]);
        }
        visibilityCombo.setValue(LauncherVisibility.labelOf(
                host.config().getOrDefault(LauncherVisibility.KEY, LauncherVisibility.KEEP)));
        visibilityCombo.getStyleClass().add("select-field");
        visibilityCombo.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        visibilityCombo.setOnAction(e -> {
            String val = visibilityCombo.getValue();
            if (val == null) return;
            host.config().put(LauncherVisibility.KEY, LauncherVisibility.valueOfLabel(val));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0002", val));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0040"),
                I18N.get("advanced.t0112"),
                visibilityCombo);
    }

    // —— 游戏默认值 —— //

    private Node gameLangCard() {
        ComboBox<String> gameLangCombo = new ComboBox<>();
        String playerFollow = I18N.get("advanced.t0041");
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
            host.ui().toast(I18N.get("advanced.k0007", (follow ? I18N.get("advanced.t0042") : val)));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0043"),
                I18N.get("advanced.t0044"), gameLangCombo);
    }

    private Node versionIsolationCard() {
        CheckBox viToggle = PageKit.toggle("true".equals(host.config().getOrDefault("VersionIsolation", "false")));
        viToggle.setOnAction(e -> {
            host.config().put("VersionIsolation", String.valueOf(viToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0008", (viToggle.isSelected() ? I18N.get("advanced.t0045") : I18N.get("advanced.t0046"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0047"), I18N.get("advanced.t0048"), viToggle);
    }

    private Node quickSaveCard() {
        CheckBox quickSaveToggle = PageKit.toggle("true".equals(host.config().getOrDefault("HomeQuickLaunchSave", "false")));
        quickSaveToggle.setOnAction(e -> {
            host.config().put("HomeQuickLaunchSave", String.valueOf(quickSaveToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0009", (quickSaveToggle.isSelected() ? I18N.get("advanced.t0049") : I18N.get("advanced.t0050"))));
            // 立即重建主页（当前停留在主页时原地刷新）
            host.refreshHomePage();
        });
        return PageKit.settingsCard(I18N.get("advanced.t0051"),
                I18N.get("advanced.t0052"),
                quickSaveToggle);
    }

    private Node gameArgsCard() {
        String curGameArgs = host.config().getOrDefault("GameArgs", "");
        TextField gameArgsField = new TextField(curGameArgs);
        gameArgsField.getStyleClass().add("input-field");
        gameArgsField.setPrefWidth(300);
        Button saveGameArgs = AppIcons.button("save", I18N.get("advanced.t0053"));
        saveGameArgs.getStyleClass().add("btn-primary");
        saveGameArgs.setOnAction(e -> {
            host.config().put("GameArgs", gameArgsField.getText());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0054"));
        });
        HBox gameArgsRow = new HBox(10, gameArgsField, saveGameArgs);
        return PageKit.settingsCard(I18N.get("advanced.t0055"), I18N.get("advanced.t0056"), gameArgsRow);
    }

    private Node preLaunchCard() {
        String curPre = host.config().getOrDefault("PreLaunchCommand", "");
        TextField preField = new TextField(curPre);
        preField.getStyleClass().add("input-field");
        preField.setPrefWidth(300);
        Button savePre = AppIcons.button("save", I18N.get("advanced.t0057"));
        savePre.getStyleClass().add("btn-primary");
        savePre.setOnAction(e -> {
            host.config().put("PreLaunchCommand", preField.getText());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0058"));
        });
        HBox preRow = new HBox(10, preField, savePre);
        return PageKit.settingsCard(I18N.get("advanced.t0059"), I18N.get("advanced.t0060"), preRow);
    }

    private Node postExitCard() {
        String curPost = host.config().getOrDefault("PostExitCommand", "");
        TextField postField = new TextField(curPost);
        postField.getStyleClass().add("input-field");
        postField.setPrefWidth(300);
        Button savePost = AppIcons.button("save", I18N.get("advanced.t0061"));
        savePost.getStyleClass().add("btn-primary");
        savePost.setOnAction(e -> {
            host.config().put("PostExitCommand", postField.getText());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0062"));
        });
        HBox postRow = new HBox(10, postField, savePost);
        return PageKit.settingsCard(I18N.get("advanced.t0063"), I18N.get("advanced.t0064"), postRow);
    }

    // —— 性能与校验 —— //

    private Node parallelCard() {
        CheckBox parallelToggle = PageKit.toggle(ParallelLaunchManager.isEnabled());
        parallelToggle.setOnAction(e -> {
            host.config().put(ParallelLaunchManager.CONFIG_KEY, String.valueOf(parallelToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0010", (parallelToggle.isSelected() ? I18N.get("advanced.t0065") : I18N.get("advanced.t0066"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0067"),
                I18N.get("advanced.t0068"), parallelToggle);
    }

    private Node parallelVerifyCard() {
        CheckBox parallelVerifyToggle = PageKit.toggle(VerifySettings.isParallelVerifyEnabled());
        parallelVerifyToggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_PARALLEL, String.valueOf(parallelVerifyToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0011", (parallelVerifyToggle.isSelected() ? I18N.get("advanced.t0069") : I18N.get("advanced.t0070"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0071"),
                I18N.get("advanced.t0072"), parallelVerifyToggle);
    }

    private Node streamingSha1Card() {
        CheckBox streamingSha1Toggle = PageKit.toggle(VerifySettings.isStreamingSha1Enabled());
        streamingSha1Toggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_STREAMING, String.valueOf(streamingSha1Toggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0012", (streamingSha1Toggle.isSelected() ? I18N.get("advanced.t0073") : I18N.get("advanced.t0074"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0075"),
                I18N.get("advanced.t0076"), streamingSha1Toggle);
    }

    private Node fastVerifyCard() {
        CheckBox fastVerifyToggle = PageKit.toggle(VerifySettings.isFastVerifyEnabled());
        fastVerifyToggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_FAST, String.valueOf(fastVerifyToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0013", (fastVerifyToggle.isSelected() ? I18N.get("advanced.t0077") : I18N.get("advanced.t0078"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0079"),
                I18N.get("advanced.t0080"), fastVerifyToggle);
    }

    private Node stateCacheCard() {
        CheckBox stateCacheToggle = PageKit.toggle(VerifySettings.isStateCacheEnabled());
        stateCacheToggle.setOnAction(e -> {
            host.config().put(VerifySettings.KEY_STATE_CACHE, String.valueOf(stateCacheToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0014", (stateCacheToggle.isSelected() ? I18N.get("advanced.t0081") : I18N.get("advanced.t0082"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0083"),
                I18N.get("advanced.t0113"), stateCacheToggle);
    }

    // —— 登录 —— //

    private Node loginModeCard() {
        // 下拉项直接承载配置值（MODE_LAUNCH / MODE_STARTUP），显示名走 cellFactory。
        // 旧实现存的是展示文案，再用中文 equals 反推配置值——界面语言一换就永远不成立，
        // 静默登录模式会被悄悄写成另一个值，且不报任何错。
        ComboBox<String> loginModeCombo = new ComboBox<>();
        loginModeCombo.getItems().addAll(SilentLoginManager.MODE_LAUNCH, SilentLoginManager.MODE_STARTUP);
        loginModeCombo.setCellFactory(cb -> silentLoginCell());
        loginModeCombo.setButtonCell(silentLoginCell());
        String curLoginMode = host.config().getOrDefault(SilentLoginManager.CONFIG_KEY, SilentLoginManager.MODE_LAUNCH);
        loginModeCombo.setValue(SilentLoginManager.MODE_STARTUP.equalsIgnoreCase(curLoginMode)
                ? SilentLoginManager.MODE_STARTUP : SilentLoginManager.MODE_LAUNCH);
        loginModeCombo.getStyleClass().add("select-field");
        loginModeCombo.setOnAction(e -> {
            String val = loginModeCombo.getValue();
            if (val == null) return;
            boolean startupMode = SilentLoginManager.MODE_STARTUP.equalsIgnoreCase(val);
            host.config().put(SilentLoginManager.CONFIG_KEY,
                    startupMode ? SilentLoginManager.MODE_STARTUP : SilentLoginManager.MODE_LAUNCH);
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.toastSilentLoginTiming",
                    I18N.get(startupMode ? "advanced.silentLoginStartup" : "advanced.silentLoginAtLaunch")));
        });
        return PageKit.settingsCard(I18N.get("advanced.silentLoginTiming"),
                I18N.get("advanced.silentLoginTimingDesc"), loginModeCombo);
    }

    /** 静默登录模式下拉的显示单元：值仍是配置值，只把值渲染成当前语言的文案 */
    private javafx.scene.control.ListCell<String> silentLoginCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(I18N.get(SilentLoginManager.MODE_STARTUP.equalsIgnoreCase(item)
                        ? "advanced.silentLoginStartup" : "advanced.silentLoginAtLaunch"));
            }
        };
    }

    // —— 社区资源 —— //

    private Node fileNameFormatCard() {
        ComboBox<String> fileNameFormat = new ComboBox<>();
        fileNameFormat.getItems().addAll(ModFileFormatter.FILENAME_FORMATS);
        fileNameFormat.setValue(host.config().getOrDefault("ModFileNameFormat", ModFileFormatter.FILENAME_FORMATS[0]));
        fileNameFormat.getStyleClass().add("select-field");
        fileNameFormat.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        fileNameFormat.setOnAction(e -> {
            host.config().put("ModFileNameFormat", fileNameFormat.getValue());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0084"));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0085"),
                I18N.get("advanced.k0015", ModFileFormatter.filenameSample() + "」）"), fileNameFormat);
    }

    private Node modDisplayStyleCard() {
        // 下拉项承载**稳定 style key**（配置值），显示名由 cellFactory 翻译。
        // 旧实现把中文文案直接当配置值：界面语言一变，配置里存的旧中文值
        // 与可选项对不上，用户设置会静默丢失。normalizeStyle 负责兼容历史值。
        ComboBox<String> modDisplayStyle = new ComboBox<>();
        modDisplayStyle.getItems().addAll(ModFileFormatter.DISPLAY_STYLE_KEYS);
        modDisplayStyle.setValue(ModFileFormatter.normalizeStyle(
                host.config().getOrDefault("ModDisplayStyle", ModFileFormatter.DISPLAY_STYLE_KEYS[0])));
        modDisplayStyle.setCellFactory(cb -> displayStyleCell());
        modDisplayStyle.setButtonCell(displayStyleCell());
        modDisplayStyle.getStyleClass().add("select-field");
        modDisplayStyle.setPrefWidth(PageKit.SETTINGS_CONTROL_WIDTH);
        modDisplayStyle.setOnAction(e -> {
            host.config().put("ModDisplayStyle", modDisplayStyle.getValue());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0086"));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0087"),
                I18N.get("advanced.t0088"), modDisplayStyle);
    }

    /** Mod 显示样式下拉的显示单元：值仍是 style key，只把 key 渲染成当前语言的文案 */
    private static javafx.scene.control.ListCell<String> displayStyleCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(I18N.get(ModFileFormatter.styleLabelKey(item)));
            }
        };
    }

    private Node ignoreQuiltCard() {
        CheckBox ignoreQuiltToggle = PageKit.toggle(PageKit.boolConfig(host, "IgnoreQuiltLoader", false));
        ignoreQuiltToggle.setOnAction(e -> {
            host.config().put("IgnoreQuiltLoader", String.valueOf(ignoreQuiltToggle.isSelected()));
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.k0016", (ignoreQuiltToggle.isSelected() ? I18N.get("advanced.t0089") : I18N.get("advanced.t0090"))));
        });
        return PageKit.settingsCard(I18N.get("advanced.t0091"),
                I18N.get("advanced.t0092"), ignoreQuiltToggle);
    }

    // —— AI 配置 —— //

    /**
     * AI 配置区：预设服务商 + API 地址 + API Key + 模型（纵向一步一行，重构 §6.6 验收）。
     *
     * <p>启动器里的 AI 只服务一件事——<b>分析错误脚本</b>：把崩溃日志 / 错误日志交给大模型，
     * 让它给出错误概述、可能原因与解决步骤（见 {@code AIDiagnosisPanel} 与 {@code AIService}）。
     * 所以这里的配置从<b>空</b>开始：不带内置密钥、不带默认模型，谁用谁填。
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

        // 1. 预设服务商：点图标即填入 API 地址 + 默认模型 //
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
                host.ui().toast(I18N.get("advanced.p0117", p.name()));
            });
            markAiChipSelected(chip, p.id().equalsIgnoreCase(curProvider));
            providerRow.getChildren().add(chip);
        }
        VBox providerBox = new VBox(8, providerRow, PageKit.hintLabel(
                I18N.get("advanced.t0114")));
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("advanced.t0093"),
                I18N.get("advanced.p0118", AiProviders.ALL.size()), providerBox));

        // 2. API 地址 //
        Button saveAiApi = AppIcons.button("save", I18N.get("advanced.t0094"));
        saveAiApi.getStyleClass().add("btn-primary");
        saveAiApi.setOnAction(e -> {
            host.config().put(AiConfig.KEY_BASE_URL, urlField.getText().trim());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0095"));
        });
        HBox aiApiRow = new HBox(10, urlField, saveAiApi);
        aiApiRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard(I18N.get("advanced.t0096"),
                I18N.get("advanced.t0097"), aiApiRow));

        // 3. API Key //
        Button saveAiApiKey = AppIcons.button("save", I18N.get("advanced.t0098"));
        saveAiApiKey.getStyleClass().add("btn-primary");
        saveAiApiKey.setOnAction(e -> {
            host.config().put(AiConfig.KEY_API_KEY, keyField.getText().trim());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0099"));
        });
        HBox aiapiKeyRow = new HBox(10, keyField, saveAiApiKey);
        aiapiKeyRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("API Key",
                I18N.get("advanced.t0100"), aiapiKeyRow));

        // 4. 模型（内置伪弹窗选择，支持在线获取模型列表） //
        Button pickModel = AppIcons.button("list", I18N.get("advanced.t0101"));
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
                    host.ui().toast(I18N.get("advanced.k0003", picked));
                }));

        Button saveAiModel = AppIcons.button("save", I18N.get("advanced.t0102"));
        saveAiModel.getStyleClass().add("btn-primary");
        saveAiModel.setOnAction(e -> {
            host.config().put(AiConfig.KEY_MODEL, modelField.getText().trim());
            host.saveConfig();
            host.ui().toast(I18N.get("advanced.t0103"));
        });

        HBox modelRow = new HBox(10, modelField, pickModel, saveAiModel);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        VBox modelBox = new VBox(8, modelRow, PageKit.hintLabel(aiConfigSummary()));
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("advanced.t0104"),
                I18N.get("advanced.t0105"),
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
        Tooltip.install(chip, new Tooltip(I18N.get("advanced.r0001", p.name(), p.baseUrl(), p.defaultModel())));
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
            return I18N.get("advanced.t0106");
        }
        if (url.isEmpty()) {
            return I18N.get("advanced.t0107");
        }
        if (model.isEmpty()) {
            return I18N.get("advanced.t0108");
        }
        return I18N.get("advanced.p0119", url, model);
    }

}

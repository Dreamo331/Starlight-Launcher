package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.main.ConfigManager;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
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
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.listjava.FindAllJavaWindows;
import com.example.starlight.listjava.JavaCacheManager;
import javafx.scene.layout.FlowPane;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;

/** 
设置 → Java 虚拟机与内存页（Java 路径选择 / 内存分配 / 参数）
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class JvmPage {

    private final LauncherContext host;


    public JvmPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(host, I18N.get("jvm.t0001"));

        // 卡片「Java 运行环境」（重构 §6.3：自动选择开关在路径控件上方，开启时路径行整组置灰） //
        // 自动选择 Java（参考 HMCL：根据游戏版本自动匹配本机已安装的 Java）
        boolean autoJava = "true".equalsIgnoreCase(host.config().getOrDefault("AutoJava", "true"));
        CheckBox autoJavaToggle = PageKit.toggle(autoJava);

        // Java 路径：可编辑下拉框，自动搜索本机所有 Java 安装（参考 HMCL JavaManagementPage 交互）
        ComboBox<JavaOption> javaCombo = new ComboBox<>();
        javaCombo.setEditable(true);
        javaCombo.setPrefWidth(200);
        javaCombo.setMinWidth(180);
        javaCombo.getStyleClass().add("select-field");
        javaCombo.setPromptText(I18N.get("jvm.t0002"));
        HBox.setHgrow(javaCombo, Priority.ALWAYS);

        // 双向转换：下拉显示友好标签（供应商 JDK/JRE 版本），提交/解析为实际路径
        javaCombo.setConverter(new StringConverter<JavaOption>() {
            @Override
            public String toString(JavaOption opt) {
                return opt == null ? "" : opt.label;
            }

            @Override
            public JavaOption fromString(String text) {
                if (text == null) return systemDefaultOption();
                String trimmed = text.trim();
                if (trimmed.isEmpty()) return systemDefaultOption();
                for (JavaOption opt : javaCombo.getItems()) {
                    if (trimmed.equalsIgnoreCase(opt.path) || trimmed.equalsIgnoreCase(opt.label)) {
                        return opt;
                    }
                }
                return new JavaOption(trimmed, trimmed);
            }
        });

        // 选择/输入即保存（与当前配置不同才写入，避免无意义重复保存）
        javaCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            String path = newVal.path;
            if (!path.equals(host.config().getOrDefault("JavaPath", "java"))) {
                host.config().put("JavaPath", path);
                host.saveConfig();
                recordJavaPathHistory(path);
                host.ui().toast(I18N.get("jvm.t0003"));
            }
        });

        // 初始选中当前配置值
        selectJavaPath(javaCombo, host.config().getOrDefault("JavaPath", "java"));

        // 搜索：后台扫描注册表与常见目录，重建下拉列表
        Button searchJavaBtn = AppIcons.button("search", I18N.get("jvm.t0004"));
        searchJavaBtn.getStyleClass().add("btn-primary");
        searchJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        searchJavaBtn.setOnAction(e -> refreshJavaOptionsAsync(javaCombo, true));

        // 浏览：手动选择 java.exe
        Button browseJavaBtn = AppIcons.button("folder", I18N.get("jvm.t0005"));
        browseJavaBtn.getStyleClass().add("btn-primary");
        browseJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        browseJavaBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18N.get("jvm.t0006"));
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "*.exe", "*.cmd"));
            }
            File f = fc.showOpenDialog(host.stage());
            if (f != null) {
                selectJavaPath(javaCombo, f.getAbsolutePath());
                host.config().put("JavaPath", f.getAbsolutePath());
                host.saveConfig();
                recordJavaPathHistory(f.getAbsolutePath());
                host.ui().toast(I18N.get("jvm.t0007"));
            }
        });

        // 检测：运行 java -version 校验所选路径可用性与版本
        Button checkJavaBtn = AppIcons.button("search", I18N.get("jvm.t0008"));
        checkJavaBtn.getStyleClass().add("btn-primary");
        checkJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        checkJavaBtn.setOnAction(e -> {
            JavaOption sel = javaCombo.getValue();
            checkJavaAsync(sel != null ? sel.path : "java");
        });

        // 自动选择开关动作：开启时禁用手动配置，避免与自动结果混淆
        autoJavaToggle.setOnAction(e -> {
            host.config().put("AutoJava", String.valueOf(autoJavaToggle.isSelected()));
            host.saveConfig();
            setJavaManualControlsEnabled(javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn, !autoJavaToggle.isSelected());
            host.ui().toast(I18N.get("jvm.k0029", (autoJavaToggle.isSelected() ? I18N.get("jvm.t0009") : I18N.get("jvm.t0010"))));
        });
        // 开启自动选择时禁用手动配置（含初始状态）
        setJavaManualControlsEnabled(javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn, !autoJava);

        // 卡片内纵向排布：开关在上，路径行在下（重构 §6.3 布局第 1 条） //
        VBox javaBox = new VBox(10,
                PageKit.settingsCard(I18N.get("jvm.t0011"), I18N.get("jvm.t0012"), autoJavaToggle),
                new HBox(10, javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn));
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("jvm.t0013"),
                I18N.get("jvm.t0014"), javaBox));

        // 页面打开时后台加载上次扫描的 Java 列表（读缓存，毫秒级；点「搜索」才强制重扫）
        refreshJavaOptionsAsync(javaCombo, false);

        // 卡片「内存分配」（重构 §6.3 布局第 2 条 + §6.6 内存优化卡迁入） //
        root.getChildren().add(buildMemoryCard());

        // 卡片「JVM 参数」（输入框右下角三角把手，高度可自由调节并记住） //
        String curJvmArgs = host.config().getOrDefault("JvmArgs", "");
        TextArea jvmArea = new TextArea(curJvmArgs.isEmpty() ? "-XX:+UseG1GC" : curJvmArgs);
        jvmArea.getStyleClass().addAll("textarea-field", "textarea-mono");
        jvmArea.setWrapText(true);
        jvmArea.setPrefRowCount(3);
        jvmArea.setMaxWidth(Double.MAX_VALUE);
        jvmArea.setMinWidth(0);
        // 输入框高度：右下角三角把手拖动调节，拖动结束写回 JvmArgsEditorHeight（0 = 跟随默认 3 行）
        int savedJvmAreaH = PageKit.parseIntSafe(host.config().getOrDefault("JvmArgsEditorHeight", "0"), 0);
        StackPane jvmEditor = PageKit.resizableTextArea(jvmArea, savedJvmAreaH, h -> {
            String value = String.valueOf(Math.round(h));
            if (value.equals(host.config().getOrDefault("JvmArgsEditorHeight", "0"))) return;
            host.config().put("JvmArgsEditorHeight", value);
            // 单独落盘客户端配置：host.saveConfig() 会重建设置页，正在拖拽输入框时会被打断
            ConfigManager.saveClientConfig(host.config());
        });
        Button saveJvm = AppIcons.button("save", I18N.get("jvm.t0015"));
        saveJvm.getStyleClass().add("btn-primary");
        saveJvm.setOnAction(e -> {
            host.config().put("JvmArgs", jvmArea.getText());
            host.saveConfig();
            host.ui().toast(I18N.get("jvm.t0016"));
        });
        HBox jvmActions = new HBox(saveJvm);
        jvmActions.setAlignment(Pos.CENTER_RIGHT);
        VBox jvmBox = new VBox(8, jvmEditor, jvmActions);
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("jvm.t0017"),
                I18N.get("jvm.t0018"), jvmBox));

        return root;
    }

    /**
     * 内存分配卡片（重构 §6.3）：最大/最小内存滑杆拖动结束自动保存（500ms 防抖，去掉保存按钮）；
     * 「内存优化」卡从高级设置页迁入（§6.6），同为一条「内存分配」卡内容。
     */
    private Node buildMemoryCard() {
        VBox content = new VBox(12);

        // 最大内存（滑杆拖动结束防抖自动保存） //
        String curMaxMem = host.config().getOrDefault("MaxMemory", "4096");
        int maxMem = PageKit.parseIntSafe(curMaxMem, 4096);
        Label maxValLabel = new Label(I18N.get("jvm.k0030", maxMem + " MB"));
        maxValLabel.getStyleClass().add("black-value-label");
        maxValLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider maxSlider = new Slider(512, 32768, maxMem);
        maxSlider.getStyleClass().add("black-ticks");
        maxSlider.setShowTickLabels(true);
        maxSlider.setMajorTickUnit(8192);
        maxSlider.setBlockIncrement(1024);
        maxSlider.setPrefWidth(200);
        maxSlider.valueProperty().addListener((obs, old, val) -> maxValLabel.setText(I18N.get("jvm.k0031", val.intValue() + " MB")));
        javafx.animation.PauseTransition maxDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        maxDebounce.setOnFinished(e -> {
            int val = (int) maxSlider.getValue();
            if (String.valueOf(val).equals(host.config().getOrDefault("MaxMemory", "4096"))) return;
            host.config().put("MaxMemory", String.valueOf(val));
            host.saveConfig();
            host.ui().toast(I18N.get("jvm.k0032", val + " MB"));
        });
        maxSlider.valueProperty().addListener((obs, old, val) -> maxDebounce.playFromStart());
        HBox maxRow = new HBox(10, maxSlider, maxValLabel);
        maxRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(PageKit.settingsCard(I18N.get("jvm.t0019"), I18N.get("jvm.t0020"), maxRow));

        // 最小内存（滑杆拖动结束防抖自动保存） //
        String curMinMem = host.config().getOrDefault("MinMemory", "2048");
        int minMem = PageKit.parseIntSafe(curMinMem, 2048);
        Label minValLabel = new Label(I18N.get("jvm.k0033", minMem + " MB"));
        minValLabel.getStyleClass().add("black-value-label");
        minValLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider minSlider = new Slider(512, 16384, minMem);
        minSlider.getStyleClass().add("black-ticks");
        minSlider.setShowTickLabels(true);
        minSlider.setMajorTickUnit(4096);
        minSlider.setBlockIncrement(512);
        minSlider.setPrefWidth(200);
        minSlider.valueProperty().addListener((obs, old, val) -> minValLabel.setText(I18N.get("jvm.k0034", val.intValue() + " MB")));
        javafx.animation.PauseTransition minDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        minDebounce.setOnFinished(e -> {
            int val = (int) minSlider.getValue();
            if (String.valueOf(val).equals(host.config().getOrDefault("MinMemory", "2048"))) return;
            host.config().put("MinMemory", String.valueOf(val));
            host.saveConfig();
            host.ui().toast(I18N.get("jvm.k0035", val + " MB"));
        });
        minSlider.valueProperty().addListener((obs, old, val) -> minDebounce.playFromStart());
        HBox minRow = new HBox(10, minSlider, minValLabel);
        minRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(PageKit.settingsCard(I18N.get("jvm.t0021"), I18N.get("jvm.t0022"), minRow));

        // 内存优化卡（自高级设置页迁入，重构 §6.6；实现仍在 MemoryPage.buildOptimizeCard）
        content.getChildren().add(host.buildMemoryOptimizeCard());

        return PageKit.settingsCardStacked(I18N.get("jvm.t0023"),
                I18N.get("jvm.t0024"), content);
    }

    // Java 路径辅助方法 //

    /** Java 路径下拉选项：path 为实际 java.exe 路径（"java" 表示系统默认），label 为友好显示文本 */
    private static final class JavaOption {
        final String path;
        final String label;

        JavaOption(String path, String label) {
            this.path = path;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * 系统默认选项：使用 PATH 中的 java。
     *
     * <p>必须是方法而非 {@code static final} 字段：{@link JavaOption} 用
     * {@code toString()} 显示，字段在类加载时创建会把标签冻结成当时语言，
     * 切到英文后这一项仍显示中文。
     */
    private static JavaOption systemDefaultOption() {
        return new JavaOption("java", I18N.get("jvm.t0025"));
    }

    /**
     * 将指定路径设为下拉框当前选中值；若不在列表中则动态追加一项。
     * "java" 映射为「系统默认」项。
     */
    private void selectJavaPath(ComboBox<JavaOption> combo, String path) {
        if (path == null || path.trim().isEmpty() || "java".equalsIgnoreCase(path.trim())) {
            combo.setValue(systemDefaultOption());
            return;
        }
        for (JavaOption opt : combo.getItems()) {
            if (opt.path.equalsIgnoreCase(path) || opt.label.equalsIgnoreCase(path)) {
                combo.setValue(opt);
                return;
            }
        }
        JavaOption custom = new JavaOption(path, path);
        combo.getItems().add(custom);
        combo.setValue(custom);
    }

    /** 开启自动选择 Java 时禁用/恢复手动配置控件 */
    private void setJavaManualControlsEnabled(ComboBox<JavaOption> combo, Button search, Button browse, Button check, boolean enabled) {
        combo.setDisable(!enabled);
        search.setDisable(!enabled);
        browse.setDisable(!enabled);
        check.setDisable(!enabled);
    }

    /**
     * 加载本机 Java 列表到下拉框（注册表 / 常见目录 / JAVA_HOME / PATH）。
     * @param forceRescan true = 强制全盘重新扫描并更新缓存（搜索按钮）；
     *                    false = 优先读上次扫描缓存（页面打开时，毫秒级）
     */
    private void refreshJavaOptionsAsync(ComboBox<JavaOption> combo, boolean forceRescan) {
        String currentText = combo.getEditor().getText();
        String current = (currentText == null || currentText.trim().isEmpty())
                ? host.config().getOrDefault("JavaPath", "java")
                : currentText.trim();
        if (forceRescan) host.ui().toast(I18N.get("jvm.t0026"));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            List<FindAllJavaWindows.JavaEntry> entries = forceRescan
                    ? JavaCacheManager.refresh()
                    : JavaCacheManager.getAvailable();
            Platform.runLater(() -> {
                combo.getItems().clear();
                combo.getItems().add(systemDefaultOption());
                // 历史记录优先展示，便于恢复常用路径
                String history = host.config().getOrDefault("JavaPathHistory", "");
                if (!history.isEmpty()) {
                    for (String p : history.split(";")) {
                        p = p.trim();
                        if (!p.isEmpty() && !"java".equalsIgnoreCase(p)) {
                            combo.getItems().add(new JavaOption(p, p));
                        }
                    }
                }
                for (FindAllJavaWindows.JavaEntry entry : entries) {
                    String exe = entry.getJavaExePath();
                    String vendor = entry.vendor != null ? entry.vendor : I18N.get("jvm.t0027");
                    String kind = entry.isJDK ? "JDK" : "JRE";
                    combo.getItems().add(new JavaOption(exe, vendor + " " + kind + " " + entry.version + "  (" + exe + ")"));
                }
                selectJavaPath(combo, current);
                if (forceRescan) host.ui().toast(I18N.get("jvm.p0029", entries.size()));
            });
        });
    }

    /** 记录 Java 路径到历史（分号分隔，去重，跳过系统默认） */
    private void recordJavaPathHistory(String path) {
        if (path == null || path.trim().isEmpty() || "java".equalsIgnoreCase(path.trim())) return;
        String history = host.config().getOrDefault("JavaPathHistory", "");
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (!history.isEmpty()) {
            for (String p : history.split(";")) {
                p = p.trim();
                if (!p.isEmpty()) set.add(p);
            }
        }
        set.add(path.trim());
        host.config().put("JavaPathHistory", String.join(";", set));
        host.saveConfig();
    }

    /** 后台运行 java -version 检测指定路径可用性与版本 */
    private void checkJavaAsync(String path) {
        String display = (path == null || path.isEmpty() || "java".equalsIgnoreCase(path.trim())) ? "java" : path;
        host.ui().toast(I18N.get("jvm.k0036", display + " ..."));
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(display, "-version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String firstLine;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    firstLine = reader.readLine();
                }
                boolean ok = p.waitFor(5, TimeUnit.SECONDS);
                String msg = (ok && firstLine != null && !firstLine.isEmpty())
                        ? I18N.get("jvm.r0001", firstLine.trim())
                        : I18N.get("jvm.r0002", firstLine == null ? I18N.get("jvm.t0028") : firstLine.trim());
                Platform.runLater(() -> host.ui().toast(msg));
            } catch (IOException e) {
                Platform.runLater(() -> host.ui().toast(I18N.get("jvm.k0001", e.getMessage())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

}



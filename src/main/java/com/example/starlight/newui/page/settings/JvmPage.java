package com.example.starlight.newui.page.settings;

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
        VBox root = PageKit.settingsPage(host, "Java虚拟机与内存");

        // Java 路径：可编辑下拉框，自动搜索本机所有 Java 安装（参考 HMCL JavaManagementPage 交互）
        ComboBox<JavaOption> javaCombo = new ComboBox<>();
        javaCombo.setEditable(true);
        javaCombo.setPrefWidth(200);
        javaCombo.setMinWidth(180);
        javaCombo.getStyleClass().add("select-field");
        javaCombo.setPromptText("选择或输入 Java 路径");
        HBox.setHgrow(javaCombo, Priority.ALWAYS);

        // 双向转换：下拉显示友好标签（供应商 JDK/JRE 版本），提交/解析为实际路径
        javaCombo.setConverter(new StringConverter<JavaOption>() {
            @Override
            public String toString(JavaOption opt) {
                return opt == null ? "" : opt.label;
            }

            @Override
            public JavaOption fromString(String text) {
                if (text == null) return SYSTEM_DEFAULT_OPTION;
                String trimmed = text.trim();
                if (trimmed.isEmpty()) return SYSTEM_DEFAULT_OPTION;
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
                host.ui().toast("Java路径已更新");
            }
        });

        // 初始选中当前配置值
        selectJavaPath(javaCombo, host.config().getOrDefault("JavaPath", "java"));

        // 搜索：后台扫描注册表与常见目录，重建下拉列表
        Button searchJavaBtn = AppIcons.button("search", "搜索");
        searchJavaBtn.getStyleClass().add("btn-primary");
        searchJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        searchJavaBtn.setOnAction(e -> refreshJavaOptionsAsync(javaCombo, true));

        // 浏览：手动选择 java.exe
        Button browseJavaBtn = AppIcons.button("folder", "浏览");
        browseJavaBtn.getStyleClass().add("btn-primary");
        browseJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        browseJavaBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择 Java 可执行文件");
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "*.exe", "*.cmd"));
            }
            File f = fc.showOpenDialog(host.stage());
            if (f != null) {
                selectJavaPath(javaCombo, f.getAbsolutePath());
                host.config().put("JavaPath", f.getAbsolutePath());
                host.saveConfig();
                recordJavaPathHistory(f.getAbsolutePath());
                host.ui().toast("Java路径已更新");
            }
        });

        // 检测：运行 java -version 校验所选路径可用性与版本
        Button checkJavaBtn = AppIcons.button("search", "检测");
        checkJavaBtn.getStyleClass().add("btn-primary");
        checkJavaBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        checkJavaBtn.setOnAction(e -> {
            JavaOption sel = javaCombo.getValue();
            checkJavaAsync(sel != null ? sel.path : "java");
        });

        HBox javaRow = new HBox(10, javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn);
        root.getChildren().add(PageKit.settingsCard("Java 路径", "自动搜索本机 Java，支持多版本切换", javaRow));

        // 页面打开时后台加载上次扫描的 Java 列表（读缓存，毫秒级；点「搜索」才强制重扫）
        refreshJavaOptionsAsync(javaCombo, false);

        // 自动选择 Java（参考 HMCL：根据游戏版本自动匹配本机已安装的 Java）
        boolean autoJava = "true".equalsIgnoreCase(host.config().getOrDefault("AutoJava", "true"));
        CheckBox autoJavaToggle = PageKit.toggle(autoJava);
        autoJavaToggle.setOnAction(e -> {
            host.config().put("AutoJava", String.valueOf(autoJavaToggle.isSelected()));
            host.saveConfig();
            setJavaManualControlsEnabled(javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn, !autoJavaToggle.isSelected());
            host.ui().toast("自动选择 Java: " + (autoJavaToggle.isSelected() ? "开启" : "关闭"));
        });
        // 开启自动选择时禁用手动配置，避免与自动结果混淆
        setJavaManualControlsEnabled(javaCombo, searchJavaBtn, browseJavaBtn, checkJavaBtn, !autoJava);
        root.getChildren().add(PageKit.settingsCard("自动选择 Java", "根据游戏版本自动匹配，无需手动配置", autoJavaToggle));

        // 最大内存
        String curMaxMem = host.config().getOrDefault("MaxMemory", "4096");
        int maxMem = PageKit.parseIntSafe(curMaxMem, 4096);
        Label maxValLabel = new Label("当前: " + maxMem + " MB");
        maxValLabel.getStyleClass().add("black-value-label");
        // 锁固有宽度：滑杆占满剩余空间时不许把数值挤成「当前…」
        maxValLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider maxSlider = new Slider(512, 32768, maxMem);
        maxSlider.getStyleClass().add("black-ticks");
        maxSlider.setShowTickLabels(true);
        maxSlider.setMajorTickUnit(8192);
        maxSlider.setBlockIncrement(1024);
        maxSlider.setPrefWidth(200);
        maxSlider.valueProperty().addListener((obs, old, val) -> maxValLabel.setText("当前: " + val.intValue() + " MB"));
        Button saveMaxMem = AppIcons.button("save", "保存");
        saveMaxMem.getStyleClass().add("btn-primary");
        saveMaxMem.setOnAction(e -> {
            host.config().put("MaxMemory", String.valueOf((int) maxSlider.getValue()));
            host.saveConfig();
            host.ui().toast("最大内存已保存");
        });
        HBox maxRow = new HBox(10, new HBox(10, maxSlider, maxValLabel), saveMaxMem);
        root.getChildren().add(PageKit.settingsCard("最大内存", "游戏可使用的最大内存 (MB)", maxRow));

        // 最小内存
        String curMinMem = host.config().getOrDefault("MinMemory", "2048");
        int minMem = PageKit.parseIntSafe(curMinMem, 2048);
        Label minValLabel = new Label("当前: " + minMem + " MB");
        minValLabel.getStyleClass().add("black-value-label");
        Slider minSlider = new Slider(512, 16384, minMem);
        minSlider.getStyleClass().add("black-ticks");
        minSlider.setShowTickLabels(true);
        minSlider.setMajorTickUnit(4096);
        minSlider.setBlockIncrement(512);
        minSlider.setPrefWidth(200);
        minSlider.valueProperty().addListener((obs, old, val) -> minValLabel.setText("当前: " + val.intValue() + " MB"));
        Button saveMinMem = AppIcons.button("save", "保存");
        saveMinMem.getStyleClass().add("btn-primary");
        saveMinMem.setOnAction(e -> {
            host.config().put("MinMemory", String.valueOf((int) minSlider.getValue()));
            host.saveConfig();
            host.ui().toast("最小内存已保存");
        });
        HBox minRow = new HBox(10, new HBox(10, minSlider, minValLabel), saveMinMem);
        root.getChildren().add(PageKit.settingsCard("最小内存", "游戏启动时的最小内存分配 (MB)", minRow));

        // JVM 参数：上标题、下多行输入框（通栏），参数通常较长，整行展示便于核对与编辑
        String curJvmArgs = host.config().getOrDefault("JvmArgs", "");
        TextArea jvmArea = new TextArea(curJvmArgs.isEmpty() ? "-XX:+UseG1GC" : curJvmArgs);
        jvmArea.getStyleClass().addAll("textarea-field", "textarea-mono");
        jvmArea.setWrapText(true);
        jvmArea.setPrefRowCount(3);
        jvmArea.setMaxWidth(Double.MAX_VALUE);
        jvmArea.setMinWidth(0);
        Button saveJvm = AppIcons.button("save", "保存");
        saveJvm.getStyleClass().add("btn-primary");
        saveJvm.setOnAction(e -> {
            host.config().put("JvmArgs", jvmArea.getText());
            host.saveConfig();
            host.ui().toast("JVM参数已保存");
        });
        HBox jvmActions = new HBox(saveJvm);
        jvmActions.setAlignment(Pos.CENTER_RIGHT);
        VBox jvmBox = new VBox(8, jvmArea, jvmActions);
        root.getChildren().add(PageKit.settingsCardStacked("JVM 参数", "自定义 JVM 启动参数（多个参数用空格分隔）", jvmBox));

        return root;
    }

    // ==================== Java 路径辅助方法 ====================

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

    /** 系统默认选项：使用 PATH 中的 java */
    private static final JavaOption SYSTEM_DEFAULT_OPTION = new JavaOption("java", "系统默认 (java)");

    /**
     * 将指定路径设为下拉框当前选中值；若不在列表中则动态追加一项。
     * "java" 映射为「系统默认」项。
     */
    private void selectJavaPath(ComboBox<JavaOption> combo, String path) {
        if (path == null || path.trim().isEmpty() || "java".equalsIgnoreCase(path.trim())) {
            combo.setValue(SYSTEM_DEFAULT_OPTION);
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
        if (forceRescan) host.ui().toast("正在搜索本机 Java...");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            List<FindAllJavaWindows.JavaEntry> entries = forceRescan
                    ? JavaCacheManager.refresh()
                    : JavaCacheManager.getAvailable();
            Platform.runLater(() -> {
                combo.getItems().clear();
                combo.getItems().add(SYSTEM_DEFAULT_OPTION);
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
                    String vendor = entry.vendor != null ? entry.vendor : "未知供应商";
                    String kind = entry.isJDK ? "JDK" : "JRE";
                    combo.getItems().add(new JavaOption(exe, vendor + " " + kind + " " + entry.version + "  (" + exe + ")"));
                }
                selectJavaPath(combo, current);
                if (forceRescan) host.ui().toast("找到 " + entries.size() + " 个 Java 安装");
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
        host.ui().toast("正在检测 " + display + " ...");
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
                        ? "Java 可用: " + firstLine.trim()
                        : "Java 检测失败: " + (firstLine == null ? "无法执行" : firstLine.trim());
                Platform.runLater(() -> host.ui().toast(msg));
            } catch (IOException e) {
                Platform.runLater(() -> host.ui().toast("Java 检测失败: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

}



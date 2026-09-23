package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.DebugLog;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * 设置 → 开发者选项页（启动画面开关 / 调试模式 / 中文日志 / 实时调试日志区）。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 调试日志控件与监听器随本页迁入；设置页重建时由主壳调用 {@link #detachLogListener()} 摘除旧监听。
 */
public final class DevPage {

    private final LauncherContext host;
    // 开发者选项：调试日志显示区与实时监听器（页面重建时先移除旧监听，防止重复注册）
    private TextArea devLogArea;
    private Consumer<String> devLogListener;

    public DevPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(host, "开发者选项");

        // ===== 启动画面开关（原有功能） =====
        CheckBox splashToggle = PageKit.toggle("true".equals(host.config().getOrDefault("ShowSplash", "true")));
        splashToggle.setOnAction(e -> {
            host.config().put("ShowSplash", String.valueOf(splashToggle.isSelected()));
            host.saveConfig();
        });
        root.getChildren().add(PageKit.settingsCard("启动画面", "显示启动画面", splashToggle));

        // ===== 调试模式开关 =====
        boolean debugMode = "true".equalsIgnoreCase(host.config().getOrDefault("DebugMode", "false"));
        AppConfig.DEBUG_MODE = debugMode; // 与全局开关同步（loadConfig 已恢复，这里兜底）
        CheckBox debugToggle = PageKit.toggle(debugMode);
        debugToggle.setOnAction(e -> {
            AppConfig.DEBUG_MODE = debugToggle.isSelected();
            host.config().put("DebugMode", String.valueOf(debugToggle.isSelected()));
            host.saveConfig();
            DebugLog.log("调试模式已" + (debugToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("调试模式",
                "记录底层页面刷新与网络数据包收发详情（大小、URL、请求/响应内容），实时显示在下方日志区", debugToggle));

        // ===== 中文日志开关 =====
        boolean chineseLog = !"false".equalsIgnoreCase(host.config().getOrDefault("ChineseLog", "true"));
        AppConfig.CHINESE_LOG = chineseLog; // 与全局开关同步
        CheckBox zhToggle = PageKit.toggle(chineseLog);
        zhToggle.setOnAction(e -> {
            AppConfig.CHINESE_LOG = zhToggle.isSelected();
            host.config().put("ChineseLog", String.valueOf(zhToggle.isSelected()));
            host.saveConfig();
            DebugLog.log("中文日志已" + (zhToggle.isSelected() ? "开启" : "关闭"));
        });
        root.getChildren().add(PageKit.settingsCard("中文日志",
                "调试日志以中文输出（如「发送 / 接收」）；关闭后以英文输出（SEND / RECV）", zhToggle));

        // ===== 调试日志显示区 =====
        devLogArea = new TextArea();
        devLogArea.setEditable(false);
        devLogArea.setWrapText(false);
        devLogArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        devLogArea.setPrefHeight(360);
        devLogArea.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(devLogArea, Priority.ALWAYS);
        // 页面重建时回显历史日志
        StringBuilder history = new StringBuilder();
        for (String line : DebugLog.snapshot()) {
            history.append(line).append('\n');
        }
        devLogArea.setText(history.toString());

        // 注册实时日志监听（回调线程不保证为 FX 线程，统一 runLater 追加）
        devLogListener = line -> Platform.runLater(() -> {
            if (devLogArea != null) {
                devLogArea.appendText(line + "\n");
                devLogArea.setScrollTop(Double.MAX_VALUE); // 自动滚到底部
            }
        });
        DebugLog.addListener(devLogListener);

        HBox logBtns = new HBox(10);
        Button clearBtn = AppIcons.button("trash", "清空日志");
        clearBtn.getStyleClass().add("btn-primary");
        clearBtn.setOnAction(e -> devLogArea.clear());
        Button copyBtn = AppIcons.button("copy", "复制全部");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(devLogArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            host.ui().toast("已复制全部日志到剪贴板");
        });
        logBtns.getChildren().addAll(clearBtn, copyBtn);
        root.getChildren().add(logBtns);
        root.getChildren().add(devLogArea);

        Label warn = AppIcons.label("warning", "开发者选项可能会影响启动器稳定性，请谨慎操作");
        warn.setStyle("-fx-text-fill: #eab308; -fx-font-size: 12px; -fx-padding: 10 0;");
        root.getChildren().add(warn);
        return root;
    }

    /** 移除旧的调试日志监听器（设置页重建前调用，避免重复输出） */
    public void detachLogListener() {
        if (devLogListener != null) {
            DebugLog.removeListener(devLogListener);
            devLogListener = null;
        }
    }
}

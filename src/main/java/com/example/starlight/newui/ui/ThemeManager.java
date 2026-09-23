package com.example.starlight.newui.ui;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.update.UpdateInstaller;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 主题与背景管理：主题切换（含深色补充样式表与过渡遮罩）、背景图片应用与可读性遮罩、高对比度。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * {@code currentTheme} 与背景视图随本类迁入，主壳保留同名上下文方法作为委派入口。
 */
public final class ThemeManager {

    private final LauncherContext host;

    private String currentTheme = "light";
    // 背景图片视图（主题页设置，持久化到配置 BackgroundImage）
    private ImageView backgroundImageView;
    /** 背景可读性遮罩：置于壁纸与内容之间（浅色主题提亮、深色主题压暗），保证前景文字对比度 */
    private Region backgroundOverlay;

    public ThemeManager(LauncherContext host) {
        this.host = host;
    }

    /** 选择背景图片文件并立即应用、持久化 */

    public void chooseBackgroundImage(Label infoLabel) {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择启动器背景图片");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("图片文件", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        File f = fc.showOpenDialog(host.stage());
        if (f == null) return;
        // 复制到启动器目录 Starlight-Launcher/wallpaper/ 并重命名为随机 10 位字符串（保留原扩展名）
        File wallpaperDir = new File("Starlight-Launcher", "wallpaper");
        if (!wallpaperDir.isDirectory() && !wallpaperDir.mkdirs()) {
            host.ui().toast("无法创建 wallpaper 目录");
            return;
        }
        String ext = "";
        int dot = f.getName().lastIndexOf('.');
        if (dot > 0) ext = f.getName().substring(dot).toLowerCase();
        File target = new File(wallpaperDir, UpdateInstaller.randomAlphanumeric(10) + ext);
        try {
            Files.copy(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            host.ui().toast("背景图片复制失败: " + e.getMessage());
            return;
        }
        // 清理 wallpaper 目录中的旧背景文件（只保留最新一张，避免长期积累）
        String oldPath = host.config().getOrDefault("BackgroundImage", "");
        if (!oldPath.isEmpty()) {
            File old = new File(oldPath);
            if (old.isFile() && old.getParentFile() != null
                    && wallpaperDir.getAbsolutePath().equalsIgnoreCase(old.getParentFile().getAbsolutePath())
                    && !old.getAbsolutePath().equalsIgnoreCase(target.getAbsolutePath())) {
                old.delete();
            }
        }
        host.config().put("BackgroundImage", target.getAbsolutePath());
        host.saveConfig();
        applyBackgroundImage();
        if (infoLabel != null) infoLabel.setText(target.getName());
        host.ui().toast("背景图片已更新");
    }

    /** 应用背景图片（读取配置 BackgroundImage，置于 host.rootPane() 最底层） */

    public void applyBackgroundImage() {
        if (backgroundOverlay != null) {
            host.rootPane().getChildren().remove(backgroundOverlay);
            backgroundOverlay = null;
        }
        if (backgroundImageView != null) {
            host.rootPane().getChildren().remove(backgroundImageView);
            backgroundImageView = null;
        }
        String path = host.config().getOrDefault("BackgroundImage", "");
        if (path.isEmpty()) return;
        File f = new File(path);
        if (!f.isFile()) return;
        try {
            Image img = new Image(f.toURI().toString());
            if (img.isError()) return;
            backgroundImageView = new ImageView(img);
            backgroundImageView.setPreserveRatio(false);
            backgroundImageView.setSmooth(true);
            backgroundImageView.fitWidthProperty().bind(host.rootPane().widthProperty());
            backgroundImageView.fitHeightProperty().bind(host.rootPane().heightProperty());
            backgroundImageView.setOpacity(0.9);
            host.rootPane().getChildren().add(0, backgroundImageView);
            backgroundImageView.toBack();

            // 可读性遮罩：浅色主题用半透明白提亮深色壁纸，深色主题用半透明黑压暗亮色壁纸
            backgroundOverlay = new Region();
            backgroundOverlay.setMouseTransparent(true);
            backgroundOverlay.prefWidthProperty().bind(host.rootPane().widthProperty());
            backgroundOverlay.prefHeightProperty().bind(host.rootPane().heightProperty());
            host.rootPane().getChildren().add(1, backgroundOverlay);
            updateBackgroundOverlay();
        } catch (Exception e) {
            // 图片加载失败时静默忽略，保持默认背景
        }
    }

    /** 按当前主题更新背景遮罩颜色（壁纸可读性），无壁纸时为空操作 */
    private void updateBackgroundOverlay() {
        if (backgroundOverlay == null) return;
        boolean dark = "dark".equals(currentTheme);
        backgroundOverlay.setStyle(dark
                ? "-fx-background-color: rgba(0,0,0,0.35);"
                : "-fx-background-color: rgba(255,255,255,0.45);");
    }

    /** 应用侧边栏显隐配置（ShowSidebar），仅在设置页生效 */

    /** 应用高对比度样式（HighContrast），切换 host.rootPane() 样式类 */

    public void applyHighContrast() {
        boolean on = "true".equalsIgnoreCase(host.config().getOrDefault("HighContrast", "false"));
        host.rootPane().getStyleClass().remove("high-contrast");
        if (on) host.rootPane().getStyleClass().add("high-contrast");
    }

    // ===== 主题 =====

    /**
     * 按当前主题把 {@code dark-extra.css} 挂上 / 摘下主 Scene。
     *
     * <p>ComboBox 的候选列表是独立弹窗，弹窗场景里没有 {@code .root} 节点，
     * 因此 {@code style.css} 里 {@code .root.dark xxx} 那套选择器命中不到它。
     * 弹窗会继承主 Scene 的样式表，所以把「不带主题前缀」的深色规则单独放一个文件，
     * 只在深色主题下挂上去。
     */
    public void syncDarkExtraStylesheet() {
        if (host.rootPane().getScene() == null) return;
        java.net.URL res = ThemeManager.class.getResource("/fxml/css/dark-extra.css");
        if (res == null) return;
        String url = res.toExternalForm();
        var sheets = host.rootPane().getScene().getStylesheets();
        boolean dark = "dark".equals(currentTheme);
        if (dark) {
            if (!sheets.contains(url)) sheets.add(url);
        } else {
            sheets.remove(url);
        }
    }


    public void applyTheme(String theme) {        if (theme == null || theme.equals(currentTheme)) return;

        String currentGradient = "dark".equals(currentTheme)
                ? "linear-gradient(to bottom right, #0d2137 0%, #1a3a5c 50%, #0f2847 100%)"
                : "linear-gradient(to bottom right, #bfdbfe 0%, #dbeafe 50%, #e0f2fe 100%)";

        final String targetTheme = theme;

        Region overlay = new Region();
        overlay.setStyle("-fx-background-color: " + currentGradient + ";");
        overlay.setMouseTransparent(true);
        if (host.rootPane().getScene() != null) {
            overlay.prefWidthProperty().bind(host.rootPane().widthProperty());
            overlay.prefHeightProperty().bind(host.rootPane().heightProperty());
        }

        host.rootPane().getChildren().add(overlay);
        overlay.toFront();

        host.rootPane().getStyleClass().removeAll("light", "dark");
        host.rootPane().getStyleClass().add(targetTheme);
        currentTheme = targetTheme;
        // 深色主题下补挂弹窗类控件的样式（ComboBox 候选列表等，见 dark-extra.css 说明）
        syncDarkExtraStylesheet();
        // 背景遮罩颜色跟随主题（保证壁纸上文字可读）
        updateBackgroundOverlay();

        host.config().put("Theme", theme);
        host.saveConfig();

FadeTransition ft = new FadeTransition(Duration.millis(500), overlay);
        ft.setFromValue(1.0);
        ft.setToValue(0.0);
        ft.setInterpolator(Interpolator.EASE_BOTH);
        ft.setOnFinished(e -> host.rootPane().getChildren().remove(overlay));
        ft.play();
    }

    /** 当前主题 key（light / dark / system） */
    public String currentTheme() {
        return currentTheme;
    }

    /** 直接设置主题 key（构造期初始化用；不会触发样式切换） */
    public void setCurrentTheme(String theme) {
        this.currentTheme = theme == null ? "" : theme;
    }

}


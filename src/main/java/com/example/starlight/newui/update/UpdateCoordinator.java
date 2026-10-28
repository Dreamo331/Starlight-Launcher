package com.example.starlight.newui.update;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.update.UpdateInstaller;
import com.example.starlight.util.UpdateChecker;

import com.example.starlight.download.task.UpdateDownloader;
import com.example.starlight.gui.UIGeneralControlClass;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 更新流程协调器：检查更新 / 自动检查 / 更新与跳过对话框 / 下载并自替换。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，逻辑、样式类与内联样式均未改动）：
 * 主壳只保留 {@code checkForUpdates()} 与 {@code autoCheckForUpdates()} 两个委派入口。
 */
public final class UpdateCoordinator {

    private final LauncherContext host;

    public UpdateCoordinator(LauncherContext host) {
        this.host = host;
    }

    /**
     * 检查更新：委托给 UpdateChecker（官方更新 API 优先，失败后 GitHub/Gitee 竞速 + 版本比对），
     * 本类只负责展示结果
     */
    public void check() {
        host.ui().toast(I18N.get("update.t0001"));
        UpdateChecker.checkUpdate(AppConfig.APP_VERSION, result -> {
            if (result.error != null || result.latestTag == null || result.latestTag.isEmpty()) {
                showErrorDialog(I18N.get("update.t0020"),
                        I18N.get("update.p0037", (result.error != null ? result.error : I18N.get("update.t0021"))));
                return;
            }
            int cmp = UpdateChecker.compareVersions(result.latestVersion, AppConfig.APP_VERSION);
            if (cmp > 0) {
                showUpdateDialog(result.latestVersion, result.releaseBody,
                        result.downloadUrl, result.md5, result.forceUpdate, result.releasePageUrl);
            } else if (cmp == 0) {
                showInfoDialog(I18N.get("update.t0022"),
                        I18N.get("update.p0038", AppConfig.DISPLAY_VERSION + "）" + sourceHint(result)));
            } else {
                showInfoDialog(I18N.get("update.t0023"),
                        I18N.get("upd.r0001", AppConfig.DISPLAY_VERSION, result.latestTag)
                        + sourceHint(result));
            }
        });
    }

    /**
     * 来源提示：降级来源（GitHub / Gitee）时告知官方服务不可用；
     * 官方正常但下载源被替换为镜像时，明确告知「官方只检查、镜像下载」，
     * 便于用户（和排查问题的我们）知道实际下载地址来自哪里。
     */
    private static String sourceHint(UpdateChecker.UpdateResult result) {
        if (result.source == null) return "";
        if (result.source != UpdateChecker.Source.OFFICIAL) {
            return I18N.get("update.p0039", result.source.label() + "）");
        }
        String dl = result.downloadSourceLabel;
        if (dl != null && !dl.isEmpty()) {
            return I18N.get("update.p0040", dl + "）");
        }
        return "";
    }

    /**
     * 自动检查更新：启动进入首页后延迟 2 秒触发（避开启动动画与窗口渲染），静默请求；
     * 发现新版本且未被「不再提醒」跳过时，在首页弹出更新提示（稍后更新 / 立即更新 + 勾选框）。
     */
    public void autoCheck() {
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(2), e ->
                UpdateChecker.checkUpdate(AppConfig.APP_VERSION, this::handleAutoUpdateResult)));
        timer.setCycleCount(1);
        timer.play();
    }

    /** 自动检查结果处理：失败/无新版本静默；有新版且非跳过版本时弹出提示（手动检查不受跳过影响） */
    private void handleAutoUpdateResult(UpdateChecker.UpdateResult result) {
        if (result.error != null || result.latestVersion == null || result.latestVersion.isEmpty()) return;
        if (UpdateChecker.compareVersions(result.latestVersion, AppConfig.APP_VERSION) <= 0) return;
        // 勾选过「下次自动更新不再提醒」：跳过该版本的自动提示（新版本发布后恢复提醒）
        String skip = host.config().getOrDefault("SkipUpdateVersion", "");
        if (result.latestVersion.equals(skip)) return;
        showAutoUpdateDialog(result.latestVersion, result.releaseBody, result.downloadUrl,
                result.md5, result.forceUpdate, result.releasePageUrl);
    }

    /**
     * 首页更新提示（集成式弹窗）：「稍后更新 / 立即更新」+ 勾选框「下次自动更新不再提醒」。
     * 勾选后记录 SkipUpdateVersion，该版本后续启动不再自动提醒；强制更新时不提供跳过选项。
     *
     * <p>官方服务器不可用时结果来自 GitHub / Gitee：没有安装包直链（发行版未附 .exe）时，
     * 按钮退化为「打开发布页」，用浏览器打开发行版页面手动下载。
     */
    private void showAutoUpdateDialog(String latest, String body, String downloadUrl, String md5,
                                      boolean forceUpdate, String releasePageUrl) {
        VBox bodyBox = new VBox(12);
        bodyBox.setPadding(new Insets(8, 4, 0, 4));

        // 版本信息 + 更新说明（textarea-field 随主题切换深浅色）
        Label verLabel = new Label(I18N.get("upd.r0006", AppConfig.DISPLAY_VERSION, latest));
        verLabel.getStyleClass().add("modal-text-title");
        TextArea area = new TextArea(body == null || body.trim().isEmpty()
                ? I18N.get("update.t0002") : body.trim());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefHeight(130);
        area.setMaxHeight(180);
        area.getStyleClass().add("textarea-field");
        VBox.setVgrow(area, Priority.ALWAYS);

        // 勾选框：下次自动更新不再提醒（强制更新不提供跳过）
        CheckBox dontRemind = new CheckBox(I18N.get("update.t0003"));
        dontRemind.getStyleClass().add("modal-checkbox");

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        if (!forceUpdate) {
            Button laterBtn = AppIcons.button("clock", I18N.get("update.t0004"));
            laterBtn.getStyleClass().add("modal-btn-cancel");
            laterBtn.setOnAction(e -> {
                host.ui().closeModal();
                if (dontRemind.isSelected()) rememberSkipVersion(latest);
            });
            btns.getChildren().add(laterBtn);
        }
        boolean hasDirect = downloadUrl != null && !downloadUrl.trim().isEmpty();
        boolean hasPage = releasePageUrl != null && !releasePageUrl.trim().isEmpty();
        Button updateBtn = null;
        // 有直链 → 启动器内下载并自动替换；只有发行版页面 → 浏览器打开手动下载；两者都无 → 不提供按钮
        if (hasDirect || hasPage) {
            updateBtn = AppIcons.button(hasDirect ? "download" : "external-link",
                    hasDirect ? I18N.get("update.t0024") : I18N.get("update.t0025"));
            updateBtn.getStyleClass().add("modal-btn-ok");
            updateBtn.setOnAction(e -> {
                host.ui().closeModal();
                if (dontRemind.isSelected()) rememberSkipVersion(latest);
                if (hasDirect) {
                    startAutoUpdate(latest, downloadUrl.trim(), md5);
                } else {
                    openReleasePage(releasePageUrl, latest);
                }
            });
        }
        if (updateBtn != null) {
            btns.getChildren().add(updateBtn);
        }

        if (forceUpdate) {
            bodyBox.getChildren().addAll(verLabel, area, btns);
        } else {
            bodyBox.getChildren().addAll(verLabel, area, dontRemind, btns);
        }
        host.ui().modal(I18N.get("update.p0041", latest + (forceUpdate ? I18N.get("update.t0026") : "")), bodyBox, 460, forceUpdate ? 290 : 330);
    }

    /** 记录「不再提醒」的版本号：该版本在自动检查时不再弹出提示（新版本发布后自动恢复提醒） */
    private void rememberSkipVersion(String latest) {
        host.config().put("SkipUpdateVersion", latest);
        host.saveConfig();
        host.ui().toast(I18N.get("upd.r0002", latest));
    }

    /** 发现新版本面板（集成在启动器内）：展示更新内容，点击「立即更新」下载新版本到启动器目录并自动替换 */
    private void showUpdateDialog(String latest, String body, String downloadUrl, String md5,
                                  boolean forceUpdate, String releasePageUrl) {
        VBox bodyBox = new VBox(10);
        bodyBox.setPadding(new Insets(8, 4, 0, 4));

        StringBuilder text = new StringBuilder();
        text.append(I18N.get("update.t0027")).append(AppConfig.DISPLAY_VERSION)
                .append(I18N.get("update.t0028")).append(latest);
        boolean hasDirect = downloadUrl != null && !downloadUrl.trim().isEmpty();
        boolean hasPage = releasePageUrl != null && !releasePageUrl.trim().isEmpty();
        if (hasDirect) {
            text.append(I18N.get("update.t0005")).append(downloadUrl.trim());
        } else if (hasPage) {
            text.append(I18N.get("update.t0006")).append(releasePageUrl.trim());
        }
        if (body != null && !body.trim().isEmpty()) {
            text.append(I18N.get("update.t0007"))
                    .append(body.length() > 800 ? body.substring(0, 800) + "..." : body);
        }
        TextArea area = new TextArea(text.toString());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(430, 280);
        VBox.setVgrow(area, Priority.ALWAYS);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button closeBtn = AppIcons.button("close", I18N.get("update.t0008"));
        closeBtn.getStyleClass().add("modal-btn-cancel");
        closeBtn.setOnAction(e -> host.ui().closeModal());
        btns.getChildren().add(closeBtn);
        if (hasDirect) {
            Button updateBtn = AppIcons.button("download", I18N.get("update.t0009"));
            updateBtn.getStyleClass().add("modal-btn-ok");
            updateBtn.setOnAction(e -> {
                host.ui().closeModal();
                startAutoUpdate(latest, downloadUrl.trim(), md5);
            });
            btns.getChildren().add(updateBtn);
        } else if (hasPage) {
            Button pageBtn = AppIcons.button("external-link", I18N.get("update.t0010"));
            pageBtn.getStyleClass().add("modal-btn-ok");
            pageBtn.setOnAction(e -> {
                host.ui().closeModal();
                openReleasePage(releasePageUrl, latest);
            });
            btns.getChildren().add(pageBtn);
        }
        bodyBox.getChildren().addAll(area, btns);
        host.ui().modal(I18N.get("update.p0042", latest + (forceUpdate ? I18N.get("update.t0029") : "")), bodyBox, 460, 380);
    }

    /**
     * 降级来源（GitHub / Gitee）没有安装包直链时，用浏览器打开发行版页面让用户手动下载。
     * 官方服务器正常情况下不会走到这里——官方接口恒给 download_url。
     */
    private void openReleasePage(String releasePageUrl, String latest) {
        if (releasePageUrl == null || releasePageUrl.trim().isEmpty()) {
            showErrorDialog(I18N.get("update.t0030"), I18N.get("update.t0031"));
            return;
        }
        try {
            Desktop.getDesktop().browse(java.net.URI.create(releasePageUrl.trim()));
            host.ui().toast(I18N.get("upd.r0003", latest));
        } catch (Exception ex) {
            showErrorDialog(I18N.get("update.t0032"),
                    I18N.get("update.p0043", ex.getMessage() + "\n" + releasePageUrl.trim()));
        }
    }

    /**
     * 自动更新流程：将新版本 exe 下载到启动器所在目录（Starlight-Launcher 文件夹），
     * 下载过程中显示进度条；完成后生成自替换脚本，等待启动器退出后覆盖 exe 并启动新版本。
     * 仅正式打包的 Starlight Launcher.exe 支持原地替换，开发环境回退为浏览器下载。
     */
    private void startAutoUpdate(String latest, String downloadUrl, String md5) {
        Path exePath = UpdateInstaller.getCurrentExePath();
        if (exePath == null) {
            showErrorDialog(I18N.get("update.t0033"), I18N.get("update.p0044", downloadUrl));
            return;
        }
        String exeName = exePath.getFileName().toString();
        if (!"Starlight Launcher.exe".equalsIgnoreCase(exeName)) {
            // 开发/调试环境（如 IDE 中的 java.exe）：不支持原地替换，回退浏览器下载
            try {
                Desktop.getDesktop().browse(java.net.URI.create(downloadUrl));
                host.ui().toast(I18N.get("update.t0011"));
            } catch (Exception ex) {
                host.ui().toast(I18N.get("update.p0045", ex.getMessage()));
            }
            return;
        }
        final Path dir = exePath.getParent();
        final Path newExe = dir.resolve(exeName + ".new");
        final Path bat = dir.resolve("sl-update.bat");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 下载进度面板（集成在启动器内）
        VBox progressBody = new VBox(10);
        progressBody.setPadding(new Insets(10, 4, 0, 4));
        Label dlgTitle = new Label(I18N.get("update.p0046", latest + " ..."));
        dlgTitle.getStyleClass().add("modal-text-title");
        Label statusLabel = new Label(I18N.get("update.t0012"));
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("modal-text");
        ProgressBar bar = new ProgressBar(0);
        bar.setPrefWidth(400);
        bar.setMaxWidth(Double.MAX_VALUE);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Button cancelBtn = AppIcons.button("close", I18N.get("update.t0013"));
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            host.ui().closeModal();
        });
        HBox btnRow = new HBox(10);
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.getChildren().add(cancelBtn);
        progressBody.getChildren().addAll(dlgTitle, statusLabel, bar, spacer, btnRow);
        host.ui().modal(I18N.get("update.t0014"), progressBody, 460, 230);

        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                Files.deleteIfExists(newExe);
                UpdateDownloader.downloadExe(downloadUrl, newExe, pct -> Platform.runLater(() -> {
                    if (!cancelled.get()) {
                        bar.setProgress(pct / 100.0);
                        statusLabel.setText(I18N.get("update.p0047", pct + "%"));
                    }
                }), cancelled);
                if (cancelled.get()) {
                    Files.deleteIfExists(newExe);
                    return;
                }
                // MD5 完整性校验（接口提供了 md5 时执行）
                if (md5 != null && !md5.trim().isEmpty()) {
                    Platform.runLater(() -> statusLabel.setText(I18N.get("update.t0015")));
                    String actual = UpdateInstaller.md5Of(newExe);
                    if (!actual.equalsIgnoreCase(md5.trim())) {
                        Files.deleteIfExists(newExe);
                        Platform.runLater(() -> {
                            host.ui().closeModal();
                            showErrorDialog(I18N.get("update.t0034"), I18N.get("update.t0035"));
                        });
                        return;
                    }
                }
                // 生成自替换脚本并启动：启动器退出后脚本自动覆盖 exe 并启动新版本
                Files.writeString(bat, UpdateInstaller.buildUpdateBatContent(exeName), StandardCharsets.US_ASCII);
                new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", bat.toString())
                        .redirectErrorStream(true)
                        .start();
                Platform.runLater(() -> {
                    host.ui().closeModal();
                    if (cancelled.get()) return;
                    // 更新就绪面板：立即重启 / 稍后
                    VBox readyBody = new VBox(14);
                    readyBody.setPadding(new Insets(10, 4, 0, 4));
                    Label readyMsg = new Label(I18N.get("upd.r0004", latest));
                    readyMsg.setWrapText(true);
                    readyMsg.getStyleClass().add("modal-text");
                    Region rSpacer = new Region();
                    VBox.setVgrow(rSpacer, Priority.ALWAYS);
                    HBox rBtns = new HBox(10);
                    rBtns.setAlignment(Pos.CENTER_RIGHT);
                    Button laterBtn = AppIcons.button("clock", I18N.get("update.t0016"));
                    laterBtn.getStyleClass().add("modal-btn-cancel");
                    laterBtn.setOnAction(e -> {
                        host.ui().closeModal();
                        host.ui().toast(I18N.get("update.t0017"));
                    });
                    Button restartBtn = AppIcons.button("refresh", I18N.get("update.t0018"));
                    restartBtn.getStyleClass().add("modal-btn-ok");
                    restartBtn.setOnAction(e -> {
                        host.ui().closeModal();
                        Platform.exit();
                    });
                    rBtns.getChildren().addAll(laterBtn, restartBtn);
                    readyBody.getChildren().addAll(readyMsg, rSpacer, rBtns);
                    host.ui().modal(I18N.get("update.t0019"), readyBody, 460, 230);
                });
            } catch (Exception ex) {
                try { Files.deleteIfExists(newExe); } catch (Exception ignored) {}
                try { Files.deleteIfExists(bat); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    host.ui().closeModal();
                    showErrorDialog(I18N.get("update.t0036"), I18N.get("upd.r0005", ex.getMessage()));
                });
            }
        });
    }

    /** 信息提示（委托通用面板，保留方法名避免改动调用点） */
    private void showInfoDialog(String title, String message) {
        host.ui().info(title, message);
    }

    /** 错误提示（委托通用面板，保留方法名避免改动调用点） */
    private void showErrorDialog(String title, String message) {
        host.ui().error(title, message);
    }

}



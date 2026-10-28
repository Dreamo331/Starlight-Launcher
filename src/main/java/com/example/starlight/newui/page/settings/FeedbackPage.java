package com.example.starlight.newui.page.settings;


import com.example.starlight.util.I18N;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.util.FeedbackEmailSender;
import com.example.starlight.util.FormatUtils;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import java.io.File;

/** 
设置 → 服务与反馈页
（从 LauncherView 抽离，逻辑与样式均未改动） */
public final class FeedbackPage {

    private final LauncherContext ctx;

    public FeedbackPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, I18N.get("feedback.t0001"));

        // 卡片「联系方式」：用户邮箱（用于回复），从配置加载上次填写的邮箱（重构 §6.13） //
        TextField emailField = new TextField(ctx.config().getOrDefault("FeedbackEmail", ""));
        emailField.setPromptText("your@email.com");
        emailField.getStyleClass().add("input-field");
        root.getChildren().add(PageKit.settingsCard(I18N.get("feedback.t0002"), I18N.get("feedback.t0003"), emailField));

        // 卡片「反馈内容」：类型下拉 + 多行文本框 //
        // 反馈类型下拉（新增配置键 settings.feedback.type，默认 bug；随正文以 [类型: …] 前缀提交，见文档 §6.13 行为变更说明）
        String[] feedbackTypes = {"bug", "suggestion", "question", "other"};
        String[] feedbackTypeNames = {I18N.get("feedback.t0004"), I18N.get("feedback.t0005"), I18N.get("feedback.t0006"), I18N.get("feedback.t0007")};
        ComboBox<String> typeCombo = new ComboBox<>();
        for (String name : feedbackTypeNames) typeCombo.getItems().add(name);
        String savedType = ctx.config().getOrDefault("settings.feedback.type", "bug");
        int typeIdx = 0;
        for (int i = 0; i < feedbackTypes.length; i++) {
            if (feedbackTypes[i].equals(savedType)) {
                typeIdx = i;
                break;
            }
        }
        typeCombo.setValue(feedbackTypeNames[typeIdx]);
        typeCombo.getStyleClass().add("select-field");
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        final int[] selectedType = {typeIdx};
        typeCombo.setOnAction(e -> {
            String val = typeCombo.getValue();
            for (int i = 0; i < feedbackTypeNames.length; i++) {
                if (feedbackTypeNames[i].equals(val)) {
                    selectedType[0] = i;
                    ctx.config().put("settings.feedback.type", feedbackTypes[i]);
                    ctx.saveConfig();
                    break;
                }
            }
        });

        TextArea content = new TextArea();
        content.setPromptText(I18N.get("feedback.t0008"));
        content.setPrefHeight(100);
        content.getStyleClass().add("textarea-field");
        VBox contentBox = new VBox(8, typeCombo, content);
        contentBox.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("feedback.t0009"), I18N.get("feedback.t0010"), contentBox));

        // 卡片「附件」：选择图片 + 缩略图预览（文件名+大小） + 自动附加最新日志开关 //
        Label fileLabel = new Label(I18N.get("feedback.t0011"));
        fileLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        fileLabel.setWrapText(true);
        // 最小宽度：避免在 HBox 里被压缩成一列竖排文字
        fileLabel.setMinWidth(140);
        Button chooseFileBtn = AppIcons.button("attach", I18N.get("feedback.t0012"));
        chooseFileBtn.getStyleClass().add("btn-primary");
        final java.io.File[] selectedFile = new java.io.File[1];
        chooseFileBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(I18N.get("feedback.t0013"));
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter(I18N.get("feedback.t0014"), "*.png", "*.jpg", "*.jpeg", "*.gif")
            );
            java.io.File f = fc.showOpenDialog(ctx.stage());
            if (f != null) {
                selectedFile[0] = f;
                fileLabel.setText(I18N.get("feedback.k0026", f.getName() + " (" + FormatUtils.formatFileSize(f.length()) + ")"));
            }
        });

        // 自动附加最新日志开关（新增配置键 settings.feedback.attachLog，默认关闭；见文档 §6.13 行为变更说明）
        CheckBox attachLogToggle = PageKit.toggle(
                "true".equalsIgnoreCase(ctx.config().getOrDefault("settings.feedback.attachLog", "false")));
        attachLogToggle.setOnAction(e -> {
            ctx.config().put("settings.feedback.attachLog", String.valueOf(attachLogToggle.isSelected()));
            ctx.saveConfig();
            ctx.toast(I18N.get("feedback.k0027", (attachLogToggle.isSelected() ? I18N.get("feedback.t0015") : I18N.get("feedback.t0016"))));
        });
        VBox attachBox = new VBox(10,
                new HBox(10, chooseFileBtn, fileLabel),
                PageKit.settingsCard(I18N.get("feedback.t0017"), I18N.get("feedback.t0018"),
                        attachLogToggle));
        attachBox.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCardStacked(I18N.get("feedback.t0019"), I18N.get("feedback.t0020"), attachBox));

        // 发送按钮（底部右对齐；发送中禁用并换文案，不可重复提交） //
        Button sendBtn = AppIcons.button("send", I18N.get("feedback.t0021"));
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> {
            String email = emailField.getText().trim();
            String msg = content.getText().trim();
            if (msg.isEmpty()) {
                // 必填校验就地提示（重构 §6.13 验收）
                content.setPromptText(I18N.get("feedback.t0022"));
                ctx.toast(I18N.get("feedback.t0023"));
                return;
            }
            // 保存邮箱配置，下次打开反馈页自动填充
            if (!email.isEmpty()) {
                ctx.config().put("FeedbackEmail", email);
                ctx.saveConfig();
            }
            sendBtn.setDisable(true);
            sendBtn.setText(I18N.get("feedback.t0024"));
            String finalEmail = email.isEmpty() ? "anonymous@unknown.com" : email;
            // 类型前缀拼进正文（[类型: bug] …），不改 FeedbackEmailSender 签名
            String typeName = feedbackTypes[selectedType[0]];
            String typedMsg = I18N.get("feedback.k0028", typeName + "]\n" + msg);
            java.io.File finalFile = selectedFile[0];
            // 自动附加日志：把 logs/starlight.log 尾部（≤1MB）落成临时文件作为第二个附件
            java.io.File logAttachment = null;
            if (attachLogToggle.isSelected()) {
                logAttachment = prepareLogAttachment();
                if (logAttachment == null) {
                    ctx.toast(I18N.get("feedback.t0025"));
                }
            }
            java.io.File finalLog = logAttachment;
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    FeedbackEmailSender.sendFeedback(finalEmail, finalEmail, typedMsg, finalFile, finalLog);
                    Platform.runLater(() -> {
                        ctx.toast(I18N.get("feedback.t0026"));
                        sendBtn.setDisable(false);
                        AppIcons.setText(sendBtn, "send", I18N.get("feedback.t0027"));
                        emailField.clear();
                        content.clear();
                        selectedFile[0] = null;
                        fileLabel.setText(I18N.get("feedback.t0028"));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        ctx.toast(I18N.get("feedback.k0001", ex.getMessage()));
                        sendBtn.setDisable(false);
                        AppIcons.setText(sendBtn, "send", I18N.get("feedback.t0029"));
                    });
                }
            });
        });
        HBox btnBox = new HBox(sendBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        root.getChildren().add(btnBox);
        return root;
    }

    /**
     * 把 logs/starlight.log 尾部（≤1MB，与 DevPage 上传日志的截取规则一致）写成临时文件，
     * 作为反馈邮件的附加日志；文件不存在或读取失败返回 null（调用方提示后继续发送）。
     */
    private java.io.File prepareLogAttachment() {
        try {
            java.nio.file.Path mainLog = java.nio.file.Path.of("logs", "starlight.log");
            if (!java.nio.file.Files.isRegularFile(mainLog)) return null;
            byte[] all = java.nio.file.Files.readAllBytes(mainLog);
            final long limit = 1024L * 1024L;
            byte[] tail = all.length > limit
                    ? java.util.Arrays.copyOfRange(all, all.length - (int) limit, all.length)
                    : all;
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("starlight-feedback-", ".log");
            java.nio.file.Files.write(tmp, tail);
            tmp.toFile().deleteOnExit();
            return tmp.toFile();
        } catch (Exception ex) {
            System.err.println("[Feedback] 附加日志失败: " + ex.getMessage());
            return null;
        }
    }
}

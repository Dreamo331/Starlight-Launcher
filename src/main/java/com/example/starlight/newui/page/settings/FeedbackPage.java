package com.example.starlight.newui.page.settings;

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
        VBox root = PageKit.settingsPage(ctx, "服务与反馈");

        // 卡片「联系方式」：用户邮箱（用于回复），从配置加载上次填写的邮箱（重构 §6.13） //
        TextField emailField = new TextField(ctx.config().getOrDefault("FeedbackEmail", ""));
        emailField.setPromptText("your@email.com");
        emailField.getStyleClass().add("input-field");
        root.getChildren().add(PageKit.settingsCard("联系方式", "方便我们回复您", emailField));

        // 卡片「反馈内容」：类型下拉 + 多行文本框 //
        // 反馈类型下拉（新增配置键 settings.feedback.type，默认 bug；随正文以 [类型: …] 前缀提交，见文档 §6.13 行为变更说明）
        String[] feedbackTypes = {"bug", "suggestion", "question", "other"};
        String[] feedbackTypeNames = {"问题报告 (bug)", "功能建议", "使用疑问", "其他"};
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
        content.setPromptText("请详细描述您遇到的问题或建议...");
        content.setPrefHeight(100);
        content.getStyleClass().add("textarea-field");
        VBox contentBox = new VBox(8, typeCombo, content);
        contentBox.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCardStacked("反馈内容", "先选类型，再详细描述", contentBox));

        // 卡片「附件」：选择图片 + 缩略图预览（文件名+大小） + 自动附加最新日志开关 //
        Label fileLabel = new Label("未选择文件");
        fileLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        fileLabel.setWrapText(true);
        Button chooseFileBtn = AppIcons.button("attach", "选择图片");
        chooseFileBtn.getStyleClass().add("btn-primary");
        final java.io.File[] selectedFile = new java.io.File[1];
        chooseFileBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择反馈附件图片");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("图片文件", "*.png", "*.jpg", "*.jpeg", "*.gif")
            );
            java.io.File f = fc.showOpenDialog(ctx.stage());
            if (f != null) {
                selectedFile[0] = f;
                fileLabel.setText("已选: " + f.getName() + " (" + FormatUtils.formatFileSize(f.length()) + ")");
            }
        });

        // 自动附加最新日志开关（新增配置键 settings.feedback.attachLog，默认关闭；见文档 §6.13 行为变更说明）
        CheckBox attachLogToggle = PageKit.toggle(
                "true".equalsIgnoreCase(ctx.config().getOrDefault("settings.feedback.attachLog", "false")));
        attachLogToggle.setOnAction(e -> {
            ctx.config().put("settings.feedback.attachLog", String.valueOf(attachLogToggle.isSelected()));
            ctx.saveConfig();
            ctx.toast("自动附加最新日志: " + (attachLogToggle.isSelected() ? "开启" : "关闭"));
        });
        VBox attachBox = new VBox(10,
                new HBox(10, chooseFileBtn, fileLabel),
                PageKit.settingsCard("自动附加最新日志", "发送反馈时把 logs/starlight.log 尾部（≤1MB）一并发出，便于排查问题",
                        attachLogToggle));
        attachBox.setMinWidth(0);
        root.getChildren().add(PageKit.settingsCardStacked("附件", "可选，截图或报错图片", attachBox));

        // 发送按钮（底部右对齐；发送中禁用并换文案，不可重复提交） //
        Button sendBtn = AppIcons.button("send", "发送反馈");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> {
            String email = emailField.getText().trim();
            String msg = content.getText().trim();
            if (msg.isEmpty()) {
                // 必填校验就地提示（重构 §6.13 验收）
                content.setPromptText("反馈内容不能为空，请详细描述...");
                ctx.toast("请输入反馈内容");
                return;
            }
            // 保存邮箱配置，下次打开反馈页自动填充
            if (!email.isEmpty()) {
                ctx.config().put("FeedbackEmail", email);
                ctx.saveConfig();
            }
            sendBtn.setDisable(true);
            sendBtn.setText("发送中...");
            String finalEmail = email.isEmpty() ? "anonymous@unknown.com" : email;
            // 类型前缀拼进正文（[类型: bug] …），不改 FeedbackEmailSender 签名
            String typeName = feedbackTypes[selectedType[0]];
            String typedMsg = "[类型: " + typeName + "]\n" + msg;
            java.io.File finalFile = selectedFile[0];
            // 自动附加日志：把 logs/starlight.log 尾部（≤1MB）落成临时文件作为第二个附件
            java.io.File logAttachment = null;
            if (attachLogToggle.isSelected()) {
                logAttachment = prepareLogAttachment();
                if (logAttachment == null) {
                    ctx.toast("未找到 logs/starlight.log，本次不附加日志");
                }
            }
            java.io.File finalLog = logAttachment;
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    FeedbackEmailSender.sendFeedback(finalEmail, finalEmail, typedMsg, finalFile, finalLog);
                    Platform.runLater(() -> {
                        ctx.toast("反馈发送成功！");
                        sendBtn.setDisable(false);
                        AppIcons.setText(sendBtn, "send", "发送反馈");
                        emailField.clear();
                        content.clear();
                        selectedFile[0] = null;
                        fileLabel.setText("未选择文件");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        ctx.toast("发送失败: " + ex.getMessage());
                        sendBtn.setDisable(false);
                        AppIcons.setText(sendBtn, "send", "发送反馈");
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

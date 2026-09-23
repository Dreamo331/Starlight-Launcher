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
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
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

        // 用户邮箱（用于回复），从配置加载上次填写的邮箱
        TextField emailField = new TextField(ctx.config().getOrDefault("FeedbackEmail", ""));
        emailField.setPromptText("your@email.com");
        emailField.getStyleClass().add("input-field");
        root.getChildren().add(PageKit.settingsCard("您的邮箱", "方便我们回复您", emailField));

        // 反馈内容
        TextArea content = new TextArea();
        content.setPromptText("请详细描述您遇到的问题或建议...");
        content.setPrefHeight(100);
        content.getStyleClass().add("textarea-field");
        root.getChildren().add(PageKit.settingsCard("反馈内容", "请详细描述", content));

        // 图片附件（可选）
        Label fileLabel = new Label("未选择文件");
        fileLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
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
                fileLabel.setText(f.getName() + " (" + FormatUtils.formatFileSize(f.length()) + ")");
            }
        });
        HBox fileRow = new HBox(10, chooseFileBtn, fileLabel);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("图片附件", "可选，截图或报错图片", fileRow));

        // 发送按钮
        Button sendBtn = AppIcons.button("send", "发送反馈");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> {
            String email = emailField.getText().trim();
            String msg = content.getText().trim();
            if (msg.isEmpty()) {
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
            java.io.File finalFile = selectedFile[0];
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                try {
                    FeedbackEmailSender.sendFeedback(finalEmail, finalEmail, msg, finalFile);
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
}

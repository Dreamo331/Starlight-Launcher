/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javafx.application.Platform
 *  javafx.geometry.Insets
 *  javafx.geometry.Pos
 *  javafx.scene.Node
 *  javafx.scene.Parent
 *  javafx.scene.Scene
 *  javafx.scene.control.Button
 *  javafx.scene.control.Label
 *  javafx.scene.control.TextField
 *  javafx.scene.control.Tooltip
 *  javafx.scene.image.Image
 *  javafx.scene.image.ImageView
 *  javafx.scene.input.Clipboard
 *  javafx.scene.input.ClipboardContent
 *  javafx.scene.layout.HBox
 *  javafx.scene.layout.Priority
 *  javafx.scene.layout.Region
 *  javafx.scene.layout.VBox
 *  javafx.stage.Stage
 *  javafx.stage.Window
 */
package com.example.starlight.newui.ui;

import com.example.starlight.log.QRcode;
import com.example.starlight.log.sendlogs;
import com.example.starlight.model.LogUploadRecord;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.service.LogUploadStore;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

public final class LogUploadResultWindow {
    private LogUploadResultWindow() {
    }

    public static void show(Window owner, LogUploadRecord record, Consumer<String> toast, Runnable onDeleted) {
        if (record == null) {
            return;
        }
        Stage stage = new Stage();
        stage.setTitle("\u65e5\u5fd7\u4e0a\u4f20\u6210\u529f");
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setMinWidth(400.0);
        stage.setMinHeight(430.0);
        VBox root = new VBox(14.0);
        root.setPadding(new Insets(22.0, 24.0, 20.0, 24.0));
        root.setAlignment(Pos.TOP_CENTER);
        Label title = new Label("\u4e0a\u4f20\u6210\u529f\uff0c\u5206\u4eab\u65e5\u5fd7\u7ed9\u4ed6\u4eba\u6392\u67e5");
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: 700;");
        Label sub = new Label("\u626b\u63cf\u4e8c\u7ef4\u7801\uff0c\u6216\u590d\u5236\u4e0b\u65b9\u94fe\u63a5");
        sub.setStyle("-fx-font-size: 12px; -fx-text-fill: #6b7280;");
        ImageView qrView = new ImageView();
        qrView.setFitWidth(220.0);
        qrView.setFitHeight(220.0);
        qrView.setPreserveRatio(true);
        qrView.setStyle("-fx-background-color: #ffffff; -fx-padding: 8;");
        String qrPath = LogUploadResultWindow.saveQrToTemp(record);
        if (qrPath != null) {
            qrView.setImage(new Image(Paths.get(qrPath, new String[0]).toUri().toString()));
        } else {
            Label qrFail = new Label("\u4e8c\u7ef4\u7801\u751f\u6210\u5931\u8d25\n\uff08\u94fe\u63a5\u4ecd\u53ef\u590d\u5236\u4f7f\u7528\uff09");
            qrFail.setStyle("-fx-font-size: 12px; -fx-text-fill: #dc2626;");
            root.getChildren().add(qrFail);
            qrView = null;
        }
        TextField urlField = new TextField(record.url);
        urlField.setEditable(false);
        urlField.getStyleClass().add("input-field");
        urlField.setPrefWidth(320.0);
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setTooltip(new Tooltip(record.url));
        Button copyBtn = AppIcons.button("copy", "\u590d\u5236");
        copyBtn.getStyleClass().add("btn-primary");
        copyBtn.setStyle("-fx-padding: 6 14; -fx-font-size: 12px; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(record.url);
            Clipboard.getSystemClipboard().setContent(cc);
            LogUploadResultWindow.notify(toast, "\u94fe\u63a5\u5df2\u590d\u5236\u5230\u526a\u8d34\u677f");
        });
        HBox urlRow = new HBox(8.0, urlField, copyBtn);
        urlRow.setAlignment(Pos.CENTER_LEFT);
        Button revokeBtn = new Button("\u64a4\u56de\uff08\u5220\u9664\u8be5\u65e5\u5fd7\uff09");
        revokeBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;-fx-background-color: #fee2e2; -fx-text-fill: #dc2626; -fx-background-radius: 8;");
        boolean canRevoke = record.id != null && !record.id.isEmpty() && record.token != null && !record.token.isEmpty();
        revokeBtn.setDisable(!canRevoke);
        if (!canRevoke) {
            revokeBtn.setTooltip(new Tooltip("\u8be5\u8bb0\u5f55\u6765\u81ea\u65e7\u7248\u7aef\u70b9\uff0c\u65e0\u5220\u9664 token\uff0c\u65e0\u6cd5\u64a4\u56de"));
        }
        revokeBtn.setOnAction(e -> LogUploadResultWindow.doRevoke(stage, record, revokeBtn, toast, onDeleted));
        Button closeBtn = new Button("\u5173\u95ed");
        closeBtn.getStyleClass().add("modal-btn-cancel");
        closeBtn.setStyle("-fx-padding: 6 16; -fx-font-size: 12px; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox btnRow = new HBox(8.0, revokeBtn, spacer, closeBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        if (qrView != null) {
            root.getChildren().addAll(title, sub, qrView, urlRow, btnRow);
        } else {
            root.getChildren().addAll(title, sub, urlRow, btnRow);
        }
        Scene scene = new Scene(root);
        URL css = LogUploadResultWindow.class.getResource("/fxml/css/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void doRevoke(Stage stage, LogUploadRecord record, Button revokeBtn, Consumer<String> toast, Runnable onDeleted) {
        revokeBtn.setDisable(true);
        revokeBtn.setText("\u6b63\u5728\u64a4\u56de...");
        new Thread(() -> {
            boolean[] okArr = new boolean[]{false};
            String[] errArr = new String[]{null};
            try {
                okArr[0] = sendlogs.deleteLog(record.id, record.token);
            }
            catch (Exception ex) {
                errArr[0] = ex.getMessage();
            }
            Platform.runLater(() -> {
                if (okArr[0]) {
                    LogUploadStore.remove(record.id);
                    LogUploadResultWindow.notify(toast, "\u5df2\u64a4\u56de\u5e76\u5220\u9664\u8be5\u65e5\u5fd7");
                    stage.close();
                    if (onDeleted != null) {
                        onDeleted.run();
                    }
                } else {
                    revokeBtn.setDisable(false);
                    revokeBtn.setText("\u64a4\u56de\uff08\u5220\u9664\u8be5\u65e5\u5fd7\uff09");
                    LogUploadResultWindow.notify(toast, "\u64a4\u56de\u5931\u8d25: " + (errArr[0] == null ? "\u672a\u77e5\u9519\u8bef" : errArr[0]));
                }
            });
        }, "log-revoke").start();
    }

    private static String saveQrToTemp(LogUploadRecord record) {
        try {
            BufferedImage qr = QRcode.generateQRCodeImage(record.url);
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "starlight-qr");
            Files.createDirectories(dir, new FileAttribute[0]);
            String idPart = record.id == null || record.id.isEmpty() ? String.valueOf(System.currentTimeMillis()) : record.id;
            Path out = dir.resolve("starlight-log-" + idPart + ".png");
            ImageIO.write(qr, "png", out.toFile());
            return out.toString();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void notify(Consumer<String> toast, String msg) {
        if (toast != null) {
            try {
                toast.accept(msg);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }
}

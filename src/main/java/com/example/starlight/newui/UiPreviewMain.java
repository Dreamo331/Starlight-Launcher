package com.example.starlight.newui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

/** 临时 UI 预览：把「设置 → 联机设置」页离屏渲染成 PNG，用于自检布局（跑完即删） */
public class UiPreviewMain extends Application {

    @Override
    public void start(Stage stage) {
        try {
            // 与正式启动一致：先注册 UI 字体，否则预览里的字体回退到系统字体、看不出真实效果
            com.example.starlight.newui.ui.AppFonts.install();

            LauncherView view = new LauncherView(stage);

            Field cacheField = LauncherView.class.getDeclaredField("pageCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Node> cache = (Map<String, Node>) cacheField.get(view);
            Node page = cache.get("sidebarMultiplayer");
            if (page == null) {
                System.out.println("PREVIEW: page not found, keys=" + cache.keySet());
                Platform.exit();
                return;
            }

            StackPane holder = new StackPane(page);
            holder.setStyle("-fx-background-color: #eef2f7; -fx-padding: 16;");
            holder.getStyleClass().addAll("root", "light");
            holder.setPrefSize(966, 620);

            Scene scene = new Scene(holder, 966, 620);
            try {
                scene.getStylesheets().add(
                        UiPreviewMain.class.getResource("/fxml/css/style.css").toExternalForm());
            } catch (Exception e) {
                System.out.println("PREVIEW: stylesheet missing: " + e.getMessage());
            }
            holder.applyCss();
            holder.layout();

            WritableImage image = scene.snapshot(null);
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader pr = image.getPixelReader();
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                pr.getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
                bi.setRGB(0, y, w, 1, row, 0, w);
            }
            File out = new File("target/ui_preview/multiplayer_settings.png");
            out.getParentFile().mkdirs();
            ImageIO.write(bi, "png", out);
            System.out.println("PREVIEW saved: " + out.getAbsolutePath() + " (" + w + "x" + h + ")");
        } catch (Throwable t) {
            System.out.println("PREVIEW failed:");
            t.printStackTrace(System.out);
        } finally {
            Platform.exit();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

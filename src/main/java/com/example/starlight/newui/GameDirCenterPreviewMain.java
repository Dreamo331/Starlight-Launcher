package com.example.starlight.newui;

import com.example.starlight.config.GameDirManager;
import com.example.starlight.newui.ui.AppFonts;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** 临时预览：模拟 LauncherView 的 noScroll + VBox.vgrow，确认卡片垂直居中 */
public class GameDirCenterPreviewMain extends Application {

    private Scene scene;

    @Override
    public void start(Stage stage) {
        try {
            AppFonts.install();
            LauncherView view = new LauncherView(stage);
            Field cacheField = LauncherView.class.getDeclaredField("pageCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Node> cache = (Map<String, Node>) cacheField.get(view);
            Node page = cache.get("sidebarGameDir");

            VBox mainContent = new VBox(page);
            mainContent.getStyleClass().add("main-content");
            mainContent.setPrefSize(1000, 760);
            VBox.setVgrow(page, Priority.ALWAYS);

            StackPane holder = new StackPane(mainContent);
            holder.getStyleClass().addAll("root", "light");
            holder.setPrefSize(1000, 760);

            scene = new Scene(holder, 1000, 760);
            scene.getStylesheets().add(
                    GameDirCenterPreviewMain.class.getResource("/fxml/css/style.css").toExternalForm());
            stage.setScene(scene);
            stage.show();

            Field gpField = LauncherView.class.getDeclaredField("gameDirPage");
            gpField.setAccessible(true);
            Object gameDirPage = gpField.get(view);

            PauseTransition listWait = new PauseTransition(Duration.millis(1100));
            listWait.setOnFinished(e -> {
                snapshot("game_dir_centered_list");
                List<GameDirManager.GameDirEntry> entries = GameDirManager.load(view.config());
                if (!entries.isEmpty()) {
                    try {
                        Method m = gameDirPage.getClass().getDeclaredMethod(
                                "showDetailView", GameDirManager.GameDirEntry.class);
                        m.setAccessible(true);
                        m.invoke(gameDirPage, entries.get(0));
                    } catch (Throwable t) {
                        t.printStackTrace(System.out);
                    }
                }
                PauseTransition detailWait = new PauseTransition(Duration.millis(1300));
                detailWait.setOnFinished(e2 -> {
                    snapshot("game_dir_centered_detail");
                    Platform.exit();
                });
                detailWait.play();
            });
            listWait.play();
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            Platform.exit();
        }
    }

    private void snapshot(String name) {
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
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
            File out = new File("target/ui_preview/" + name + ".png");
            out.getParentFile().mkdirs();
            ImageIO.write(bi, "png", out);
            System.out.println("PREVIEW saved: " + out.getAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

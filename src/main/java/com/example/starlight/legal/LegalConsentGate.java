package com.example.starlight.legal;

import com.example.starlight.main.ConfigManager;
import com.example.starlight.newui.AppConfig;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.util.I18N;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 首次启动的法律文档同意页（《免责声明》《用户服务协议》《隐私政策》）。
 *
 * <p>合规要点（对应《个人信息保护法》的「告知—同意」与用户协议第二条）：
 * <ol>
 *   <li><b>先告知、后使用</b>：本页在主界面与后台服务初始化之前显示，
 *       未同意则不会进入启动器任何功能；</li>
 *   <li><b>主动勾选</b>：「同意并继续」按钮默认禁用，必须自己勾选确认框后才可点击，
 *       不存在「继续即视为同意」的默认勾选；</li>
 *   <li><b>不同意可退出</b>：明确的「不同意，退出」按钮，不诱导、不隐藏；</li>
 *   <li><b>留痕</b>：同意后由 {@link LegalConsentStore} 在本机写入同意记录
 *       （时间、启动器版本、三份文档哈希），不上传任何信息。</li>
 * </ol>
 *
 * <p>本页没有独立的 Stage：复用主窗口，同意后直接回调 {@code onAccepted} 继续启动流程。
 * 这样不会出现「关掉唯一的窗口触发 JavaFX 隐式退出」的问题。
 */
public final class LegalConsentGate {

    private static final Logger log = LoggerFactory.getLogger(LegalConsentGate.class);

    /** 窗口尺寸：够放三份文档的表格，又不至于在小屏上超出 */
    private static final double WIDTH = 940;
    private static final double HEIGHT = 720;

    private LegalConsentGate() {
    }

    /**
     * 展示同意页。
     *
     * @param stage      主窗口（尚未显示）
     * @param onAccepted 用户勾选并点击「同意并继续」后的回调（已写入同意记录）
     */
    public static void show(Stage stage, Runnable onAccepted) {
        boolean dark = isDarkTheme();

        // 头部：图标 + 标题 + 版本号
        StackPane iconBox = new StackPane(AppIcons.icon("shield", 24,
                dark ? Color.web("#93c5fd") : Color.web("#2563eb")));
        iconBox.getStyleClass().add("legal-consent-icon");

        Label title = new Label(I18N.get("legal.t0001"));
        title.getStyleClass().add("legal-consent-title");
        Label subtitle = new Label(I18N.get("legal.t0002"));
        subtitle.getStyleClass().add("legal-consent-sub");
        subtitle.setWrapText(true);
        VBox titles = new VBox(3, title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titles, Priority.ALWAYS);

        Label version = new Label(I18N.get("legal.t0020", AppConfig.DISPLAY_VERSION));
        version.getStyleClass().add("legal-consent-version");

        HBox header = new HBox(14, iconBox, titles, version);
        header.setAlignment(Pos.CENTER_LEFT);

        // 文档页签 + 内容区（三份文档一次构建好，切换只改可见性）
        HBox tabs = new HBox(8);
        StackPane content = new StackPane();
        content.getStyleClass().add("legal-consent-content");
        VBox.setVgrow(content, Priority.ALWAYS);

        List<Button> tabButtons = new ArrayList<>();
        List<ScrollPane> docPanes = new ArrayList<>();
        for (LegalDocument doc : LegalDocument.values()) {
            Button tab = new Button(doc.title());
            tab.getStyleClass().add("legal-tab");
            tabButtons.add(tab);
            tabs.getChildren().add(tab);

            ScrollPane pane = new ScrollPane(LegalMarkdownView.render(doc.text()));
            pane.setFitToWidth(true);
            pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            pane.getStyleClass().add("legal-doc-scroll");
            pane.setVisible(false);
            pane.setManaged(false);
            docPanes.add(pane);
            content.getChildren().add(pane);
        }

        // 页签切换：只有一个文档可见
        for (int idx = 0; idx < tabButtons.size(); idx++) {
            final int selected = idx;
            tabButtons.get(idx).setOnAction(e -> {
                for (int k = 0; k < docPanes.size(); k++) {
                    boolean on = k == selected;
                    docPanes.get(k).setVisible(on);
                    docPanes.get(k).setManaged(on);
                    if (on) docPanes.get(k).setVvalue(0);
                    tabButtons.get(k).getStyleClass().remove("selected");
                    if (on) tabButtons.get(k).getStyleClass().add("selected");
                }
            });
        }
        tabButtons.get(0).getStyleClass().add("selected");
        docPanes.get(0).setVisible(true);
        docPanes.get(0).setManaged(true);

        // 同意区：主动勾选 + 两个明确按钮
        LegalDocument[] docs = LegalDocument.values();
        CheckBox confirm = new CheckBox(I18N.get("legal.t0005",
                docs.length > 0 ? docs[0].title() : "",
                docs.length > 1 ? docs[1].title() : "",
                docs.length > 2 ? docs[2].title() : ""));
        confirm.getStyleClass().add("legal-consent-check");
        confirm.setWrapText(true);
        confirm.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(confirm, Priority.ALWAYS);

        Button decline = new Button(I18N.get("legal.t0004"));
        decline.getStyleClass().add("modal-btn-cancel");
        Button accept = new Button(I18N.get("legal.t0003"));
        accept.getStyleClass().add("modal-btn-ok");
        accept.setDisable(true);
        confirm.selectedProperty().addListener((obs, was, now) -> accept.setDisable(!now));

        HBox actions = new HBox(10, decline, accept);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox footer = new HBox(16, confirm, actions);
        footer.setAlignment(Pos.CENTER_LEFT);

        Label recordHint = new Label(I18N.get("legal.t0006"));
        recordHint.getStyleClass().add("legal-consent-hint");
        recordHint.setWrapText(true);
        // 不能只靠 wrapText：Label 的首选宽度等于整段文字宽度，VBox 会把它撑出卡片右缘后被裁掉
        recordHint.setMaxWidth(Double.MAX_VALUE);
        recordHint.setMinWidth(0);

        VBox card = new VBox(14, header, tabs, content, footer, recordHint);
        card.getStyleClass().add("legal-consent-card");
        card.setPadding(new Insets(24, 28, 20, 28));

        StackPane root = new StackPane(card);
        root.setPadding(new Insets(14));
        root.getStyleClass().addAll("root", dark ? "dark" : "light");

        // 与主窗口一致的圆角裁剪
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        root.setClip(clip);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        var css = LegalConsentGate.class.getResource("/fxml/css/style.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        // 交互
        accept.setOnAction(e -> {
            boolean recorded = LegalConsentStore.accept();
            if (!recorded) {
                // 写不了记录不阻断使用（例如数据目录只读），但必须留下日志，
                // 下次启动会因为「无有效记录」再次弹出本页。
                log.warn("同意记录写入失败，本次仍继续启动；下次启动将再次要求确认");
            }
            onAccepted.run();
        });
        decline.setOnAction(e -> {
            log.info("用户不同意法律文档，启动器退出");
            javafx.application.Platform.exit();
        });

        // 无边框窗口没有系统关闭键：Alt+F4 / 任务栏关闭一律按「不同意」处理
        stage.setOnCloseRequest(e -> {
            e.consume();
            javafx.application.Platform.exit();
        });

        // 无边框窗口拖动：按住头部区域可移动窗口
        final double[] drag = new double[2];
        header.setOnMousePressed(e -> {
            drag[0] = e.getScreenX() - stage.getX();
            drag[1] = e.getScreenY() - stage.getY();
        });
        header.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - drag[0]);
            stage.setY(e.getScreenY() - drag[1]);
        });

        stage.setTitle("Starlight Launcher");
        stage.setScene(scene);
        stage.setResizable(true);
        stage.setMinWidth(760);
        stage.setMinHeight(560);
        stage.setWidth(Math.min(WIDTH, maxWindowWidth()));
        stage.setHeight(Math.min(HEIGHT, maxWindowHeight()));
        stage.centerOnScreen();
        stage.show();
        log.info("显示法律文档同意页（三份文档，需主动勾选确认）");
    }

    /** 深色主题判定：只认 {@code Theme=dark}；system 与未知值按浅色处理（与主界面的默认观感一致） */
    private static boolean isDarkTheme() {
        try {
            Map<String, String> cfg = ConfigManager.readClientConfig();
            return "dark".equalsIgnoreCase(cfg.getOrDefault("Theme", "light"));
        } catch (Exception e) {
            return false;
        }
    }

    private static double maxWindowWidth() {
        try {
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            return Math.max(760, visual.getWidth() - 60);
        } catch (Exception e) {
            return WIDTH;
        }
    }

    private static double maxWindowHeight() {
        try {
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            return Math.max(560, visual.getHeight() - 80);
        } catch (Exception e) {
            return HEIGHT;
        }
    }
}

package com.example.starlight.newui.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Transition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 通用界面服务：Toast、图片预览、遮罩弹窗、确认 / 提示 / 输入面板、开源许可面板。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，仅可见性与类名调整），
 * 全部控件挂在构造时传入的 rootPane 上，样式类（modal-* / input-field）与原实现保持一致。
 */
public final class UiService {

    private final StackPane rootPane;
    private HBox toastBox;
    private Label toastLabel;
    private Region toastDot;
    private PauseTransition toastTimer;
    private ParallelTransition toastAnim;
    /** 提示条当前是否处于「显示」态：重复弹提示时不再重播进场动画 */
    private boolean toastShown;
    private StackPane modalOverlay;

    /** 提示条静止位置：距顶部 52px（对应 .toast 的 top:52px） */
    private static final double TOAST_Y = 52;
    /** 隐藏态：相对静止位置再上移 14px、缩到 0.96（对应 .toast 的 translate(-50%,-14px) scale(0.96)） */
    private static final double TOAST_Y_HIDDEN = TOAST_Y - 14;
    private static final double TOAST_SCALE_HIDDEN = 0.96;
    /** 进出场时长，对应 CSS 的 transition: all 0.32s */
    private static final Duration TOAST_MOVE = Duration.millis(320);
    /** 停留时长，对应 JS 的 setTimeout(..., 2200)：到点后移除 .show，提示条平滑向上淡出缩回 */
    private static final Duration TOAST_HOLD = Duration.millis(2200);
    /** cubic-bezier(0.34, 1.4, 0.64, 1)：y1=1.4 让提示条滑入时轻微回弹，不是生硬的线性滑入 */
    private static final Interpolator TOAST_EASE = new CubicBezierInterpolator(0.34, 1.4, 0.64, 1);

    public UiService(StackPane rootPane) {
        this.rootPane = rootPane;
    }

    /** 当前遮罩面板（供调用方校验自己打开的弹窗是否仍是最上层，如启动动画弹窗） */
    public StackPane currentModalOverlay() {
        return modalOverlay;
    }

    /** 本地图片大图预览（半透明遮罩 + 点击关闭），支持磁盘文件路径 */
    public void localImagePreview(String filePath) {
        Image img;
        try {
            img = new Image(new File(filePath).toURI().toString());
            if (img.isError()) {
                toast("加载图片失败");
                return;
            }
        } catch (Exception e) {
            toast("加载图片失败: " + e.getMessage());
            return;
        }

        // 遮罩层
        StackPane overlay = new StackPane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.7);");
        overlay.prefWidthProperty().bind(rootPane.widthProperty());
        overlay.prefHeightProperty().bind(rootPane.heightProperty());

        // 图片视图（按窗口大小缩放，保留比例）
        ImageView imageView = new ImageView(img);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(Math.min(img.getWidth(), rootPane.getWidth() - 120));
        imageView.setFitHeight(Math.min(img.getHeight(), rootPane.getHeight() - 120));
        imageView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 4);");

        // 关闭提示
        Label closeHint = new Label("点击任意位置关闭");
        closeHint.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 12px;");
        StackPane.setAlignment(closeHint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(closeHint, new Insets(0, 0, 20, 0));

        overlay.getChildren().addAll(imageView, closeHint);

        // 点击关闭
        overlay.setOnMouseClicked(e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
            ft.setToValue(0.0);
            ft.setOnFinished(ev -> rootPane.getChildren().remove(overlay));
            ft.play();
        });

        // 入场动画
        overlay.setOpacity(0);
        rootPane.getChildren().add(overlay);
        overlay.toFront();
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setToValue(1.0);
        ft.play();
    }

    // ===== Toast =====

    /** 普通提示（默认 info 蓝点） */
    public void toast(String message) {
        toast(message, "info");
    }

    /**
     * 轻提示：顶部居中的白色卡片 + 彩色圆点。
     *
     * <p>对齐 弹窗样式.html 的 .toast：距顶部 52px、11px 圆角、柔和投影，
     * 圆点按类型着色（success 绿 / info 蓝 / warning 橙 / error 红）。
     *
     * <p>动画对齐该文件的 CSS/JS：
     * 隐藏态为「上移 14px + 缩到 0.96 + 全透明」，显示态为「原位 + 原始大小 + 不透明」，
     * 两者之间用 {@code cubic-bezier(0.34, 1.4, 0.64, 1)} 走 320ms（滑入时带一点回弹）；
     * 停留 2.2 秒后回到隐藏态，即平滑向上淡出缩回。
     */
    public void toast(String message, String type) {
        String t = (type == null || type.isBlank()) ? "info" : type;
        if (toastBox == null) {
            toastDot = new Region();
            toastDot.getStyleClass().addAll("toast-dot", t);
            toastDot.setMouseTransparent(true);
            toastLabel = new Label();
            toastLabel.getStyleClass().add("toast-text");
            toastBox = new HBox(10, toastDot, toastLabel);
            toastBox.getStyleClass().add("toast-box");
            toastBox.setAlignment(Pos.CENTER_LEFT);
            toastBox.setMouseTransparent(true);
            // HBox 作为 Pane，max 尺寸默认是无限大；放进 StackPane 会被拉伸铺满整个窗口，
            // 变成一层覆盖全屏的半透明白色。这里收紧到内容尺寸。
            toastBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        }
        // 类型可能变化，替换圆点上的类型样式类
        toastDot.getStyleClass().setAll("toast-dot", t);

        if (toastBox.getParent() == null) {
            rootPane.getChildren().add(toastBox);
            // 顶部居中、距顶 52px（对应 .toast 的 top:52px / left:50%）
            StackPane.setAlignment(toastBox, Pos.TOP_CENTER);
            // 新节点先落到隐藏态，进场动画才有起点可走
            toastBox.setTranslateY(TOAST_Y_HIDDEN);
            toastBox.setScaleX(TOAST_SCALE_HIDDEN);
            toastBox.setScaleY(TOAST_SCALE_HIDDEN);
            toastBox.setOpacity(0);
        }
        toastLabel.setText(message);
        toastBox.toFront();
        // 已经在显示时只换文案并重新计时（对应 CSS 里重复 add('show') 不会再触发过渡）；
        // 若正处在淡出途中，则从当前值继续滑回显示态，不会跳一下。
        if (!toastShown) {
            toastShown = true;
            playToastTransition(true);
        }
        if (toastTimer != null) toastTimer.stop();
        toastTimer = new PauseTransition(TOAST_HOLD);
        toastTimer.setOnFinished(e -> {
            toastShown = false;
            playToastTransition(false);
        });
        toastTimer.play();
    }

    /**
     * 提示条的进场 / 退场：位移、缩放、透明度三个子动画同时进行，各用同一条曲线。
     *
     * <p>曲线必须逐个设在子动画上：{@code ParallelTransition.interpolate} 是空实现，
     * 父级的 interpolator 不会下发给子动画，真正生效的是各子动画自己的 interpolator。
     */
    private void playToastTransition(boolean show) {
        if (toastAnim != null) toastAnim.stop();
        // 不设 from：从当前值出发，中途被打断也不会跳回起点
        TranslateTransition slide = new TranslateTransition(TOAST_MOVE, toastBox);
        slide.setToY(show ? TOAST_Y : TOAST_Y_HIDDEN);
        ScaleTransition zoom = new ScaleTransition(TOAST_MOVE, toastBox);
        zoom.setToX(show ? 1 : TOAST_SCALE_HIDDEN);
        zoom.setToY(show ? 1 : TOAST_SCALE_HIDDEN);
        FadeTransition fade = new FadeTransition(TOAST_MOVE, toastBox);
        fade.setToValue(show ? 1 : 0);
        for (Transition tr : new Transition[]{slide, zoom, fade}) {
            tr.setInterpolator(TOAST_EASE);
        }
        toastAnim = new ParallelTransition(slide, zoom, fade);
        toastAnim.play();
    }

    /** 图片查看器：在 rootPane 上弹出半透明遮罩显示图片，点击任意位置关闭 */
    public void imagePreview(String imagePath) {
        javafx.scene.image.Image img;
        try {
            img = new javafx.scene.image.Image(UiService.class.getResourceAsStream(imagePath));
            if (img.isError()) {
                toast("加载图片失败");
                return;
            }
        } catch (Exception e) {
            toast("加载图片失败: " + e.getMessage());
            return;
        }

        // 遮罩层
        StackPane overlay = new StackPane();
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.7);");
        overlay.prefWidthProperty().bind(rootPane.widthProperty());
        overlay.prefHeightProperty().bind(rootPane.heightProperty());

        // 图片视图
        javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(img);
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(400);
        imageView.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 4);");

        // 关闭提示
        Label closeHint = new Label("点击任意位置关闭");
        closeHint.setStyle("-fx-text-fill: rgba(255,255,255,0.5); -fx-font-size: 12px;");
        StackPane.setAlignment(closeHint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(closeHint, new Insets(0, 0, 20, 0));

        overlay.getChildren().addAll(imageView, closeHint);

        // 点击关闭
        overlay.setOnMouseClicked(e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
            ft.setToValue(0.0);
            ft.setOnFinished(ev -> rootPane.getChildren().remove(overlay));
            ft.play();
        });

        // 入场动画
        overlay.setOpacity(0);
        rootPane.getChildren().add(overlay);
        overlay.toFront();
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setToValue(1.0);
        ft.play();
    }

    // ===== 通用遮罩面板（集成弹窗：替代独立 Stage / Dialog / Alert） =====

    /** 在 rootPane 上弹出居中卡片面板：半透明遮罩 + 标题栏 + 关闭按钮。
     *  content 负责内部布局，传入 VBox/ScrollPane 时会自动拉伸填充；width/height 为卡片尺寸 */
    public void modal(String title, Node content, double width, double height) {
        modal(title, content, width, height, true);
    }

    /** 图标配色（对应 弹窗样式.html 的 .modal-icon.info/success/warning/danger） */
    private static javafx.scene.paint.Color variantColor(String variant) {
        return switch (variant == null ? "info" : variant) {
            case "success" -> javafx.scene.paint.Color.web("#22c55e");
            case "warning" -> javafx.scene.paint.Color.web("#d97706");
            case "danger" -> javafx.scene.paint.Color.web("#ef4444");
            default -> javafx.scene.paint.Color.web("#3b82f6");
        };
    }

    /**
     * 把已构建好的内容装进遮罩 + 卡片并淡入显示。
     *
     * <p>卡片尺寸固定，外层套一个紧贴的 StackPane，好让关闭按钮能浮在右上角
     * （对应 弹窗样式.html 的 .modal-close 绝对定位）。
     */
    private void showCard(Region body, double width, double height, boolean showCloseBtn) {
        if (modalOverlay != null) closeModal();

        VBox card = new VBox();
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(width);
        card.setMinWidth(width);
        card.setMaxWidth(width);
        // height <= 0 表示按内容自适应（标准弹窗用）；
        // height > 0 时只作为「最小高度」：不设 prefHeight，否则 VBox 会一直用这个高度渲染，
        // 内容更高时按钮就被挤到卡片外面去了。
        if (height > 0) {
            card.setMinHeight(height);
        }
        card.getChildren().add(body);
        VBox.setVgrow(body, Priority.ALWAYS);

        StackPane frame = new StackPane(card);
        frame.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        if (showCloseBtn) {
            Button closeBtn = new Button();
            closeBtn.getStyleClass().add("modal-close-btn");
            AppIcons.apply(closeBtn, "close", 13, null);
            closeBtn.setOnAction(e -> closeModal());
            frame.getChildren().add(closeBtn);
            StackPane.setAlignment(closeBtn, Pos.TOP_RIGHT);
            StackPane.setMargin(closeBtn, new Insets(12, 12, 0, 0));
        }

        StackPane overlay = new StackPane(frame);
        overlay.getStyleClass().add("modal-overlay");
        overlay.prefWidthProperty().bind(rootPane.widthProperty());
        overlay.prefHeightProperty().bind(rootPane.heightProperty());
        overlay.setAlignment(Pos.CENTER);

        modalOverlay = overlay;
        overlay.setOpacity(0);
        rootPane.getChildren().add(overlay);
        overlay.toFront();
        FadeTransition ft = new FadeTransition(Duration.millis(200), overlay);
        ft.setToValue(1.0);
        ft.play();
    }

    /**
     * 标准弹窗：居中「图标 → 标题 → 描述 → 附加内容 → 按钮」。
     *
     * <p>对齐 弹窗样式.html 的 .modal：内容整体居中、顶部一个 54×54 圆角图标块
     * （info/success/warning/danger 四态配色）、描述 13px 灰字、按钮等宽并排。
     *
     * @param iconName 图标名（{@link AppIcons} 中的名字）
     * @param variant  info / success / warning / danger
     * @param extra    附加内容（输入框、详情列表等），可为 null
     * @param actions  按钮行，可为 null
     */
    private void dialog(String iconName, String variant, String title, String desc,
                        Node extra, HBox actions, double width, boolean showCloseBtn) {
        VBox body = new VBox();
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(28, 26, 22, 26));

        StackPane iconBox = new StackPane();
        iconBox.getStyleClass().addAll("modal-icon", variant);
        iconBox.getChildren().add(AppIcons.icon(iconName, 26, variantColor(variant)));
        VBox.setMargin(iconBox, new Insets(0, 0, 16, 0));

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("modal-card-title");
        titleLabel.setWrapText(true);
        titleLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox.setMargin(titleLabel, new Insets(0, 0, 8, 0));

        body.getChildren().addAll(iconBox, titleLabel);

        if (desc != null && !desc.isBlank()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("modal-desc");
            descLabel.setWrapText(true);
            descLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            body.getChildren().add(descLabel);
        }
        if (extra != null) {
            if (extra instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
            VBox.setMargin(extra, new Insets(16, 0, 0, 0));
            body.getChildren().add(extra);
        }
        if (actions != null) {
            actions.setMaxWidth(Double.MAX_VALUE);
            VBox.setMargin(actions, new Insets(24, 0, 0, 0));
            body.getChildren().add(actions);
        }

        // 高度按内容自适应
        showCard(body, width, -1, showCloseBtn);
    }

    /** 等宽铺满的按钮（弹窗样式.html 里 .modal-btn 是 flex:1） */
    private static void fillWidth(Button b) {
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
    }

    /** 单个确定/知道了按钮，铺满整行 */
    private static HBox singleAction(String text, String styleClass, Runnable onClick) {
        HBox box = new HBox();
        Button b = new Button(text);
        b.getStyleClass().add(styleClass);
        fillWidth(b);
        if (onClick != null) b.setOnAction(e -> onClick.run());
        box.getChildren().add(b);
        return box;
    }

    public void modal(String title, Node content, double width, double height, boolean showCloseBtn) {
        VBox body = new VBox();
        // 与改造前的标题栏留白相当（原为 14/14/8/16 + 15px 标题），避免内容净高变大后挤出按钮
        body.setPadding(new Insets(20, 20, 16, 20));

        if (title != null && !title.isBlank()) {
            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("modal-card-title");
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            titleLabel.setAlignment(Pos.CENTER);
            titleLabel.setWrapText(true);
            VBox.setMargin(titleLabel, new Insets(0, 0, 10, 0));
            body.getChildren().add(titleLabel);
        }
        body.getChildren().add(content);
        VBox.setVgrow(content, Priority.ALWAYS);

        showCard(body, width, height, showCloseBtn);
    }

    /** 关闭当前遮罩面板（淡出后移除） */
    public void closeModal() {
        if (modalOverlay == null) return;
        StackPane overlay = modalOverlay;
        modalOverlay = null;
        FadeTransition ft = new FadeTransition(Duration.millis(180), overlay);
        ft.setToValue(0.0);
        ft.setOnFinished(e -> rootPane.getChildren().remove(overlay));
        ft.play();
    }

    /** 确认面板（异步回调风格）：点击 okText 按钮后关闭面板并执行 onConfirm，danger=true 时按钮红色 */
    public void confirm(String title, String message, String okText, boolean danger, Runnable onConfirm) {
        HBox actions = new HBox(10);
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        fillWidth(cancelBtn);
        cancelBtn.setOnAction(e -> closeModal());
        Button okBtn = new Button(okText);
        okBtn.getStyleClass().add(danger ? "modal-btn-danger" : "modal-btn-ok");
        fillWidth(okBtn);
        okBtn.setOnAction(e -> {
            closeModal();
            onConfirm.run();
        });
        actions.getChildren().addAll(cancelBtn, okBtn);

        dialog(danger ? "warning" : "help", danger ? "danger" : "info",
                title, message, null, actions, 380, false);
    }

    /** 信息提示面板（替代 Alert INFORMATION） */
    public void info(String title, String message) {
        dialog("info", "info", title, message, null,
                singleAction("确定", "modal-btn-ok", this::closeModal), 380, false);
    }

    /** 错误提示面板（替代 Alert ERROR） */
    public void error(String title, String message) {
        dialog("error", "danger", title, message, null,
                singleAction("确定", "modal-btn-danger", this::closeModal), 380, false);
    }

    /** 单行文本输入面板（替代 TextInputDialog）：输入非空时点击确定回调 onOk，回车等效确定 */
    public void input(String title, String prompt, String placeholder, Consumer<String> onOk) {
        input(title, prompt, placeholder, null, onOk);
    }

    /** 单行文本输入面板（带初始值，用于重命名等「先展示当前值再修改」的场景） */
    public void input(String title, String prompt, String placeholder, String initialValue,
                                Consumer<String> onOk) {
        TextField field = new TextField(initialValue == null ? "" : initialValue);
        field.setPromptText(placeholder);
        field.getStyleClass().add("input-field");
        field.setPrefWidth(Double.MAX_VALUE);
        field.setMaxWidth(Double.MAX_VALUE);
        // 带初始值时全选，便于直接覆盖输入
        field.selectAll();
        VBox.setMargin(field, new Insets(0));

        HBox actions = new HBox(10);
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        fillWidth(cancelBtn);
        cancelBtn.setOnAction(e -> closeModal());
        Button okBtn = new Button("确定");
        okBtn.getStyleClass().add("modal-btn-ok");
        fillWidth(okBtn);
        okBtn.setDisable(field.getText().trim().isEmpty());
        field.textProperty().addListener((obs, o, n) -> okBtn.setDisable(n == null || n.trim().isEmpty()));
        okBtn.setOnAction(e -> {
            closeModal();
            onOk.accept(field.getText().trim());
        });
        field.setOnAction(e -> {
            if (!okBtn.isDisabled()) okBtn.fire();
        });
        actions.getChildren().addAll(cancelBtn, okBtn);

        dialog("memo", "info", title, prompt, field, actions, 380, true);
    }

    /** 显示项目自身的 MIT 许可全文（版权页「项目许可」卡片入口） */
    public void mitLicenseDialog() {
        String text = """
                MIT License

                Copyright (c) 2026 Starlight Launcher Contributors

                Permission is hereby granted, free of charge, to any person obtaining a copy
                of this software and associated documentation files (the "Software"), to deal
                in the Software without restriction, including without limitation the rights
                to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
                copies of the Software, and to permit persons to whom the Software is
                furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all
                copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
                FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
                AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
                LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
                OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
                SOFTWARE.

                —— 第三方组件许可见「开源许可」列表；
                Starlight Launcher 按「AS IS」提供，与 Mojang Studios / Microsoft 无隶属关系，
                Minecraft 是 Mojang Studios 的商标。""";
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefSize(520, 340);
        area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        area.positionCaret(0);
        VBox body = new VBox(10, area);
        body.setPadding(new Insets(8, 4, 0, 4));
        VBox.setVgrow(area, Priority.ALWAYS);
        modal("项目许可", body, 560, 420);
    }

    /** 显示第三方开源许可列表（读取 /assets/licenses.json） */
    public void licenseDialog() {
        List<String> lines = new ArrayList<>();
        try (java.io.InputStream is = UiService.class.getResourceAsStream("/assets/licenses.json")) {
            if (is != null) {
                JsonArray arr = JsonParser.parseReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String name = o.has("name") ? o.get("name").getAsString() : "-";
                    String ver = o.has("version") ? o.get("version").getAsString() : "-";
                    String lic = o.has("license") ? o.get("license").getAsString() : "-";
                    String url = o.has("url") && !o.get("url").isJsonNull() ? o.get("url").getAsString() : "";
                    lines.add(name + " " + ver + "\n   许可证: " + lic + (url.isEmpty() ? "" : "\n   " + url));
                }
            } else {
                lines.add("未找到 licenses.json");
            }
        } catch (Exception e) {
            lines.add("加载许可证信息失败: " + e.getMessage());
        }

        ListView<String> list = new ListView<>();
        list.getItems().setAll(lines);
        list.setPrefSize(480, 320);
        list.setStyle("-fx-background-radius: 8;");
        VBox.setVgrow(list, Priority.ALWAYS);
        Label hint = new Label("Starlight Launcher 使用的第三方组件");
        hint.getStyleClass().add("modal-hint");
        VBox body = new VBox(10, hint, list);
        body.setPadding(new Insets(8, 4, 0, 4));
        modal("开源许可", body, 500, 380);
    }
}


package com.example.starlight.newui.ai;

import com.example.starlight.config.AiConfig;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 「选择模型」伪弹窗：挂在启动器内置遮罩（{@link com.example.starlight.newui.ui.UiService#modal}）上的模型选择器，
 * 版式与「配置下载路径」弹窗同源（复用 install-target-* 样式，深浅主题自动适配）。
 *
 * <p>两条路都通：
 * <ul>
 *   <li><b>在线获取</b>：{@link AiModelCatalog} 直接问服务商的 {@code /models}，列出账号可用的全部模型；</li>
 *   <li><b>内置兜底</b>：没网 / 接口不兼容 / 还没填 Key 时，显示 {@link AiProviders} 里登记的常见模型，
 *       照样能选；实在没有想要的，关掉弹窗在输入框里手打模型名即可。</li>
 * </ul>
 *
 * <p>打开时会先用内置模型把列表铺出来（立刻可用），同时后台拉一次在线列表，
 * 拉到就整体替换，拉不到就保留内置列表并在状态行说明原因。
 */
public final class AiModelPickerDialog {

    /** 弹窗宽度（与设计稿 .modal 同宽），高度按内容自适应 */
    private static final double MODAL_WIDTH = 420;
    /** 模型列表固定高度：约 3 行（启动器默认窗口只有 480 高，弹窗整体不能太长） */
    private static final double LIST_HEIGHT = 180;

    /** 一行模型：单选按钮、整行节点、模型名 */
    private record Row(RadioButton radio, VBox node, String model) {
    }

    private final LauncherContext host;
    private final String baseUrl;
    private final String apiKey;
    private final String current;
    private final Consumer<String> onPick;

    private final ToggleGroup group = new ToggleGroup();
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> filterableRows = new ArrayList<>();

    private VBox listBox;
    private ScrollPane listScroll;
    private Label statusLabel;
    private Label emptyNote;
    private Button fetchBtn;
    private Button confirmBtn;
    private TextField searchField;
    /** 是否正在拉取（按钮防重复点） */
    private boolean fetching;

    private AiModelPickerDialog(LauncherContext host, String baseUrl, String apiKey,
                                String current, Consumer<String> onPick) {
        this.host = host;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.current = current == null ? "" : current.trim();
        this.onPick = onPick;
    }

    /**
     * 弹出模型选择器。
     *
     * @param baseUrl 当前 API 地址（为空时只显示内置模型，不联网）
     * @param apiKey  当前 API Key（可为空）
     * @param current 当前已选模型（在列表里预选中）
     * @param onPick  选中后的回调（参数为模型名），取消时不回调
     */
    public static void show(LauncherContext host, String baseUrl, String apiKey,
                            String current, Consumer<String> onPick) {
        if (host == null) return;
        new AiModelPickerDialog(host, baseUrl, apiKey, current, onPick).open();
    }

    // ==================== 构建 ====================

    private void open() {
        VBox body = new VBox(8);
        body.setAlignment(Pos.TOP_CENTER);
        body.getChildren().add(dialogIcon());
        body.getChildren().add(centered("选择模型", "modal-card-title"));
        body.getChildren().add(centered("用于分析崩溃日志的模型，可联网获取，也可直接用内置模型\n" + addressHint(),
                "modal-hint"));
        body.getChildren().add(searchRow());

        listBox = new VBox(6);
        emptyNote = centered("没有匹配的模型", "install-target-empty");
        emptyNote.setVisible(false);
        emptyNote.setManaged(false);
        listBox.getChildren().add(emptyNote);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("install-target-scroll");
        scroll.setPrefViewportHeight(LIST_HEIGHT);
        scroll.setMinHeight(LIST_HEIGHT + 4);
        scroll.setMaxHeight(LIST_HEIGHT + 4);
        // 固定高度的内嵌列表：补挂惯性滚动（不动尺寸约束）
        PageKit.attachInertia(host, scroll);
        listScroll = scroll;
        body.getChildren().add(scroll);

        body.getChildren().add(fetchButton());

        statusLabel = centered("", "modal-hint");
        // 换行后高度不被压成一行（被压扁时 Label 会直接省略号截断）
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        statusLabel.setPadding(new Insets(0, 6, 0, 6));
        body.getChildren().add(statusLabel);

        body.getChildren().add(actions());

        // 关闭按钮容易和「取消」重复，这里按设计稿只留底栏按钮
        host.ui().modal("", body, MODAL_WIDTH, -1, false);

        // 先用内置模型铺列表（立刻可点），再后台拉一次在线列表
        renderModels(AiProviders.presetModels(baseUrl), true);
        if (baseUrl.isEmpty()) {
            setStatus("未填写 API 地址，当前是内置模型；也可关掉弹窗直接手打模型名", false);
        } else {
            fetchAsync();
        }
        if (searchField != null) searchField.requestFocus();
    }

    /** 顶部图标块：优先用当前地址对应的服务商 logo（自动识别），识别不出用通用图标 */
    private Node dialogIcon() {
        StackPane box = new StackPane();
        box.getStyleClass().addAll("modal-icon", "info");
        AiProviders.Provider p = AiProviders.matchByBaseUrl(baseUrl);
        Image logo = p == null ? null : AiProviders.logo(p.logo());
        if (logo != null) {
            ImageView iv = new ImageView(logo);
            iv.setFitWidth(30);
            iv.setFitHeight(30);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            box.getChildren().add(iv);
        } else {
            box.getChildren().add(AppIcons.icon("sparkles", 24, Color.web("#3b82f6")));
        }
        VBox.setMargin(box, new Insets(0, 0, 10, 0));
        return box;
    }

    private String addressHint() {
        AiProviders.Provider p = AiProviders.matchByBaseUrl(baseUrl);
        if (baseUrl.isEmpty()) return "当前未填写 API 地址";
        return "当前地址：" + baseUrl + (p == null ? "" : "（" + p.name() + "）");
    }

    private static Label centered(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.setWrapText(true);
        return l;
    }

    /** 搜索框：放大镜 + 透明输入框 + 清除按钮（与下载路径弹窗同一套样式） */
    private Node searchRow() {
        searchField = new TextField();
        searchField.setPromptText("搜索模型");
        searchField.getStyleClass().add("install-target-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((o, a, b) -> filter(b));

        Button clear = new Button();
        clear.getStyleClass().add("install-target-clear");
        AppIcons.apply(clear, "close", 11, null);
        clear.setVisible(false);
        clear.setManaged(false);
        clear.setOnAction(e -> {
            searchField.clear();
            searchField.requestFocus();
        });
        searchField.textProperty().addListener((o, a, b) -> {
            boolean on = b != null && !b.isEmpty();
            clear.setVisible(on);
            clear.setManaged(on);
        });

        HBox box = new HBox(7, AppIcons.icon("search", 13, Color.web("#9ca3af")), searchField, clear);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("install-target-search");
        return box;
    }

    /** 通栏虚线按钮：联网获取模型列表 */
    private Button fetchButton() {
        fetchBtn = new Button("从服务商获取模型列表");
        fetchBtn.getStyleClass().add("install-target-browse");
        AppIcons.apply(fetchBtn, "refresh", 14, null);
        fetchBtn.setMaxWidth(Double.MAX_VALUE);
        fetchBtn.setOnAction(e -> fetchAsync());
        return fetchBtn;
    }

    private HBox actions() {
        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        cancelBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancelBtn, Priority.ALWAYS);
        cancelBtn.setOnAction(e -> host.ui().closeModal());

        confirmBtn = new Button("使用此模型");
        confirmBtn.getStyleClass().add("modal-btn-ok");
        confirmBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(confirmBtn, Priority.ALWAYS);
        confirmBtn.setOnAction(e -> confirm());

        HBox box = new HBox(10, cancelBtn, confirmBtn);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    // ==================== 列表 ====================

    /** 重新铺一遍模型列表；preset=true 表示这批是内置模型（会打「内置」徽章） */
    private void renderModels(List<String> models, boolean preset) {
        rows.clear();
        filterableRows.clear();
        // 保留 emptyNote（下标 0），其余行全部换掉
        if (listBox.getChildren().size() > 1) {
            listBox.getChildren().remove(1, listBox.getChildren().size());
        }

        Row preferred = null;
        for (String model : models) {
            Row row = createRow(model, preset);
            listBox.getChildren().add(row.node());
            if (!current.isEmpty() && current.equalsIgnoreCase(model)) preferred = row;
        }
        if (preferred != null) {
            preferred.radio().setSelected(true);
        } else if (!rows.isEmpty()) {
            // 当前模型不在列表里（换了服务商、或在线列表里没有它）：默认选中第一个，
            // 免得用户面对一个「点了没反应」的确认按钮
            rows.get(0).radio().setSelected(true);
        }
        // 让选中的那一行尽量滚进可视区（在线列表动辄上百个模型，否则看不到自己选的是哪个）
        int selectedIdx = preferred != null ? rows.indexOf(preferred) : 0;
        if (listScroll != null && selectedIdx > 0 && rows.size() > 1) {
            listScroll.setVvalue(PageKit.clamp((double) selectedIdx / (rows.size() - 1), 0, 1));
        }
        filter(searchField == null ? "" : searchField.getText());
        refreshSelection();
        updateConfirmState();
    }

    private Row createRow(String model, boolean preset) {
        RadioButton radio = new RadioButton();
        radio.setToggleGroup(group);
        radio.getStyleClass().add("install-target-radio");
        radio.setOnAction(e -> {
            refreshSelection();
            updateConfirmState();
        });

        StackPane iconBox = new StackPane(AppIcons.icon("sparkles", 15, Color.web("#3b82f6")));
        iconBox.getStyleClass().addAll("install-target-icon", "global");

        Label nameLabel = new Label(model);
        nameLabel.getStyleClass().add("install-target-name");
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label subLabel = new Label(preset ? subTitleFor(model) : "在线获取");
        subLabel.getStyleClass().add("install-target-sub");
        subLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox info = new VBox(2, nameLabel, subLabel);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox head = new HBox(10, radio, iconBox, info);
        head.setAlignment(Pos.CENTER_LEFT);
        if (preset) head.getChildren().add(badge("内置", "shared"));

        VBox rowNode = new VBox(head);
        rowNode.getStyleClass().add("install-target-row");
        rowNode.setCursor(Cursor.HAND);
        rowNode.setOnMouseClicked(e -> {
            radio.setSelected(true);
            refreshSelection();
            updateConfirmState();
            if (e.getClickCount() >= 2) confirm();   // 双击直接采用
        });
        Tooltip.install(rowNode, new Tooltip(model));

        Row row = new Row(radio, rowNode, model);
        rows.add(row);
        filterableRows.add(row);
        return row;
    }

    /** 内置模型所在的服务商名（用于行副标题）；匹配不到就显示「内置模型」 */
    private static String subTitleFor(String model) {
        for (AiProviders.Provider p : AiProviders.ALL) {
            for (String m : p.models()) {
                if (m.equalsIgnoreCase(model)) return p.name();
            }
        }
        return "内置模型";
    }

    private static Label badge(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().add("install-target-badge");
        if (kind != null) l.getStyleClass().add(kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    /** 搜索过滤（名字匹配；全被过滤掉时给个提示） */
    private void filter(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Row r : filterableRows) {
            boolean hit = q.isEmpty() || r.model().toLowerCase(Locale.ROOT).contains(q);
            r.node().setVisible(hit);
            r.node().setManaged(hit);
            if (hit) shown++;
        }
        boolean none = shown == 0 && !rows.isEmpty();
        if (emptyNote != null) {
            emptyNote.setVisible(none);
            emptyNote.setManaged(none);
        }
    }

    /** 同步选中高亮 */
    private void refreshSelection() {
        for (Row r : rows) {
            boolean on = r.radio().isSelected();
            if (on) {
                if (!r.node().getStyleClass().contains("selected")) r.node().getStyleClass().add("selected");
            } else {
                r.node().getStyleClass().remove("selected");
            }
        }
    }

    private void updateConfirmState() {
        if (confirmBtn != null) confirmBtn.setDisable(selectedRow() == null);
    }

    private Row selectedRow() {
        for (Row r : rows) {
            if (r.radio().isSelected()) return r;
        }
        return null;
    }

    private void confirm() {
        Row sel = selectedRow();
        if (sel == null) {
            setStatus("先在上面的列表里点一个模型", false);
            return;
        }
        host.ui().closeModal();
        onPick.accept(sel.model());
    }

    // ==================== 在线获取 ====================

    private void fetchAsync() {
        if (fetching) return;
        if (baseUrl.isEmpty()) {
            setStatus("未填写 API 地址，当前是内置模型；也可关掉弹窗直接手打模型名", false);
            return;
        }
        fetching = true;
        if (fetchBtn != null) fetchBtn.setDisable(true);
        setStatus("正在获取模型列表…", false);

        String base = AiConfig.normalizeBaseUrl(baseUrl);
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                List<String> models = AiModelCatalog.fetch(base, apiKey);
                Platform.runLater(() -> {
                    fetching = false;
                    if (fetchBtn != null) fetchBtn.setDisable(false);
                    renderModels(models, false);
                    setStatus("已获取 " + models.size() + " 个模型", true);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    fetching = false;
                    if (fetchBtn != null) fetchBtn.setDisable(false);
                    setStatus("获取失败：" + messageOf(ex) + "（列表里是内置模型，也可手动输入）", false);
                });
            }
        });
    }

    private static String messageOf(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) msg = t.getClass().getSimpleName();
        return msg.replaceAll("\\s+", " ").trim();
    }

    private void setStatus(String text, boolean ok) {
        if (statusLabel == null) return;
        statusLabel.setText(text);
        // 失败用暖色、成功/普通用灰，不改样式类（避免污染 .modal-hint 的主题色）
        statusLabel.setStyle(ok
                ? "-fx-text-fill: #16a34a;"
                : (text != null && text.startsWith("获取失败") ? "-fx-text-fill: #d97706;" : ""));
    }
}

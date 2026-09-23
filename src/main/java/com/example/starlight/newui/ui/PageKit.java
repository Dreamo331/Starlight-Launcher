package com.example.starlight.newui.ui;

import com.example.starlight.newui.LauncherContext;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;

/**
 * 页面与设置页脚手架：页面外壳、设置卡片、开关、滚动面板与一批小工具。
 *
 * <p>从 LauncherView 抽离（方法体逐字搬运，仅把「读配置 / 切页 / Toast」改为从
 * {@link LauncherContext} 获取）。样式类名（content-title / back-btn / settings-card /
 * settings-card-info / settings-card-title / settings-card-desc / toggle-switch /
 * btn-primary / select-field）与内联样式均未改动。
 */
public final class PageKit {

    private PageKit() {
    }

    /** 设置页导航项：侧边栏隐藏时用于下拉导航（顺序与 buildSidebar 一致） */
    public static final String[][] SETTINGS_NAV_ITEMS = {
            {"sidebarAccount", "Account"}, {"sidebarAbout", "关于"}, {"sidebarLicense", "版权"},
            {"sidebarGameAccount", "游戏账户档案"}, {"sidebarMod", "模组"}, {"sidebarJvm", "Java虚拟机与内存"},
            {"sidebarGameDir", "游戏目录"}, {"sidebarVersionSettings", "版本独立设置"}, {"sidebarAdvanced", "高级设置"},
            {"sidebarTheme", "主题与背景"}, {"sidebarMainUi", "主界面"},
            {"sidebarLanguage", "语言"}, {"sidebarAccessibility", "辅助功能"},
            {"sidebarColorBlind", "色盲辅助"},
            {"sidebarDownload", "下载"}, {"sidebarProxy", "代理"},
            {"sidebarMultiplayer", "联机设置"},
            {"sidebarFeedback", "服务与反馈"},
            {"sidebarSponsor", "赞助我们"}, {"sidebarDev", "开发者选项"}
    };

    public static VBox settingsPage(LauncherContext ctx, String title) {
        return settingsPage(ctx, title, null);
    }

    /**
     * 创建设置样式页面；backTarget 非空时在标题左侧添加返回按钮（用于从首页进入的无侧边栏子页面）
     */
    public static VBox settingsPage(LauncherContext ctx, String title, String backTarget) {
        VBox root = new VBox(16);
        root.setPadding(new Insets(0, 4, 0, 0));

        // 标题行：子页面提供「← 返回」回到来源页
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        if (backTarget != null && !backTarget.isEmpty()) {
            Button backBtn = AppIcons.button("back", "返回");
            backBtn.getStyleClass().add("back-btn");
            backBtn.setOnAction(e -> ctx.switchToPage(backTarget));
            header.getChildren().add(backBtn);
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("content-title");
        header.getChildren().add(titleLabel);
        root.getChildren().add(header);
        // 侧边栏隐藏时提供下拉导航，避免设置页内无法切换页面
        if ("false".equalsIgnoreCase(ctx.config().getOrDefault("ShowSidebar", "true"))) {
            ComboBox<String> nav = new ComboBox<>();
            nav.getStyleClass().add("select-field");
            nav.setMaxWidth(240);
            for (String[] item : SETTINGS_NAV_ITEMS) nav.getItems().add(item[1]);
            for (int i = 0; i < SETTINGS_NAV_ITEMS.length; i++) {
                if (SETTINGS_NAV_ITEMS[i][1].equals(title)) {
                    nav.setValue(SETTINGS_NAV_ITEMS[i][1]);
                    break;
                }
            }
            nav.setOnAction(e -> {
                if (nav.getValue() == null) return;
                for (String[] item : SETTINGS_NAV_ITEMS) {
                    if (item[1].equals(nav.getValue())) {
                        ctx.switchToSettings(item[0]);
                        break;
                    }
                }
            });
            root.getChildren().add(nav);
        }
        return root;
    }


    public static HBox settingsCard(String title, String desc, Node control) {
        HBox card = new HBox(20);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("settings-card");
        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        // 说明区可以被压窄（desc 已开自动换行），把剩余宽度让给右侧控件
        info.setMinWidth(0);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        info.getChildren().add(titleLabel);
        if (desc != null && !desc.isEmpty()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("settings-card-desc");
            // 允许换行：长描述在窄空间自动换行收缩，避免撑大卡片/页面宽度
            descLabel.setWrapText(true);
            info.getChildren().add(descLabel);
        }
        HBox.setHgrow(info, Priority.ALWAYS);
        if (control != null) {
            // 右侧控件里的按钮/复选框锁定固有宽度，宽度不够时改为压缩左侧说明，
            // 避免按钮文字被压成「测试...」「更新...」甚至只剩「...」
            lockButtonWidths(control);
            card.getChildren().addAll(info, control);
        } else {
            card.getChildren().add(info);
        }
        return card;
    }

    /**
     * 竖直排布的设置卡片：标题/说明在上，控件在下并通栏铺满。
     *
     * <p>用于多行输入（TextArea）等需要整行宽度的控件，避免与左侧说明挤在同一行。
     * 样式类与 {@link #settingsCard} 一致，仅额外附加 settings-card-stacked 把对齐改为顶对齐。
     */
    public static VBox settingsCardStacked(String title, String desc, Node control) {
        VBox card = new VBox(10);
        card.getStyleClass().add("settings-card-stacked");
        card.getStyleClass().add("settings-card");
        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        info.setMinWidth(0);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        info.getChildren().add(titleLabel);
        if (desc != null && !desc.isEmpty()) {
            Label descLabel = new Label(desc);
            descLabel.getStyleClass().add("settings-card-desc");
            descLabel.setWrapText(true);
            info.getChildren().add(descLabel);
        }
        card.getChildren().add(info);
        if (control != null) {
            lockButtonWidths(control);
            // 下方控件通栏：占满卡片宽度，窄窗口下也只压缩自身而不撑大卡片
            if (control instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
                region.setMinWidth(0);
            }
            card.getChildren().add(control);
        }
        return card;
    }

    /** 递归把容器里的按钮/复选框锁成固有宽度，防止窄卡片里被压成省略号 */
    public static void lockButtonWidths(Node node) {
        if (node instanceof Button b) {
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setMaxWidth(Region.USE_PREF_SIZE);
        } else if (node instanceof CheckBox c) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        } else if (node instanceof javafx.scene.control.RadioButton r) {
            r.setMinWidth(Region.USE_PREF_SIZE);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) lockButtonWidths(child);
        }
    }

    public static Button primaryButton(LauncherContext ctx, String text, String targetPage) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-primary");
        btn.setOnAction(e -> {
            if (targetPage.startsWith("sidebar")) {
                ctx.switchToSettings(targetPage);
            } else {
                ctx.ui().toast(targetPage);
            }
        });
        return btn;
    }


    /**
     * 创建统一样式的开关（CheckBox 外观改成拨动开关）。
     *
     * <p>滑块尺寸与位移由 {@link ToggleSwitchAnimator} 在皮肤就绪后用代码锁定并驱动
     * （回弹位移、按压挤压、✓/✕ 交叉淡入淡出、键盘焦点环），
     * 所以 CSS 里 <b>不能</b> 再给 {@code .mark} 写 {@code -fx-pref-width} / {@code -fx-translate-x}：
     * 作者样式表的优先级高于代码，下次样式重算会把动画中的值打回原值。轨道尺寸仍写在 CSS 里。
     */
    public static CheckBox toggle(boolean value) {
        CheckBox toggle = new CheckBox();
        toggle.setSelected(value);
        toggle.getStyleClass().add("toggle-switch");
        ToggleSwitchAnimator.install(toggle);
        return toggle;
    }


    /**
     * 创建统一的滚动面板，并挂接惯性滚动（规格：.codeartsdoer/specs/inertia_scrolling/spec.md）。
     *
     * <p>页面级滚动区经此方法统一挂接（页面外壳 + 各自带滚动区的页面）：vbarPolicy NEVER + inertia-pane 样式类
     * （滚动条隐藏双保险）+ {@link InertiaScrollSupport#install} 惯性引擎。
     * 滚轮过滤器按惯性开/关二分：开启时全部滚轮输入由惯性模型驱动（含默认速度 35）；
     * 关闭时逐字保留既有回退行为（35 短路原生滚动 / 非 35 瞬时跳转），滚动能力不丢失。
     * 竖直滚动速度由 ScrollSpeed 倍率控制，惯性顺滑度由 SmoothScrollEase 档位控制。
     */
    public static void configureScrollPane(LauncherContext ctx, ScrollPane sp) {
        sp.setFitToWidth(true);
        sp.setFitToHeight(false);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color: transparent;");
        sp.getStyleClass().add(InertiaScrollSupport.PANEL_STYLE_CLASS);
        InertiaScrollSupport.install(ctx, sp);
        VBox.setVgrow(sp, Priority.ALWAYS);
        // 不参与父容器最小宽度计算：内容再宽也只按可用宽度压缩（fitToWidth 适配），避免撑大窗口/挤压侧边栏
        sp.setMinWidth(0);
        sp.setMinHeight(0);
        sp.setMaxWidth(Double.MAX_VALUE);
        sp.setMaxHeight(Double.MAX_VALUE);
        attachWheelFilter(ctx, sp);
    }

    /**
     * 给自行管理尺寸/样式的内部滚动区补挂惯性滚动（引擎 + 滚轮过滤器 + 滚动条隐藏样式类），
     * 不改动面板的尺寸与布局约束 —— 用于固定高度的嵌入列表（弹窗、任务卡片）与定宽侧栏。
     */
    public static void attachInertia(LauncherContext ctx, ScrollPane sp) {
        if (ctx == null || sp == null) return;
        sp.getStyleClass().add(InertiaScrollSupport.PANEL_STYLE_CLASS);
        InertiaScrollSupport.install(ctx, sp);
        attachWheelFilter(ctx, sp);
    }

    /**
     * 拦截鼠标滚轮事件：惯性开启时全部由惯性模型驱动（含默认速度 35）；
     * 惯性关闭时按配置缩放滚动速度（回退路径与改造前行为一致）。
     * 滚轮落点位于自带滚动的控件（ListView/TextArea/内层 ScrollPane 等）内时不拦截，
     * 交给控件原生滚动 —— 覆盖下载中心「游戏版本」页的版本列表、任务卡片内嵌文件列表等嵌套场景。
     */
    private static void attachWheelFilter(LauncherContext ctx, ScrollPane sp) {
        sp.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            int speed = 35;
            if (ctx.config() != null) {
                speed = parseIntSafe(ctx.config().getOrDefault("ScrollSpeed", "35"), 35);
            }
            if (InertiaScrollSupport.isEnabled(ctx)) {
                if (insideSelfScrollingControl(event.getTarget(), sp)) return;
                // 惯性开启：按「可滚区间」归一（vvalue 1.0 对应的像素距离），每格滚轮像素位移恒定，
                // 短页面与长页面速度一致；内容不足一屏时不拦截不吞事件，保持对外层透明
                double range = scrollRange(sp);
                if (range <= 0) return;
                event.consume();
                double factor = speed / 35.0;
                InertiaScrollSupport.wheelScroll(sp, event.getDeltaY(), range, factor);
                return;
            }
            if (speed == 35) return; // 默认速度，不拦截
            if (insideSelfScrollingControl(event.getTarget(), sp)) return;

            double contentH = sp.getContent() != null
                    ? sp.getContent().getBoundsInLocal().getHeight() : 0;
            double viewH = sp.getViewportBounds().getHeight();
            double range = contentH - viewH;
            if (range <= 0) return; // 内容不足一屏：不拦截，避免悬停时吞掉外层滚轮

            event.consume();
            double factor = speed / 35.0;
            // deltaY 通常每格 ±40，折算成 notch 数，再乘以基础步长和速度系数
            double notches = event.getDeltaY() / 40.0;
            double baseStep = 0.2; // 每 notch 滚动的 vvalue 占比（默认20倍速时原生行为等效约0.2）
            double shift = notches * baseStep * factor;
            sp.setVvalue(clamp(sp.getVvalue() - shift, 0, 1));
        });
    }

    /** 沿滚轮目标向上找（以 sp 为边界）：途经自带滚动的控件时返回 true（滚轮留给它原生处理）。
     * 内层 ScrollPane 只有真正可滚（内容高于视口）才算自带滚动，短内容嵌套面板保持对外层透明；
     * ListView/TableView/TreeView/TextArea 无法低成本判断可滚性，一律交原生处理（与挂接前行为一致）。 */
    private static boolean insideSelfScrollingControl(Object target, ScrollPane sp) {
        if (!(target instanceof Node n)) return false;
        Node cur = n;
        while (cur != null && cur != sp) {
            if (cur instanceof javafx.scene.control.ListView<?>
                    || cur instanceof javafx.scene.control.TableView<?>
                    || cur instanceof javafx.scene.control.TreeView<?>
                    || cur instanceof javafx.scene.control.TextArea
                    || cur instanceof javafx.scene.control.ScrollBar) {
                return true;
            }
            if (cur instanceof ScrollPane inner && scrollRange(inner) > 0) {
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    /**
     * 统一的「加载失败」错误状态卡片（仅内容无法正常加载时显示，替代裸错误码文案）：
     * bug 图标 + 友好提示 + 一行灰色小字错误详情 +「重新加载」「复制错误代码」按钮。
     *
     * <p>纯 UI 方法，不感知业务：重载动作由调用方注入（列表重新 search、详情重新 load…），
     * 传 null 时不显示按钮行（如「帖子不存在」这类没有重载意义的场景）；复制的是
     * detail（原始错误信息/错误码），复制后按钮短暂变「已复制」。
     */
    public static VBox buildErrorState(String title, String detail, Runnable onReload) {
        VBox box = new VBox(10);
        box.getStyleClass().add("error-state");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(26, 16, 22, 16));

        Region bugIcon = AppIcons.icon("bug", 30, javafx.scene.paint.Color.web("#ef4444"));
        StackPane iconWrap = new StackPane(bugIcon);
        iconWrap.getStyleClass().add("error-state-icon");
        box.getChildren().add(iconWrap);

        Label titleLabel = new Label(title == null || title.isBlank() ? "加载失败" : title);
        titleLabel.getStyleClass().add("error-state-title");
        titleLabel.setWrapText(true);
        titleLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        box.getChildren().add(titleLabel);

        if (detail != null && !detail.isBlank()) {
            Label detailLabel = new Label(detail);
            detailLabel.getStyleClass().add("error-state-detail");
            detailLabel.setWrapText(true);
            detailLabel.setMaxWidth(360);
            detailLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            box.getChildren().add(detailLabel);
        }

        if (onReload != null) {
            HBox actions = new HBox(10);
            actions.setAlignment(Pos.CENTER);
            Button reloadBtn = new Button("重新加载");
            reloadBtn.getStyleClass().addAll("btn-primary", "error-state-btn");
            reloadBtn.setGraphic(AppIcons.icon("refresh", 13, null));
            reloadBtn.setOnAction(e -> onReload.run());
            Button copyBtn = new Button("复制错误代码");
            copyBtn.getStyleClass().addAll("error-state-btn", "error-state-btn-secondary");
            copyBtn.setGraphic(AppIcons.icon("copy", 13, null));
            copyBtn.setOnAction(e -> {
                String text = (title == null ? "" : title) + (detail == null ? "" : "\n" + detail);
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                // 复制反馈：按钮文字短暂切到「已复制」再还原
                copyBtn.setText("已复制");
                PauseTransition restore = new PauseTransition(Duration.millis(1200));
                restore.setOnFinished(ev -> copyBtn.setText("复制错误代码"));
                restore.play();
            });
            actions.getChildren().addAll(reloadBtn, copyBtn);
            box.getChildren().add(actions);
        }
        return box;
    }

    /** 面板竖直可滚余量：内容高度 − 视口高度，≤0 表示没有可滚内容 */
    private static double scrollRange(ScrollPane p) {
        double contentH = p.getContent() != null
                ? p.getContent().getBoundsInLocal().getHeight() : 0;
        return contentH - p.getViewportBounds().getHeight();
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }


    /** 目录操作按钮：固定为自身首选宽度，窗口变窄时文字不会被压成省略号 */
    public static Button actionButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-primary");
        btn.setMinWidth(Region.USE_PREF_SIZE);
        return btn;
    }

    /** 允许 createSettingsCard 的标题换行：窗口较窄时标题换行显示，而不是被压成省略号 */
    public static void allowCardTitleWrap(Node card) {
        if (!(card instanceof HBox box) || box.getChildren().isEmpty()) return;
        if (!(box.getChildren().get(0) instanceof VBox info)) return;
        for (Node node : info.getChildren()) {
            if (node instanceof Label label && label.getStyleClass().contains("settings-card-title")) {
                label.setWrapText(true);
                return;
            }
        }
    }


    /** 读一个布尔型配置项 */
    public static boolean boolConfig(LauncherContext ctx, String key, boolean def) {
        return Boolean.parseBoolean(ctx.config().getOrDefault(key, String.valueOf(def)));
    }

    /** 灰色小字提示 */
    public static Label hintLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
        return l;
    }


    /**

    /** 设置页右侧控件列宽度：固定后各卡片纵向对齐，避免控件被压缩导致按钮变窄、文本被截断 */
    public static final double SETTINGS_CONTROL_WIDTH = 380;

    /** 设置卡片里的操作按钮：最小宽度取实际所需宽度，防止被同行长文本挤压变形（出现「…」） */
    public static Button cardActionButton(String text) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-primary");
        btn.setMinWidth(Region.USE_PREF_SIZE);
        return btn;
    }


// ===== 工具条卡片 =====

/**
 * 工具条卡片：把「搜索框 + 筛选控件」这样的整行控件装进同一张卡片里，避免它们直接铺在页面背景上。
 *
 * <p>纵向排布（行距 10，内边距见 CSS 的 {@code .toolbar-card}），主题色与 settings-card 一致
 * （深色 / 高对比度自动跟随）；页面标题仍留在卡片外，与其它页面保持同一套版式。
 *
 * @param rows 依次放进卡片的行（null 会被忽略）
 */
public static VBox toolbarCard(Node... rows) {
    VBox card = new VBox();
    card.getStyleClass().add("toolbar-card");
    for (Node row : rows) {
        if (row != null) card.getChildren().add(row);
    }
    return card;
}


// ===== 长列表：搜索框 + 每批 20 条 + 底部「向下加载更多」 =====

/** 列表每批渲染条数：一次只铺这么多卡片，其余由底部按钮按需追加，避免一次渲染成百上千个节点卡窗口 */
public static final int LIST_PAGE_SIZE = 20;

/** 「向下加载更多」所在行的样式类（重新渲染时靠它把这行摘掉） */
private static final String MORE_ROW_STYLE = "paged-more-row";

/**
 * 统一的搜索框：输入后延迟 250ms 触发一次 {@code onSearch}（回车立即触发）。
 *
 * <p>防抖是为了避免每敲一个字就把整页卡片重铺一遍；截图类页面重铺还会重新解码缩略图。
 *
 * @param initial  初始关键词（刷新页面后保留上次的搜索内容）
 * @param onSearch 关键词回调（已 trim；空串表示不过滤）
 */
public static TextField searchField(String prompt, String initial,
                                    java.util.function.Consumer<String> onSearch) {
    TextField field = new TextField(initial == null ? "" : initial);
    field.setPromptText(prompt);
    field.getStyleClass().add("input-field");
    field.setMaxWidth(Double.MAX_VALUE);
    PauseTransition debounce = new PauseTransition(Duration.millis(250));
    debounce.setOnFinished(e -> onSearch.accept(field.getText().trim()));
    field.textProperty().addListener((obs, old, now) -> debounce.playFromStart());
    field.setOnAction(e -> {
        debounce.stop();
        onSearch.accept(field.getText().trim());
    });
    return field;
}

/**
 * 清空条目容器并从第一条开始重新铺一页（搜索 / 筛选条件变化后调用）。
 *
 * @param stack    外层容器（「向下加载更多」按钮挂在这里）
 * @param itemHost 条目容器（条目铺在这里；网格布局时是外层容器里的 FlowPane）
 * @param items    过滤后的全部条目
 * @param factory  单条 → 卡片节点
 */
public static <T> void resetBatch(VBox stack, Pane itemHost, List<T> items,
                                  java.util.function.Function<T, Node> factory) {
    removeMoreRow(stack);
    itemHost.getChildren().clear();
    if (items == null || items.isEmpty()) {
        Label none = new Label("没有匹配的内容");
        none.setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
        itemHost.getChildren().add(none);
        return;
    }
    renderBatch(stack, itemHost, items, 0, factory);
}

/**
 * 从第 {@code from} 条起追加一批（{@value #LIST_PAGE_SIZE} 条）到 {@code itemHost}；
 * 还有剩余时在 {@code stack} 末尾补一个「向下加载更多」按钮，点一次再追加一批。
 *
 * @param from 本批起始下标
 */
public static <T> void renderBatch(VBox stack, Pane itemHost, List<T> items, int from,
                                   java.util.function.Function<T, Node> factory) {
    removeMoreRow(stack);
    int to = Math.min(from + LIST_PAGE_SIZE, items.size());
    for (int i = from; i < to; i++) {
        Node node = factory.apply(items.get(i));
        if (node != null) itemHost.getChildren().add(node);
    }
    if (to >= items.size()) return;

    final int next = to;
    Button more = new Button("向下加载更多（已显示 " + to + " / " + items.size() + "）");
    more.getStyleClass().add("btn-primary");
    more.setStyle("-fx-padding: 6 20; -fx-font-size: 12px; -fx-background-radius: 18;");
    more.setMinWidth(Region.USE_PREF_SIZE);
    more.setMaxWidth(Region.USE_PREF_SIZE);
    more.setOnAction(e -> renderBatch(stack, itemHost, items, next, factory));
    HBox row = new HBox(more);
    row.setAlignment(Pos.CENTER);
    row.getStyleClass().add(MORE_ROW_STYLE);
    stack.getChildren().add(row);
}

/** 摘掉上一次留下的「向下加载更多」行（没有则不动） */
private static void removeMoreRow(Pane stack) {
    stack.getChildren().removeIf(n -> n.getStyleClass().contains(MORE_ROW_STYLE));
}


// ===== 工具方法 =====

public static int parseIntSafe(String s, int defaultVal) {
    try { return Integer.parseInt(s.trim()); }
    catch (Exception e) { return defaultVal; }
}
}


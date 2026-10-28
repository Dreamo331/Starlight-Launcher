package com.example.starlight.newui.ui;

import com.example.starlight.newui.LauncherContext;
import com.example.starlight.util.I18N;

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
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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

    // 导航数据源（新 IA · 6 组 15 项；「游戏账户档案」已并入「账户」） //
    //
    // 这里存的是 **i18n 键**，不是译文。数组是 static final、在类初始化时就固定，
    // 若直接放 I18N.get(...) 的返回值，语言会被冻结在类加载那一刻；
    // 想让界面跟随配置切换，必须在每次渲染时用 navLabel() / sectionLabel() 现取。

    /** 分组标题键：与 {@link #SETTINGS_NAV_ITEMS} 的顺序一一对应（重构 §5.1） */
    public static final String[] SETTINGS_NAV_SECTIONS = {
            "navsec.general", "navsec.game", "navsec.personalize", "navsec.network",
            "navsec.online", "navsec.other"
    };

    /** 每个分组包含的导航项数（用于把扁平数组切回分组渲染；总和必须等于 SETTINGS_NAV_ITEMS.length = 15） */
    public static final int[] SETTINGS_NAV_SECTION_SIZES = {2, 4, 3, 2, 1, 3};

    /**
     * 设置页导航项（新 IA 单一数据源，重构 §7 导航数据源约定）：
     * 侧边栏（LauncherView.buildSidebar）与顶部下拉导航都从这里渲染，禁止各写一份。
     *
     * <p>每项是 {@code {稳定 id, i18n 键}}。id 参与路由与 CSS 查找，<b>永不翻译</b>；
     * 显示名一律经 {@link #navLabel(String)} 现取。
     *
     * <p>已移除的旧 id（sidebarLicense / sidebarMod / sidebarLanguage / sidebarColorBlind）
     * 不在清单中——深链重定向见 {@code NavRouter.resolve}，内部页间跳转也必须走
     * {@code LauncherContext.switchToSettings}（其内部同样过 NavRouter）。
     */
    public static final String[][] SETTINGS_NAV_ITEMS = {
            {"sidebarAccount", "nav.account"},
            {"sidebarAbout", "nav.about"},
            {"sidebarJvm", "nav.jvm"},
            {"sidebarGameDir", "nav.gameDir"},
            {"sidebarVersionSettings", "nav.versionSettings"},
            {"sidebarAdvanced", "nav.advanced"},
            {"sidebarTheme", "nav.theme"},
            {"sidebarMainUi", "nav.mainUi"},
            {"sidebarAccessibility", "nav.accessibility"},
            {"sidebarDownload", "nav.download"},
            {"sidebarProxy", "nav.proxy"},
            {"sidebarMultiplayer", "nav.multiplayer"},
            {"sidebarFeedback", "nav.feedback"},
            {"sidebarSponsor", "nav.sponsor"},
            {"sidebarDev", "nav.dev"}
    };

    /** 导航 id → 当前语言下的显示名（查不到回退 id 本身，便于发现漏配） */
    public static String navLabel(String id) {
        if (id == null) return "";
        for (String[] item : SETTINGS_NAV_ITEMS) {
            if (item[0].equals(id)) return I18N.get(item[1]);
        }
        return id;
    }

    /** 分组下标 → 当前语言下的分组标题 */
    public static String sectionLabel(int index) {
        if (index < 0 || index >= SETTINGS_NAV_SECTIONS.length) return "";
        return I18N.get(SETTINGS_NAV_SECTIONS[index]);
    }

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
            Button backBtn = AppIcons.button("back", I18N.get("pagekit.t0001"));
            backBtn.getStyleClass().add("back-btn");
            backBtn.setOnAction(e -> ctx.switchToPage(backTarget));
            header.getChildren().add(backBtn);
        }
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("content-title");
        header.getChildren().add(titleLabel);
        root.getChildren().add(header);
        // 侧边栏隐藏时提供下拉导航，避免设置页内无法切换页面。
        // 数据源与侧边栏同一份（SETTINGS_NAV_ITEMS），分组顺序命名天然一致（重构 §5.3）。
        // 下拉里显示的是译文，但回查用的是「解析后的显示名 == 本页标题」，
        // 不依赖 item[1] 的字面内容——item[1] 现在是 i18n 键，不是文案。
        if ("false".equalsIgnoreCase(ctx.config().getOrDefault("ShowSidebar", "true"))) {
            ComboBox<String> nav = new ComboBox<>();
            nav.getStyleClass().add("select-field");
            nav.setMaxWidth(240);
            for (String[] item : SETTINGS_NAV_ITEMS) nav.getItems().add(navLabel(item[0]));
            for (String[] item : SETTINGS_NAV_ITEMS) {
                if (navLabel(item[0]).equals(title)) {
                    nav.setValue(navLabel(item[0]));
                    break;
                }
            }
            nav.setOnAction(e -> {
                if (nav.getValue() == null) return;
                for (String[] item : SETTINGS_NAV_ITEMS) {
                    if (navLabel(item[0]).equals(nav.getValue())) {
                        ctx.switchToSettings(item[0]);
                        break;
                    }
                }
            });
            root.getChildren().add(nav);
        }
        return root;
    }


    /**
     * 卡片说明文字：单行显示（超出用省略号），完整内容放进 Tooltip。
     *
     * <p>用于「文字过多、原本要换行」的卡片：不再靠换行把卡片撑高，鼠标悬停即可看全文。
     * 动态更新的文字请对已有 Label 调用 {@link #cardTooltip(Label)}（Tooltip 与文本保持同步）。
     */
    public static Label cardDesc(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-card-desc");
        cardTooltip(label);
        return label;
    }

    /**
     * 给卡片里的文字挂上「单行 + 省略号 + Tooltip 全文」。
     *
     * <p>Tooltip 文本与 Label 的文本属性绑定，状态/路径这类会在运行中更新的文字，
     * 悬停时看到的也是最新内容；文字为空时不挂 Tooltip（避免弹出一个空白气泡）。
     * 重复调用不会重复挂。
     */
    public static void cardTooltip(Label label) {
        if (label == null || label.getProperties().containsKey(CARD_TOOLTIP_KEY)) return;
        label.setWrapText(false);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        // minWidth=0 才能被压窄并显示省略号；否则 Label 的最小宽度会反过来把卡片撑宽
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(label.textProperty());
        // 长说明在 Tooltip 里换行显示，避免一条几百像素的横条
        tip.setWrapText(true);
        tip.setMaxWidth(420);
        label.getProperties().put(CARD_TOOLTIP_KEY, tip);
        applyCardTooltip(label, tip);
        label.textProperty().addListener((o, a, b) -> applyCardTooltip(label, tip));
    }

    /** {@link #cardTooltip(Label)} 的标记键：同一个 Label 只挂一次 */
    private static final String CARD_TOOLTIP_KEY = "sl.cardTooltip";

    /** 按当前文本挂上/摘下 Tooltip（文本为空时不显示空白气泡） */
    private static void applyCardTooltip(Label label, Tooltip tip) {
        String text = label.getText();
        boolean hasText = text != null && !text.isBlank();
        if (hasText) {
            if (label.getTooltip() != tip) label.setTooltip(tip);
        } else if (label.getTooltip() == tip) {
            label.setTooltip(null);
        }
    }

    /**
     * 移除设置页外壳的标题行。
     *
     * <p>用于「页面自带标题」的场景（例如账户页把「账户」换成卡片正上方的问候语）：
     * 只摘掉 content-title 那枚文字，返回按钮等其它内容保留；整行被摘空时才连行移除。
     * 传进来的 title 仍要照常提供给 {@link #settingsPage}——侧边栏隐藏时的下拉导航靠标题名回显当前页。
     */
    public static void removePageTitle(VBox page) {
        if (page == null) return;
        for (Node node : new java.util.ArrayList<>(page.getChildren())) {
            if (!(node instanceof HBox header)) continue;
            Node titleNode = null;
            for (Node child : header.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("content-title")) {
                    titleNode = child;
                    break;
                }
            }
            if (titleNode == null) continue;
            header.getChildren().remove(titleNode);
            if (header.getChildren().isEmpty()) page.getChildren().remove(header);
            return;
        }
    }

    public static HBox settingsCard(String title, String desc, Node control) {
        HBox card = new HBox(20);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("settings-card");
        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        // 说明区可以被压窄（超长文字单行省略 + 悬停看全文），把剩余宽度让给右侧控件
        info.setMinWidth(0);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        info.getChildren().add(titleLabel);
        if (desc != null && !desc.isEmpty()) {
            info.getChildren().add(cardDesc(desc));
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
     * 判断右侧控件里的提示文字是否与卡片说明重复。
     *
     * <p>历史写法在卡片里既给了 {@code desc}（卡片说明），又在右侧 VBox 里塞一个
     * {@code hintLabel} 讲同一件事。中文短、挤得下；英文变长后这个 hint 被压成
     * 一列几个字符宽（表现为右侧一条竖排文字）。这类重复提示直接去掉，
     * 说明统一由卡片 desc 承担。
     */
    private static void stripRedundantHints(Node control, String desc) {
        if (desc == null || desc.isBlank()) return;
        if (!(control instanceof VBox v)) return;
        v.getChildren().removeIf(child -> child instanceof Label lb
                && lb.getStyleClass().contains("settings-hint"));
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
            info.getChildren().add(cardDesc(desc));
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

    // 分区折叠卡片（重构 §4.2 / §7 CollapsibleSection） //

    /**
     * 分区折叠卡片：标题行点击展开/收起，右侧箭头指示状态；摘要行在收起时替代内容高度。
     *
     * <p>复用 settings-card / settings-card-stacked 外观（深浅主题自动跟随），
     * 折叠状态同一会话内保持（由调用方持有，重建页面时把 open 再传回来即可）。
     *
     * @param title      卡片标题
     * @param summary    收起时显示的一行摘要（空串则只显示标题行）
     * @param content    展开后的内容（调用后其可见性由本方法管理）
     * @param open       初始是否展开
     * @param stateSink  展开/收起回调（把新状态写回调用方的持久化变量；传 null 忽略）
     */
    public static VBox collapsibleSection(String title, String summary, Node content,
                                          boolean open, java.util.function.Consumer<Boolean> stateSink) {
        VBox card = new VBox(12);
        card.getStyleClass().addAll("settings-card", "settings-card-stacked");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        Label chevron = new Label(open ? "▴" : "▾");
        chevron.getStyleClass().add("settings-card-desc");
        chevron.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(10, titleLabel, chevron);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setCursor(javafx.scene.Cursor.HAND);

        Label summaryLabel;
        if (summary != null && !summary.isEmpty()) {
            summaryLabel = cardDesc(summary);
            summaryLabel.setVisible(!open);
            summaryLabel.setManaged(!open);
        } else {
            summaryLabel = null;
        }

        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.setMinWidth(0);
        }
        content.setVisible(open);
        content.setManaged(open);

        header.setOnMouseClicked(e -> {
            boolean nowOpen = !content.isVisible();
            content.setVisible(nowOpen);
            content.setManaged(nowOpen);
            chevron.setText(nowOpen ? "▴" : "▾");
            if (summaryLabel != null) {
                summaryLabel.setVisible(!nowOpen);
                summaryLabel.setManaged(!nowOpen);
            }
            if (stateSink != null) stateSink.accept(nowOpen);
        });

        card.getChildren().add(header);
        if (summaryLabel != null) card.getChildren().add(summaryLabel);
        card.getChildren().add(content);
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
     * ListView/TableView/TreeView/TextArea 无法低成本判断可滚性，一律交原生处理（与挂接前行为一致）。
     * WebView（模组详情页的描述正文）同理：它内部有独立的滚动文档，滚轮必须交给它，
     * 否则光标停在描述区滚动时会被外层页面滚走，描述区自己纹丝不动。 */
    private static boolean insideSelfScrollingControl(Object target, ScrollPane sp) {
        if (!(target instanceof Node n)) return false;
        Node cur = n;
        while (cur != null && cur != sp) {
            if (cur instanceof javafx.scene.control.ListView<?>
                    || cur instanceof javafx.scene.control.TableView<?>
                    || cur instanceof javafx.scene.control.TreeView<?>
                    || cur instanceof javafx.scene.control.TextArea
                    || cur instanceof javafx.scene.web.WebView
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

        Label titleLabel = new Label(title == null || title.isBlank() ? I18N.get("pagekit.t0002") : title);
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
            Button reloadBtn = new Button(I18N.get("pagekit.t0003"));
            reloadBtn.getStyleClass().addAll("btn-primary", "error-state-btn");
            reloadBtn.setGraphic(AppIcons.icon("refresh", 13, null));
            reloadBtn.setOnAction(e -> onReload.run());
            Button copyBtn = new Button(I18N.get("pagekit.t0004"));
            copyBtn.getStyleClass().addAll("error-state-btn", "error-state-btn-secondary");
            copyBtn.setGraphic(AppIcons.icon("copy", 13, null));
            copyBtn.setOnAction(e -> {
                String text = (title == null ? "" : title) + (detail == null ? "" : "\n" + detail);
                ClipboardContent content = new ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                // 复制反馈：按钮文字短暂切到「已复制」再还原
                copyBtn.setText(I18N.get("pagekit.t0005"));
                PauseTransition restore = new PauseTransition(Duration.millis(1200));
                restore.setOnFinished(ev -> copyBtn.setText(I18N.get("pagekit.t0006")));
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
        /**
     * 卡片内的浅色小字提示。
     *
     * <p>宽度约束为何是「最小 + 最大」而不是只设 minWidth(0)：
     * Label 默认最小宽度等于整段文本宽度，放进 HBox 右侧、空间不足时会被压到
     * 只剩几个字符一列（表现为一条竖排文字）。英文文案比中文长，这个问题会立刻暴露。
     *
     * <p>但单纯设 {@code minWidth(0)} 只是允许它被压得更狠（实测宽度从 115 掉到 47，
     * 变成更高的竖条）。真正的解法是给一个**最小可用宽度**：空间不够时宁可压缩
     * 左侧说明，也不把提示压成竖排。实测 minWidth=180 时提示稳定显示为两行。
     */
    public static Label hintLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinWidth(HINT_MIN_WIDTH);
        l.setMaxWidth(SETTINGS_CONTROL_WIDTH);
        l.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-dim;");
        return l;
    }

    /** 卡片内提示文字的最小可用宽度（低于此宽度会变成竖排一条，实测 180 起可正常换行） */
    private static final double HINT_MIN_WIDTH = 180;


    // 多行输入：右下角三角把手拖拽调节高度 //

    /** 可拖拽多行输入区的最小高度（约 2 行） */
    public static final double RESIZABLE_AREA_MIN_H = 48;

    /** 可拖拽多行输入区的最大高度（约 34 行） */
    public static final double RESIZABLE_AREA_MAX_H = 560;

    /** 右下角「拖拽调高」三角把手的样式类（三角形形状写在 style.css 的 -fx-shape 里） */
    public static final String RESIZE_HANDLE_STYLE_CLASS = "textarea-resize-handle";

    /** 三角把手边长（px） */
    private static final double RESIZE_HANDLE_SIZE = 14;

    /** 三角把手离输入框边框的内缩（px）：贴死边角会压住圆角边框 */
    private static final double RESIZE_HANDLE_INSET = 3;

    /**
     * 给多行输入框（TextArea）右下角加一个三角把手，按住上下拖动即可自由调节高度
     * （交互对齐 HTML {@code <textarea>} 右下角的 resizer）。
     *
     * <p>实现是「输入框 + 叠在右下角的三角 Region」的 StackPane：TextArea 的高度原本由
     * {@code prefRowCount} 定死，这里按鼠标竖直位移改它的 prefHeight（夹在
     * {@link #RESIZABLE_AREA_MIN_H} / {@link #RESIZABLE_AREA_MAX_H} 之间），松开时以新高度回调，
     * 交给调用方持久化；双击三角把手复位到默认高度（回调传 0 表示「跟随 prefRowCount 的默认高度」）。
     *
     * <p>页面滚动只由滚轮/键盘驱动、不注册鼠标拖拽 filter，所以这里的 MOUSE_DRAGGED
     * 不会和页面滚动打架（历史上页面有「按住拖动滚动」，会把这类把手的手势抢走）。
     *
     * @param area            多行输入框（宽度与文本仍由调用方负责）
     * @param savedHeight     上次保存的高度（px）；≤0 表示不覆盖 prefRowCount 算出的默认高度
     * @param onHeightChanged 高度变化回调（仅在真正拖动/双击复位后触发，可能传 0；传 null 忽略）
     * @return 输入框 + 三角把手的叠层容器（放进卡片时替换原来的 TextArea 即可）
     */
    public static StackPane resizableTextArea(javafx.scene.control.TextArea area, double savedHeight,
                                              java.util.function.DoubleConsumer onHeightChanged) {
        final double minH = RESIZABLE_AREA_MIN_H;
        final double maxH = Math.max(minH, RESIZABLE_AREA_MAX_H);
        // 复位高度：有保存值就用保存值，否则交回 USE_COMPUTED_SIZE 让 prefRowCount 重新生效
        final double resetH = savedHeight > 0 ? clamp(savedHeight, minH, maxH) : Region.USE_COMPUTED_SIZE;
        if (savedHeight > 0) area.setPrefHeight(resetH);
        // 高度改由把手接管：允许压缩到 minH、拉伸到 maxH（宽度约束仍留在调用方）
        area.setMinHeight(minH);
        area.setMaxHeight(maxH);

        Region handle = new Region();
        handle.getStyleClass().add(RESIZE_HANDLE_STYLE_CLASS);
        handle.setCursor(javafx.scene.Cursor.SE_RESIZE);
        handle.setMinSize(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE);
        handle.setPrefSize(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE);
        handle.setMaxSize(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE);
        javafx.scene.control.Tooltip.install(handle,
                new javafx.scene.control.Tooltip(I18N.get("pagekit.t0007")));

        StackPane wrap = new StackPane(area, handle);
        StackPane.setAlignment(handle, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(handle, new Insets(0, RESIZE_HANDLE_INSET, RESIZE_HANDLE_INSET, 0));
        wrap.setMinWidth(0);
        wrap.setMaxWidth(Double.MAX_VALUE);

        // 按下记住起始鼠标 Y 与起始高度，拖动期间按位移改 prefHeight；
        // 指针拖出把手范围也继续生效（拖拽事件仍派发给按下时的节点）
        final double[] origin = new double[2];
        final boolean[] dragged = new boolean[1];
        handle.setOnMousePressed(e -> {
            dragged[0] = false;
            origin[0] = e.getSceneY();
            origin[1] = area.getHeight() > 0 ? area.getHeight() : area.prefHeight(-1);
            e.consume();
        });
        handle.setOnMouseDragged(e -> {
            dragged[0] = true;
            area.setPrefHeight(clamp(origin[1] + (e.getSceneY() - origin[0]), minH, maxH));
            e.consume();
        });
        handle.setOnMouseReleased(e -> {
            // 只在真正拖动后回调：单纯点一下把手不该写配置
            if (dragged[0] && onHeightChanged != null) onHeightChanged.accept(area.getPrefHeight());
            dragged[0] = false;
            e.consume();
        });
        handle.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            area.setPrefHeight(resetH);
            if (onHeightChanged != null) onHeightChanged.accept(resetH > 0 ? resetH : 0);
            e.consume();
        });

        return wrap;
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


// 工具条卡片 //

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


// 长列表：搜索框 + 每批 20 条 + 底部「向下加载更多」 //

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
        Label none = new Label(I18N.get("pagekit.t0008"));
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
    Button more = new Button(I18N.get("pagekit.k0002", to + " / " + items.size() + "）"));
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


// 工具方法 //

public static int parseIntSafe(String s, int defaultVal) {
    try { return Integer.parseInt(s.trim()); }
    catch (Exception e) { return defaultVal; }
}
}

package com.example.starlight.newui.glass;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.example.starlight.newui.ui.GlassFeature;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.util.Duration;

/**
 * 「通透模式」总控：接管卡片背景的渲染方式，其余一切照旧。
 *
 * <p>开关与全部可调参数在 {@code com.example.starlight.newui.ui.GlassFeature} 一个文件里。关着的时候
 * 本类一行代码都不会碰到卡片；打开之后，{@code PageKit} 造出来的每张卡片会多一层玻璃
 * （见 {@link GlassSurface}），渲染方式用的是 {@code references/liquid-glass} 那套像素采样：
 * 拍一张「窗口去掉玻璃卡片之后」的快照 → 降采样模糊 → 每块卡片按圆角 SDF 采样、着色、加高光。</p>
 *
 * <p>刷新策略（避免每帧都在烧 CPU）：</p>
 * <ul>
 *   <li>每帧只做「位置 / 尺寸 / 不透明度 / 主题有没有变」的比对，几乎零成本；</li>
 *   <li>真的变了就防抖一小会儿（{@code GlassFeature.SETTLE_MS}）再拍一次快照并重画，滚动过程中不重画；</li>
 *   <li>悬停这类不改变背景的变化只重画那一块，不重拍快照；</li>
 *   <li>页面切页的淡入淡出期间（有效不透明度 &lt; 1）不拍快照，否则会把半透明的页面拍进去。</li>
 * </ul>
 */
public final class GlassCards {

    /** 卡片属性键：同一张卡片只装一层玻璃 */
    private static final String KEY = "sl.glassSurface";

    /** 卡片属性键：滚动监听只挂一次 */
    private static final String SCROLL_KEY = "sl.glassScrollWake";

    /**
     * 算作「卡片」的样式类：约定俗成这块是页面上的一张面板/卡片，
     * 判断方式沿用 CSS 里的写法，不再各处手写 {@link #attach}。
     *
     * <p>只收「整张卡片」级别的东西：列表行、行内小卡（例如 {@code method-card}、
     * {@code home-ver-card}、{@code gamedir-detail-*-card}）不收，否则会在玻璃上再叠玻璃，
     * 糊成一片。</p>
     */
    private static final java.util.Set<String> CARD_STYLES = java.util.Set.of(
            // PageKit 造的设置卡片 / 工具条卡片（构建时已经装好，这里只是兜底）
            "settings-card", "toolbar-card",
            // 首页左侧三块 + 顶部卡片
            "home-card", "home-card-left-item",
            // 版本独立设置页的卡片
            "vset-card",
            // 账户页的卡片外壳
            "account-card-shell",
            // 游戏目录页的整块面板
            "gamedir-panel");


    private static final List<WeakReference<GlassSurface>> REGISTRY = new ArrayList<>();

    private static boolean enabled;
    private static boolean refreshing;
    private static boolean failed;
    private static NodeSnapshotBackdrop backdrop;
    private static PauseTransition settle;
    private static PauseTransition lag;
    private static AnimationTimer timer;
    private static int idleTicks;
    private static Node watchedRoot;
    private static boolean lastDarkRoot;
    private static double lastRootW = -1;
    private static double lastRootH = -1;

    private GlassCards() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 切换开关。关掉时把已经装上的玻璃全部拆掉（卡片退回原来的 CSS 背景），
     * 打开时等页面重建后由 {@link #attach(Region)} 重新装上。
     *
     * <p>{@link GlassFeature#ENABLED} 为 false 时无论传什么都只会保持关闭。</p>
     */
    public static void setEnabled(boolean value) {
        value = value && GlassFeature.ENABLED;
        if (enabled == value) {
            return;
        }
        enabled = value;
        if (value) {
            failed = false;
            ensureTimer();
        } else {
            stopTimer();
            disposeAll();
            backdrop = null;
        }
    }

    /**
     * 给一张卡片装上玻璃（开关关闭时什么都不做）。
     *
     * <p>只改这张卡片自己：加一层不参与布局的 ImageView，并挂上 {@code glass-card} 样式类
     * 让 CSS 背景让位；卡片结构、控件、事件全部不变。</p>
     */
    public static void attach(Region card) {
        if (!GlassFeature.ENABLED || !enabled || failed || card == null) {
            return;
        }
        if (!(card instanceof Pane pane)) {
            return; // 只有 Pane 能挂子节点；别的 Region 保持原样
        }
        if (card.getProperties().containsKey(KEY)) {
            return;
        }
        GlassSurface surface = new GlassSurface(pane);
        card.getProperties().put(KEY, surface);
        REGISTRY.add(new WeakReference<>(surface));
        // 卡片先建好、后进场景：等它真的显示出来，计时器才有事可做
        card.sceneProperty().addListener((o, oldScene, newScene) -> {
            if (newScene != null) {
                installScrollWake(pane);
                wake();
                scheduleRefresh();
            }
        });
        wake();
        scheduleRefresh();
    }

    /**
     * 把一整页里所有「卡片」都装上玻璃：{@link #CARD_STYLES} 命中的节点逐个 {@link #attach}。
     *
     * <p>页面在 {@code switchToPage} 里统一过一遍，所以首页、下载、资源、联机、设置全部覆盖；
     * 已经装过的（PageKit 构建时就装好的）靠卡片属性键跳过，重复调用无副作用。</p>
     */
    public static void decorate(Node pageRoot) {
        if (!enabled || failed || pageRoot == null) {
            return;
        }
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(pageRoot);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node instanceof Pane pane) {
                for (String style : pane.getStyleClass()) {
                    if (CARD_STYLES.contains(style)) {
                        attach(pane);
                        break;
                    }
                }
            }
            if (node instanceof Parent parent) {
                queue.addAll(parent.getChildrenUnmodifiable());
            }
            // 这几类容器的内容不在 children 里（在皮肤里），必须单独取：
            // 首页右栏、版本独立设置页、下载页都是「整页一个滚动区」，漏掉就整页没有玻璃。
            if (node instanceof ScrollPane scroll) {
                if (scroll.getContent() != null) {
                    queue.add(scroll.getContent());
                }
            } else if (node instanceof TabPane tabs) {
                for (javafx.scene.control.Tab tab : tabs.getTabs()) {
                    if (tab.getContent() != null) {
                        queue.add(tab.getContent());
                    }
                }
            } else if (node instanceof javafx.scene.control.TitledPane titled) {
                if (titled.getContent() != null) {
                    queue.add(titled.getContent());
                }
            }
        }
    }

    /**
     * 卡片所在的滚动区一滚，卡片的世界坐标就在变，而计时器平时是睡着的（见 {@link #wake()}），
     * 所以要在滚动那一刻把它叫醒，否则滚完不会重拍。
     */
    private static void installScrollWake(Pane card) {
        if (Boolean.TRUE.equals(card.getProperties().get(SCROLL_KEY))) {
            return;
        }
        for (Node node = card.getParent(); node != null; node = node.getParent()) {
            if (node instanceof ScrollPane pane) {
                card.getProperties().put(SCROLL_KEY, Boolean.TRUE);
                pane.vvalueProperty().addListener((o, a, b) -> wake());
                pane.hvalueProperty().addListener((o, a, b) -> wake());
                return;
            }
        }
    }

    /** 悬停等「只改观感、不改背景」的变化：拿现有快照单独重画脏掉的卡片。 */
    static void requestRenderOnly() {
        if (!enabled || refreshing || failed) {
            return;
        }
        if ((settle != null && settle.getStatus() == Animation.Status.RUNNING)
                || (lag != null && lag.getStatus() == Animation.Status.RUNNING)) {
            return; // 已经在排队一次完整刷新，交给它
        }
        if (backdrop == null || backdrop.pixels() == null) {
            scheduleRefresh();
            return;
        }
        List<GlassSurface> live = liveSurfaces();
        if (live.isEmpty()) {
            return;
        }
        boolean dark = isDark(live.get(0).card().getScene());
        refreshing = true;
        try {
            for (GlassSurface surface : live) {
                if (surface.isDirty()) {
                    surface.render(backdrop, dark);
                }
            }
        } finally {
            refreshing = false;
        }
    }

    // 内部实现 //

    /**
     * 排一次「立刻」刷新：滚动、缩放、淡入结束这类只改位置的变化。
     *
     * <p>防抖的意义：惯性滚动一帧一个位置，每帧都拍一次快照（几十毫秒）会把界面拖死。</p>
     */
    private static void scheduleRefresh() {
        if (!enabled || failed || refreshing) {
            return;
        }
        if (settle == null) {
            settle = new PauseTransition(Duration.millis(GlassFeature.SETTLE_MS));
            settle.setOnFinished(e -> refresh());
        }
        settle.playFromStart();
    }

    /**
     * 排一次「慢一拍」刷新：主题切换、壁纸更换这类变化自带淡入淡出动画
     * （启动器切主题会在整个窗口上盖一层 500ms 的渐隐遮罩），立刻拍会把遮罩拍进背景里，
     * 玻璃后面就永远压着一层旧主题的颜色。这里等动画跑完再拍。
     */
    private static void scheduleLagRefresh() {
        if (!enabled || failed || refreshing) {
            return;
        }
        if (lag == null) {
            lag = new PauseTransition(Duration.millis(GlassFeature.LAG_MS));
            lag.setOnFinished(e -> refresh());
        }
        lag.playFromStart();
    }

    /**
     * 盯住场景根的结构变化：主题渐隐遮罩、壁纸层都是运行时加进根节点的子节点，
     * 加进来或摘掉之后背景就变了，玻璃必须跟着重拍一次。
     */
    private static void watchRoot(Node root) {
        if (root == watchedRoot) {
            return;
        }
        watchedRoot = root;
        root.getStyleClass().addListener((ListChangeListener<String>) c -> {
            wake();
            scheduleLagRefresh();
        });
        root.layoutBoundsProperty().addListener((o, a, b) -> wake());
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) c -> {
                wake();
                scheduleLagRefresh();
            });
        }
    }

    /** 拍一张新快照，然后把所有玻璃卡片重画一遍。 */
    private static void refresh() {
        if (!enabled || refreshing || failed) {
            return;
        }
        List<GlassSurface> live = liveSurfaces();
        if (live.isEmpty()) {
            return;
        }
        Scene scene = live.get(0).card().getScene();
        if (scene == null) {
            return;
        }
        Node root = scene.getRoot();
        if (root == null) {
            return;
        }
        // 切页淡入/淡出期间先不拍：半透明的页面拍进去会让玻璃后面永远压着一层灰。
        // 只看祖先链，因为拍的时候卡片自己会被藏起来，它自己的 opacity 不影响背景。
        for (GlassSurface surface : live) {
            if (surface.ancestorOpacity() < 0.995) {
                return;
            }
        }
        double rw = root.getLayoutBounds().getWidth();
        double rh = root.getLayoutBounds().getHeight();
        if (rw < 40 || rh < 40) {
            return;
        }
        boolean dark = isDark(scene);
        boolean plain = root.getStyleClass().contains("high-contrast");
        watchRoot(root);
        lastDarkRoot = dark;

        Point2D rootOrigin = root.localToScene(0, 0);
        double rootX = rootOrigin == null ? 0 : rootOrigin.getX();
        double rootY = rootOrigin == null ? 0 : rootOrigin.getY();
        double[] viewport = unionViewport(live, rootX, rootY, rw, rh);
        if (viewport == null) {
            return;
        }

        refreshing = true;
        long started = System.nanoTime();
        try {
            if (backdrop == null) {
                backdrop = new NodeSnapshotBackdrop();
            }
            backdrop.bind(root);
            backdrop.setViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            backdrop.setSceneOrigin(rootX + viewport[0], rootY + viewport[1]);
            backdrop.setBaseColor(dark ? Color.web("#101B2B") : Color.web("#F1F5F9"));

            // 拍快照时把所有玻璃卡片先藏起来：相机要拍的是「卡片后面」，
            // 不是卡片自己（否则玻璃会采样到自己的上一帧，越叠越糊）。
            //
            // 用 opacity 而不是 setVisible(false)：可见性会改变卡片的几何边界
            // （不可见节点不参与父节点的 bounds 计算），而快照内部会强制走一遍布局，
            // 滚动区据此就认为「内容变矮了」，把滚动位置夹回顶部 —— 表现为「滚不动」。
            // opacity 只影响绘制，布局与边界一个像素都不动。
            List<Pane> faded = new ArrayList<>();
            List<Double> savedOpacity = new ArrayList<>();
            for (GlassSurface surface : live) {
                Pane card = surface.card();
                faded.add(card);
                savedOpacity.add(card.getOpacity());
                card.setOpacity(0);
            }
            try {
                backdrop.prepare(viewport[2], viewport[3], renderScale(viewport[2], viewport[3]));
            } finally {
                for (int i = 0; i < faded.size(); i++) {
                    faded.get(i).setOpacity(savedOpacity.get(i));
                }
            }
            long snapshotNanos = System.nanoTime() - started;
            for (GlassSurface surface : live) {
                surface.setPlain(plain);
                surface.render(backdrop, dark);
            }
            if (System.getProperty("starlight.glass.debug") != null) {
                long totalMs = (System.nanoTime() - started) / 1_000_000;
                long snapMs = snapshotNanos / 1_000_000;
                System.out.println("[Glass] 刷新 " + live.size() + " 张卡片：" + totalMs + "ms（快照 " + snapMs
                        + "ms + 采样 " + (totalMs - snapMs) + "ms，视口 " + (int) viewport[2] + "x" + (int) viewport[3]
                        + " @" + String.format(java.util.Locale.ROOT, "%.2f", backdrop.scale())
                        + " dark=" + dark + " plain=" + plain + "）");
            }
        } catch (RuntimeException ex) {
            // 通透模式是观感功能，任何异常都不该把设置页弄坏：本次会话退回普通卡片
            failed = true;
            System.err.println("[Glass] 通透模式渲染失败，已回退普通卡片: " + ex);
            disposeAll();
        } finally {
            refreshing = false;
        }
    }

    /**
     * 快照渲染比例：跟随屏幕缩放，但封顶 1.0，并按像素预算等比下调。
     *
     * <p>封顶 1.0 是有意的：玻璃内部本来就是一块模糊背景，拍到 1.25 倍只是让「模糊」更清晰一点，
     * 代价是快照像素多 56%（实测 45ms → 30ms 量级）。边缘那几道高光在 1.0 上也完全够看。</p>
     */
    private static double renderScale(double logicalW, double logicalH) {
        double output = 1;
        try {
            Screen screen = Screen.getPrimary();
            if (screen != null) {
                output = screen.getOutputScaleX();
            }
        } catch (RuntimeException ignored) {
            // 无屏幕环境（离屏预览）：按 1 处理
        }
        double scale = Math.max(0.7, Math.min(output, GlassFeature.MAX_RENDER_SCALE));
        double pixels = logicalW * logicalH * scale * scale;
        if (pixels > GlassFeature.PIXEL_BUDGET) {
            scale *= Math.sqrt(GlassFeature.PIXEL_BUDGET / pixels);
        }
        return Math.max(0.5, scale);
    }

    private static boolean isDark(Scene scene) {
        if (scene == null) {
            return false;
        }
        Node root = scene.getRoot();
        return root != null && root.getStyleClass().contains("dark");
    }

    /** 当前场景里真正显示着的玻璃卡片（已释放 / 已隐藏 / 滚出窗口 / 尺寸为 0 的都跳过） */
    private static List<GlassSurface> liveSurfaces() {
        List<GlassSurface> out = new ArrayList<>();
        Iterator<WeakReference<GlassSurface>> it = REGISTRY.iterator();
        while (it.hasNext()) {
            GlassSurface surface = it.next().get();
            if (surface == null || surface.isDisposed()) {
                it.remove();
                continue;
            }
            Pane card = surface.card();
            if (card.getScene() == null || !card.isVisible()
                    || card.getWidth() < 8 || card.getHeight() < 8) {
                continue;
            }
            // 长设置页里滚出窗口的卡片不必采样：它们一个像素都看不见，却要照付 CPU
            if (!insideWindow(card)) {
                continue;
            }
            out.add(surface);
        }
        return out;
    }

    private static boolean insideWindow(Pane card) {
        Bounds box = card.localToScene(card.getLayoutBounds());
        Node root = card.getScene().getRoot();
        if (box == null || root == null) {
            return false;
        }
        Bounds window = root.localToScene(root.getLayoutBounds());
        return window == null || box.intersects(window);
    }

    /**
     * 所有玻璃卡片的外接矩形（逻辑 px，相对根节点），外扩一圈给投影与边缘采样用。
     *
     * <p>只拍这一块而不是整窗：快照是整套流程里最贵的一步（整窗 1165x722 @1.25 约 70ms），
     * 而卡片实际占的面积往往只有窗口的一半到六成。</p>
     */
    private static double[] unionViewport(List<GlassSurface> live, double rootX, double rootY,
                                          double rootW, double rootH) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (GlassSurface surface : live) {
            Pane card = surface.card();
            Bounds box = card.localToScene(card.getLayoutBounds());
            if (box == null) {
                continue;
            }
            minX = Math.min(minX, box.getMinX());
            minY = Math.min(minY, box.getMinY());
            maxX = Math.max(maxX, box.getMaxX());
            maxY = Math.max(maxY, box.getMaxY());
        }
        if (minX == Double.MAX_VALUE) {
            return null;
        }
        double pad = GlassRenderer.MARGIN + 12;
        double x0 = clamp(minX - rootX - pad, 0, rootW);
        double y0 = clamp(minY - rootY - pad, 0, rootH);
        double x1 = clamp(maxX - rootX + pad, 0, rootW);
        double y1 = clamp(maxY - rootY + pad, 0, rootH);
        double w = x1 - x0;
        double h = y1 - y0;
        if (w < 16 || h < 16) {
            return null;
        }
        return new double[]{x0, y0, w, h};
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void ensureTimer() {
        if (timer == null) {
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    tick();
                }
            };
        }
        idleTicks = 0;
        timer.start();
    }

    private static void stopTimer() {
        if (timer != null) {
            timer.stop();
        }
    }

    /**
     * 「有事要办」：把每帧比对唤醒。
     *
     * <p>平时这个计时器会在安静一阵后自己停下（否则启动器挂在后台也要一直跑 60fps 的脉冲），
     * 所以任何可能改变玻璃外观的地方都要叫一声：卡片进场景 / 悬停 / 改尺寸 / 滚动 / 换主题 /
     * 背景层增删。少叫一声的后果只是玻璃晚一步更新，不会出错。</p>
     */
    static void wake() {
        if (!enabled || failed) {
            return;
        }
        ensureTimer();
    }

    /** 每帧：只做廉价的比对，发现变化才排一次刷新。 */
    private static void tick() {
        if (!enabled || refreshing || failed) {
            return;
        }
        List<GlassSurface> live = liveSurfaces();
        if (live.isEmpty()) {
            // 当前页面没有玻璃卡片：安静 1.5 秒后熄火，等下一次 wake()
            if (++idleTicks > GlassFeature.IDLE_TICKS) {
                stopTimer();
            }
            return;
        }
        idleTicks = 0;
        Scene scene = live.get(0).card().getScene();
        if (scene == null) {
            return;
        }
        Node root = scene.getRoot();
        if (root == null) {
            return;
        }
        boolean dark = isDark(scene);
        boolean plain = root.getStyleClass().contains("high-contrast");
        watchRoot(root);
        boolean darkChanged = dark != lastDarkRoot;
        lastDarkRoot = dark;
        boolean changed = false;

        double rw = root.getLayoutBounds().getWidth();
        double rh = root.getLayoutBounds().getHeight();
        if (Math.abs(rw - lastRootW) > 0.5 || Math.abs(rh - lastRootH) > 0.5) {
            lastRootW = rw;
            lastRootH = rh;
            changed = true;
        }
        for (GlassSurface surface : live) {
            if (surface.isPlain() != plain) {
                surface.setPlain(plain);
                changed = true;
            }
            if (surface.detectChange(dark)) {
                changed = true;
            }
        }
        if (changed) {
            // 换主题自带一层 500ms 渐隐遮罩，立刻拍会把遮罩拍进背景
            if (darkChanged) {
                scheduleLagRefresh();
            } else {
                scheduleRefresh();
            }
        }
    }

    private static void disposeAll() {
        for (WeakReference<GlassSurface> ref : REGISTRY) {
            GlassSurface surface = ref.get();
            if (surface != null) {
                surface.dispose();
            }
        }
        REGISTRY.clear();
    }

    /** 调试用：当前挂着的玻璃卡片数量（离屏预览 / 自检脚本可直接读） */
    public static int liveCount() {
        return liveSurfaces().size();
    }
}

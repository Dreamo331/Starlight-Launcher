package com.example.starlight.newui.ui;

import com.example.starlight.newui.LauncherContext;

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.Map;

/**
 * 惯性滚动引擎（规格：.codeartsdoer/specs/inertia_scrolling/spec.md）。
 *
 * <p>页面滚动区由 {@link PageKit#configureScrollPane}/{@link PageKit#attachInertia} 挂接；
 * 每个面板持有一个 Engine 实例（存入 {@code sp.getProperties()}，无静态注册表）：
 * <ul>
 *   <li><b>双值模型</b>：targetV 只被输入路径写（滚轮/拖拽/键盘/吸附），currentV 只被帧回调写
 *       （拖拽直控期二值同步例外）；帧回调 {@code currentV += (targetV-currentV)*ease} 后
 *       {@code setVvalue(currentV)}，|Δ| &lt; {@value #SNAP_EPSILON} 时精确停靠并 stop。</li>
 *   <li><b>自停双保险</b>：帧回调首行 scene==null（切页移除，最迟一帧停止，无幽灵动画）
 *       与 SmoothScroll=false（关闭瞬间停靠当前目标位置，不悬停半途）。</li>
 *   <li><b>线程规则</b>：不创建线程、不注册后台回调、无 Platform.runLater——
 *       AnimationTimer 帧回调与鼠标/键盘/滚轮事件全部由 JavaFX Application Thread 派发。</li>
 * </ul>
 *
 * <p>滚轮入口不在此注册：滚轮 SCROLL filter 由 PageKit 单点重写（惯性开/关二分），
 * 开启分支经 {@link #wheelScroll} 调入引擎；键盘用冒泡 {@code addEventHandler}
 * （控件先 consume 的方向键天然被尊重）；拖拽用捕获 filter 三连（豁免清单见 {@link #isInteractive}）。
 */
public final class InertiaScrollSupport {

    /** ease 三档（spec 6.1.2）：慢滑 / 标准 / 跟手 */
    public static final double EASE_SLOW = 0.05;
    public static final double EASE_STD = 0.1;
    public static final double EASE_FAST = 0.18;
    /** 默认档位 = 标准（spec 5.2.1.3） */
    public static final double DEFAULT_EASE = EASE_STD;
    /** 接近吸附阈值：|targetV-currentV| 小于此值时精确停靠（spec 6.1.3） */
    public static final double SNAP_EPSILON = 0.0005;
    /** fling 系数：每秒 1 屏速度的甩动滑行 0.28 屏（spec 6.2.3 / design D5） */
    public static final double FLING_FACTOR = 0.28;
    /** 最近一次拖拽距释放超过该值 → 速度视为 0（停顿后原地释放不滑行） */
    public static final double FLING_IDLE_MS = 250;
    /** 键盘 ↑/↓ 步长（spec 6.2.5） */
    public static final double KEY_STEP = 0.05;
    /** 键盘 PageUp/PageDown 步长（spec 6.2.5） */
    public static final double PAGE_STEP = 0.85;
    /** 滚轮静止判定：已声明分段区域滚轮输入静默该时长后就近归段（spec 5.6.1.1） */
    public static final double SNAP_IDLE_MS = 120;
    /** 配置键：惯性滚动开关（默认 true） */
    public static final String CONFIG_ENABLED = "SmoothScroll";
    /** 配置键：滚动平滑度档位（"0.05"/"0.1"/"0.18"，默认 "0.1"） */
    public static final String CONFIG_EASE = "SmoothScrollEase";
    /** 挂接面板样式类（style.css 新分区作用域宿主，挂接时由代码添加） */
    public static final String PANEL_STYLE_CLASS = "inertia-pane";
    /** 分段吸附声明键（存 sp.getProperties；&lt;2 视为未声明，spec 5.6.1.2） */
    public static final String PROP_SECTIONS = "inertia.sections";
    /** 安装幂等标记（同时充当引擎实例引用，无静态注册表） */
    static final String INSTALL_KEY = "starlight.inertia";

    private static final String EASE_SLOW_VALUE = "0.05";
    private static final String EASE_STD_VALUE = "0.1";
    private static final String EASE_FAST_VALUE = "0.18";

    private InertiaScrollSupport() {
    }

    /**
     * 给面板安装惯性引擎（幂等：重复调用直接返回）。
     *
     * <p>注册拖拽三 filter、键盘冒泡 handler 与 contentProperty 监听
     * （内容根节点 focusTraversable，保障"点击空白后键盘可滚"）；
     * ctx.config() 为 null 时按全默认值运行（对齐既有过滤器 null 防护惯例）。
     */
    public static void install(LauncherContext ctx, ScrollPane sp) {
        if (ctx == null || sp == null) return;
        if (sp.getProperties().get(INSTALL_KEY) != null) return;
        new Engine(ctx, sp).attach();
    }

    /** 声明分段吸附段数（≥2 生效）；首期实验能力，仅预览脚本构造 sections=4 页面验收 */
    public static void setSections(ScrollPane sp, int sections) {
        if (sp == null) return;
        sp.getProperties().put(PROP_SECTIONS, sections);
    }

    /** 惯性开关当前状态（PageKit 滚轮过滤器二分用） */
    public static boolean isEnabled(LauncherContext ctx) {
        return readEnabled(ctx);
    }

    /**
     * 滚轮路径：targetV = clamp(targetV − deltaY/range × factor, 0, 1)。
     * range 为「可滚区间」像素高度（contentHeight − 视口高度，即 vvalue 1.0 实际对应的距离）——
     * 按它归一后每格滚轮的像素位移恒定，短页面不再比长页面慢；
     * 由 PageKit 滚轮过滤器的惯性开启分支调用（range&gt;0 已由调用方判定）；
     * factor = ScrollSpeed/35.0（倍率语义保留）。
     */
    public static void wheelScroll(ScrollPane sp, double deltaY, double range, double factor) {
        if (sp == null) return;
        if (sp.getProperties().get(INSTALL_KEY) instanceof Engine engine) {
            engine.wheel(deltaY, range, factor);
        }
    }

    /**
     * 给虚拟化 ListView（VirtualFlow + 隐藏滚动条）挂接惯性滚动（幂等）。
     *
     * <p>虚拟列表没有 ScrollPane content 可驱动，改为经 CSS lookup 拿到 VirtualFlow 的
     * 竖直 ScrollBar，按 vvalue 比例驱动：滚轮一格 ≈ {@value #LISTVIEW_ROWS_PER_NOTCH} 行
     * × ScrollSpeed 倍率，与 ScrollPane 共用 ease 收敛/吸附手感；SmoothScroll=false 时
     * 不拦截滚轮（保留原生滚动）。拖拽多选/键盘导航仍归 ListView 原生处理。
     */
    public static void installListView(LauncherContext ctx, ListView<?> lv) {
        if (ctx == null || lv == null) return;
        if (lv.getProperties().get(INSTALL_KEY) != null) return;
        lv.getProperties().put(INSTALL_KEY, Boolean.TRUE);
        lv.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!readEnabled(ctx)) return;
            ScrollBar bar = listViewBar(lv);
            if (bar == null || bar.getMax() <= 0) return; // 皮肤未就绪 / 内容不足一屏：交原生
            e.consume();
            ListViewDriver driver = (ListViewDriver) lv.getProperties().get(LISTVIEW_DRIVER_KEY);
            if (driver == null || driver.bar() != bar) {
                driver = new ListViewDriver(bar, ctx);
                lv.getProperties().put(LISTVIEW_DRIVER_KEY, driver);
            }
            // deltaY>0 表示滚轮向上：VirtualFlow 滚动条 value 0=顶部，与 ScrollPane 引擎同号取负
            double notches = -e.getDeltaY() / 40.0;   // 一格滚轮 ≈ 一个 notch
            driver.nudge(notches * readSpeedFactor(ctx) * LISTVIEW_ROWS_PER_NOTCH);
        });
    }

    /** ListView 每格滚轮滚动的行数（原生 ListView 一格约 3 行，保持一致再乘 ScrollSpeed 倍率） */
    public static final double LISTVIEW_ROWS_PER_NOTCH = 3.0;
    /** ListView 惯性驱动器缓存键（存 lv.getProperties；skin 重建后按实际滚动条重建） */
    static final String LISTVIEW_DRIVER_KEY = "inertia.listViewDriver";
    /** ListView 竖直滚动条缓存键（skin 重建后由 lookup 重新解析） */
    static final String LISTVIEW_BAR_KEY = "inertia.listViewBar";

    /** ListView 虚拟滚动条的惰性解析（skin 可能在首次滚轮后才就绪；skin 重建后重新 lookup） */
    private static ScrollBar listViewBar(ListView<?> lv) {
        if (lv.getProperties().get(LISTVIEW_BAR_KEY) instanceof ScrollBar cached
                && cached.getScene() != null) {
            return cached;
        }
        ScrollBar bar = null;
        for (Node n : lv.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb
                    && sb.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                bar = sb;
                break;
            }
        }
        lv.getProperties().put(LISTVIEW_BAR_KEY, bar);
        return bar;
    }

    /** ScrollSpeed 倍率（缺省/非法回退 35 → 1.0） */
    private static double readSpeedFactor(LauncherContext ctx) {
        Map<String, String> cfg = ctx == null ? null : ctx.config();
        String v = cfg == null ? null : cfg.get("ScrollSpeed");
        if (v != null) {
            try {
                return Math.max(0.1, Integer.parseInt(v.trim()) / 35.0);
            } catch (NumberFormatException ignored) {
            }
        }
        return 1.0;
    }

    /** 解析 SmoothScrollEase 为档位字符串（白名单三档，缺省/非法回退 "0.1"）；设置页初始选中共用 */
    public static String readEaseValue(LauncherContext ctx) {
        Map<String, String> cfg = ctx == null ? null : ctx.config();
        String v = cfg == null ? null : cfg.get(CONFIG_EASE);
        if (EASE_SLOW_VALUE.equals(v) || EASE_STD_VALUE.equals(v) || EASE_FAST_VALUE.equals(v)) {
            return v;
        }
        return EASE_STD_VALUE;
    }

    /** 解析 SmoothScrollEase 为 ease 数值（异常吞并回退 0.1，spec 5.3.3.1） */
    private static double readEase(LauncherContext ctx) {
        return Double.parseDouble(readEaseValue(ctx));
    }

    /** 解析 SmoothScroll：缺省/非 "false" 一律视为开启（spec 5.3.3.1） */
    private static boolean readEnabled(LauncherContext ctx) {
        Map<String, String> cfg = ctx == null ? null : ctx.config();
        return cfg == null || !"false".equalsIgnoreCase(cfg.get(CONFIG_ENABLED));
    }

    /**
     * 拖拽豁免判定：沿 getTarget() 向上遍历父链（到 boundary 为止，不含），命中交互控件则本次手势不接管
     * （滑块/按钮/输入框等照常工作，页面不滚——spec 4.5.3/5.5.1.3）。
     * CheckBox/RadioButton/ToggleButton 均为 ButtonBase 子类，toggle-switch（CheckBox）同覆盖。
     * boundary 传发起判定的面板自身：链路上的<b>其它</b> ScrollPane（嵌套内层滚动区）也算交互控件，
     * 手势归最内层面板，避免内外两层同时拖拽。
     */
    private static boolean isInteractive(Node n, Node boundary) {
        while (n != null && n != boundary) {
            if (n instanceof ButtonBase
                    || n instanceof TextInputControl
                    || n instanceof ComboBox<?>
                    || n instanceof ChoiceBox<?>
                    || n instanceof Slider
                    || n instanceof ScrollBar
                    || n instanceof TableView<?>
                    || n instanceof ListView<?>
                    || n instanceof TreeView<?>
                    || n instanceof TabPane
                    || n instanceof ScrollPane) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    /** 键盘防御判定：焦点在这些控件上时按键归控件，页面不滚（与冒泡机制双保险，spec 5.7.1.2） */
    private static boolean keyboardGuard(Node n) {
        return n instanceof TextInputControl || n instanceof Slider || n instanceof ComboBox<?>;
    }

    // ===== 预览脚本验收通道（生产代码不使用；避免暴露内部类） =====

    /** 帧回调计数（report②：停靠后不再递增 = 无空转动画） */
    public static long debugFrameCount(ScrollPane sp) {
        return sp != null && sp.getProperties().get(INSTALL_KEY) instanceof Engine engine
                ? engine.frameCount : -1;
    }

    /** 累计帧耗时（ns，report⑦：均值 &lt;1ms） */
    public static long debugFrameNanosTotal(ScrollPane sp) {
        return sp != null && sp.getProperties().get(INSTALL_KEY) instanceof Engine engine
                ? engine.frameNanosTotal : -1;
    }

    /** 当前 targetV（report①③④⑤⑥ 期望值核对） */
    public static double debugTargetV(ScrollPane sp) {
        return sp != null && sp.getProperties().get(INSTALL_KEY) instanceof Engine engine
                ? engine.targetV : Double.NaN;
    }

    /** 当前 currentV（拖拽/收敛位置核对） */
    public static double debugCurrentV(ScrollPane sp) {
        return sp != null && sp.getProperties().get(INSTALL_KEY) instanceof Engine engine
                ? engine.currentV : Double.NaN;
    }

    /**
     * 每面板一个引擎实例。targetV 仅输入路径写、currentV 仅帧回调写（拖拽直控期同步例外）；
     * 单实例 AnimationTimer，需要时 start（幂等）、停靠即 stop，静止时零帧回调开销。
     */
    private static final class Engine {

        private final LauncherContext ctx;
        private final ScrollPane sp;
        private final AnimationTimer timer;
        private final PauseTransition wheelSnap;
        private double targetV;
        private double currentV;
        private double ease;
        private boolean dragging;
        /** 引擎当前是否在动画（AnimationTimer 无公开运行态，自维护；用于从静止恢复时对齐面板实际位置） */
        private boolean animating;
        private double startY;
        private double startV;
        private double lastSceneY;
        private long lastNanos;
        private int dragCount;
        private long frameCount;
        private long frameNanosTotal;

        private Engine(LauncherContext ctx, ScrollPane sp) {
            this.ctx = ctx;
            this.sp = sp;
            this.ease = readEase(ctx);
            this.targetV = sp.getVvalue();
            this.currentV = sp.getVvalue();
            this.timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    frameCount++;
                    long t0 = System.nanoTime();
                    try {
                        if (sp.getScene() == null) {
                            stopAnimating(); // 切页移除：最迟一帧内停止，无幽灵动画
                            return;
                        }
                        if (!readEnabled(ctx)) {
                            settle();        // 关闭瞬间：停靠当前目标位置，不悬停半途
                            return;
                        }
                        currentV += (targetV - currentV) * ease;
                        if (Math.abs(targetV - currentV) < SNAP_EPSILON) {
                            currentV = targetV;   // 接近吸附：最终位置精确等于 targetV
                            sp.setVvalue(currentV);
                            stopAnimating();
                            return;
                        }
                        sp.setVvalue(currentV);
                    } finally {
                        frameNanosTotal += System.nanoTime() - t0;
                    }
                }
            };
            this.wheelSnap = new PauseTransition(Duration.millis(SNAP_IDLE_MS));
            this.wheelSnap.setOnFinished(e -> {
                int s = sections();
                if (s < 2) return;
                resyncIfIdle();
                targetV = clamp01(Math.round(currentV * s) / (double) s);
                startAnimating();
            });
            sp.getProperties().put(INSTALL_KEY, this);
        }

        private void attach() {
            sp.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onPressed);
            sp.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onDragged);
            sp.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onReleased);
            sp.addEventHandler(KeyEvent.KEY_PRESSED, this::onKey);
            sp.contentProperty().addListener((o, ov, nv) -> {
                if (nv != null) nv.setFocusTraversable(true);
            });
            if (sp.getContent() != null) {
                sp.getContent().setFocusTraversable(true);
            }
        }

        // ===== 输入路径 =====

        private void wheel(double deltaY, double range, double factor) {
            if (!scrollable()) return;
            resyncIfIdle();
            targetV = clamp01(targetV - deltaY / range * factor);
            startAnimating();
            if (sections() >= 2) {
                wheelSnap.playFromStart();   // 滚轮静止后就近归段，未声明区域零开销
            }
        }

        /** 按下：惯性关闭时事件直通控件与原生行为；豁免未命中才接管；滑行中再按下立即以 currentV 接管为起点（spec 5.5.3.2） */
        private void onPressed(MouseEvent e) {
            if (!readEnabled(ctx)) return;
            if (!(e.getTarget() instanceof Node hit) || isInteractive(hit, sp)) return;
            if (!scrollable()) return;
            dragging = true;
            stopAnimating();
            wheelSnap.stop();
            targetV = currentV;
            startY = e.getSceneY();
            startV = currentV;
            lastSceneY = startY;
            lastNanos = System.nanoTime();
            dragCount = 0;
        }

        /** 拖拽直控：targetV = currentV = startV − (sceneY−startY)/range（可滚区间像素），二值同步 */
        private void onDragged(MouseEvent e) {
            if (!dragging) return;
            double range = scrollableHeight();
            if (range <= 0) return;
            double v = clamp01(startV - (e.getSceneY() - startY) / range);
            targetV = v;
            currentV = v;
            sp.setVvalue(currentV);
            lastSceneY = e.getSceneY();
            lastNanos = System.nanoTime();
            dragCount++;
            e.consume();
        }

        /**
         * 释放：速度采样（&lt;2 次 DRAGGED 点按无速度、dt==0 防除零、停顿 &gt;250ms 视为 0）
         * → fling 折算（velocityNorm 单位 vvalue/s）→ 分段吸附（D10：有甩动取甩动后 targetV）
         * → 进入动画。
         */
        private void onReleased(MouseEvent e) {
            if (!dragging) return;
            dragging = false;
            double velocity = 0;
            if (dragCount >= 2) {
                double dtMs = (System.nanoTime() - lastNanos) / 1_000_000.0;
                double range = scrollableHeight();
                if (dtMs > 0 && dtMs <= FLING_IDLE_MS && range > 0) {
                    velocity = (e.getSceneY() - lastSceneY) / dtMs / range * 1000.0;
                }
            }
            targetV = clamp01(currentV - velocity * FLING_FACTOR);
            int s = sections();
            if (s >= 2) {
                targetV = clamp01(Math.round(targetV * s) / (double) s);
            }
            startAnimating();
            e.consume();
        }

        /**
         * 键盘（冒泡 handler）：↑/↓ ±0.05、PgDn/PgUp ±0.85、Home→0、End→1；
         * 每次按键独立累加 targetV（长按连发连续收敛）；关闭时直接 setVvalue 立即到位（spec 5.7.1.3）。
         */
        private void onKey(KeyEvent e) {
            if (e.getTarget() instanceof Node hit && keyboardGuard(hit)) return;
            if (!scrollable()) return;
            double to;
            switch (e.getCode()) {
                case DOWN -> to = targetV + KEY_STEP;
                case UP -> to = targetV - KEY_STEP;
                case PAGE_DOWN -> to = targetV + PAGE_STEP;
                case PAGE_UP -> to = targetV - PAGE_STEP;
                case HOME -> to = 0;
                case END -> to = 1;
                default -> {
                    return;
                }
            }
            if (readEnabled(ctx)) {
                resyncIfIdle();
                targetV = clamp01(to);
                startAnimating();
            } else {
                targetV = currentV = clamp01(to);
                sp.setVvalue(currentV);
            }
            e.consume();
        }

        // ===== 内部工具 =====

        /**
         * 动画启动即刷新 ease（设置页切档后下次滚动立即用新档位，spec 5.3.1.3.a）；
         * 从静止恢复时以面板实际位置为起点（关闭期/外部跳转后 currentV 可能陈旧，防重开跳变）。
         */
        private void startAnimating() {
            ease = readEase(ctx);
            if (!animating) {
                currentV = clamp01(sp.getVvalue());
            }
            animating = true;
            timer.start();
        }

        private void stopAnimating() {
            timer.stop();
            animating = false;
        }

        /** 输入驱动路径专用：动画未运行时把双值对齐到面板实际位置（拖拽接管期与动画运行期不动） */
        private void resyncIfIdle() {
            if (!animating && !dragging) {
                currentV = clamp01(sp.getVvalue());
                targetV = currentV;
            }
        }

        private void settle() {
            currentV = targetV;
            sp.setVvalue(currentV);
            stopAnimating();
        }

        /** 内容不足（contentHeight − viewHeight ≤ 0）时全部输入路径忽略（spec 5.1.3.1） */
        private boolean scrollable() {
            return contentHeight() - sp.getViewportBounds().getHeight() > 0;
        }

        /** 可滚区间像素高度：内容高 − 视口高；vvalue 的 1.0 实际对应这段距离（恒定像素速度的归一基准） */
        private double scrollableHeight() {
            return contentHeight() - sp.getViewportBounds().getHeight();
        }

        private double contentHeight() {
            Node c = sp.getContent();
            return c == null ? 0 : c.getBoundsInLocal().getHeight();
        }

        private int sections() {
            Object v = sp.getProperties().get(PROP_SECTIONS);
            return v instanceof Number n ? n.intValue() : 0;
        }

        private static double clamp01(double v) {
            return Math.max(0, Math.min(1, v));
        }
    }

    /**
     * ListView 惯性驱动器：把 vvalue 比例 [0,1] 缓动到 VirtualFlow 的竖直滚动条上。
     * 与 ScrollPane Engine 相同的双值收敛模型（target 只被输入写、current 只被帧回调写），
     * 帧回调检查 bar.getScene()==null（控件移出场景即停，无幽灵动画）。
     */
    private static final class ListViewDriver {

        private final ScrollBar bar;
        private final LauncherContext ctx;
        private final AnimationTimer timer;
        private double target;
        private double current;
        private double ease;
        private boolean animating;

        ListViewDriver(ScrollBar bar, LauncherContext ctx) {
            this.bar = bar;
            this.ctx = ctx;
            this.ease = readEase(ctx);
            this.timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    if (bar.getScene() == null) {   // 控件已移出场景：停止，无幽灵动画
                        stopAnimating();
                        return;
                    }
                    current += (target - current) * ease;
                    if (Math.abs(target - current) < SNAP_EPSILON) {
                        current = target;
                        apply();
                        stopAnimating();
                        return;
                    }
                    apply();
                }
            };
        }

        ScrollBar bar() {
            return bar;
        }

        /** 追加 n 行滚动量（rows&gt;0 向下）；动画未运行时以滚动条实际位置为起点并刷新 ease */
        void nudge(double rows) {
            if (!animating) {
                ease = readEase(ctx);
                current = readFraction();
                target = current;
                animating = true;
                timer.start();
            }
            double max = bar.getMax();
            double extent = bar.getVisibleAmount();
            // 1 行 ≈ 视口高度的 1/10（每屏约 10 行；像素/单元格两种 VirtualFlow 单位下按比例都成立）
            double rowFrac = (max > 0 && extent > 0) ? extent / (max + extent) / 10.0 : 0.02;
            target = clamp01(target + rows * rowFrac);
        }

        private void apply() {
            double max = bar.getMax();
            if (max > 0) bar.setValue(clamp01(current) * max);
        }

        private double readFraction() {
            double max = bar.getMax();
            return max > 0 ? clamp01(bar.getValue() / max) : 0;
        }

        private void stopAnimating() {
            timer.stop();
            animating = false;
        }

        private static double clamp01(double v) {
            return Math.max(0, Math.min(1, v));
        }
    }
}
package com.example.starlight.newui.ui;

import java.lang.reflect.Method;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * 通透模式（Liquid Glass 卡片）的**唯一开关文件**。
 *
 * <p>要改这个功能的状态、默认值、观感或性能，只改这个文件；实现代码在
 * {@code com.example.starlight.newui.glass}（渲染引擎与卡片玻璃层），一般不需要动。</p>
 *
 * <h2>一、两个开关</h2>
 * <ul>
 *   <li>{@link #ENABLED} —— 总开关。设成 {@code false} 之后：设置页不再出现「通透模式」卡片、
 *       卡片不会装玻璃、计时器不会启动，实现类也不会被加载。等同于这个功能不存在。</li>
 *   <li>{@link #SHOW_TOGGLE} —— 只藏界面开关。设置页不再显示那张卡片，但功能仍按
 *       {@link #DEFAULT_ON}（或配置文件里已有的值）生效，适合"强制开/强制关"的调试场景。</li>
 * </ul>
 *
 * <h2>二、编译期不参与构建</h2>
 * <p>Java 没有预处理器，真正的「不编译」靠 Maven profile：</p>
 * <pre>
 * mvn -Pno-glass clean package      # 整个 newui.glass 包不参与编译
 * </pre>
 * <p>本文件<b>不直接引用</b>那个包里的任何类型（实现类通过反射解析，见文末），
 * 所以把包排除掉之后工程照样能编译，功能自动降级为「什么都没有」，也不会报错。</p>
 *
 * <h2>三、默认状态与配置键</h2>
 * <p>用户在「设置 → 个性化 → 主题与背景 → 通透模式」里的选择存在
 * {@code starlight-client.ini} 的 {@link #CONFIG_KEY} 键里；没有这个键时用 {@link #DEFAULT_ON}。</p>
 */
public final class GlassFeature {

    // ===== 编译期总开关 =====
    /** 总开关：false = 整个通透模式停用（配合 {@code -Pno-glass} 时连实现代码都不编译）。 */
    public static final boolean ENABLED = false;

    // ===== UI =====
    /** 设置页是否显示「通透模式」开关卡片。 */
    public static final boolean SHOW_TOGGLE = true;
    /** 配置里还没有这个键时的默认状态（true = 默认开启）。 */
    public static final boolean DEFAULT_ON = false;
    /** 配置键：写进 starlight-client.ini。 */
    public static final String CONFIG_KEY = "GlassMode";

    // ===== 观感参数（改这里就能调画面） =====
    /** 卡片圆角半径（逻辑 px）。 */
    public static final double RADIUS = 12;
    /** 采样背景的模糊半径（逻辑 px）。越大越朦胧。 */
    public static final double BLUR = 9;
    /** 边缘高光强度。 */
    public static final double SPECULAR = 1.35;
    /** 边缘带宽度相对卡片高度的比例（10–26px 之间夹取）。 */
    public static final double EDGE_WIDTH_RATIO = 0.28;
    /** 浅色主题：白色着色浓度（渲染器内部中心只取 60%，见 GlassSurface.applyLook）。 */
    public static final double TINT_ALPHA_LIGHT = 0.52;
    /** 深色主题：深色着色浓度。 */
    public static final double TINT_ALPHA_DARK = 0.62;
    /** 浅色主题：提亮量（-1..1）。 */
    public static final double BRIGHTNESS_LIGHT = 0.05;
    /** 深色主题：压暗量（-1..1）。深色主题文字是白的，压暗保证亮壁纸上也读得清。 */
    public static final double BRIGHTNESS_DARK = -0.10;
    /** 浅色主题：饱和度倍数。 */
    public static final double SATURATION_LIGHT = 1.18;
    /** 深色主题：饱和度倍数。 */
    public static final double SATURATION_DARK = 1.12;
    /** 鼠标悬停时额外加的着色浓度。 */
    public static final double HOVER_TINT_BOOST = 0.08;

    // ===== 性能参数 =====
    /** 快照/采样比例上限（1.0 = 不放大；越小越快、边缘越软）。 */
    public static final double MAX_RENDER_SCALE = 1.0;
    /** 快照像素预算：超过就等比降采样。 */
    public static final double PIXEL_BUDGET = 2_500_000;
    /** 位置/尺寸变化后等多久再重拍（毫秒）。 */
    public static final long SETTLE_MS = 110;
    /** 主题切换这类带渐隐动画的变化等多久再重拍（毫秒）。 */
    public static final long LAG_MS = 620;
    /** 没有玻璃卡片时，计时器安静多少帧后熄火（60fps 下 90 ≈ 1.5 秒）。 */
    public static final int IDLE_TICKS = 90;

    // ===== 反射桥（实现包可以整个不参与编译） =====
    //
    // 为什么不直接 import GlassCards：那个包会被 -Pno-glass 从编译里排除，
    // 只要本文件出现一个 GlassCards 的类型引用，排除后工程就编不过。
    // 这里用反射拿方法句柄，排除时 resolve() 返回 null，所有入口自动空转。

    private static final String IMPL_CLASS = "com.example.starlight.newui.glass.GlassCards";

    private static final Class<?> IMPL = resolveImpl();
    private static final Method M_SET_ENABLED = method("setEnabled", boolean.class);
    private static final Method M_ATTACH = method("attach", Region.class);
    private static final Method M_DECORATE = method("decorate", Node.class);
    private static final Method M_LIVE_COUNT = method("liveCount");

    private GlassFeature() {
    }

    /** 实现是否可用（编译时被排除、或类加载失败时为 false）。 */
    public static boolean available() {
        return ENABLED && IMPL != null;
    }

    /** 读配置判断当前是否开启（缺省用 {@link #DEFAULT_ON}）。 */
    public static boolean isEnabled(Map<String, String> config) {
        if (!ENABLED) {
            return false;
        }
        if (config == null) {
            return DEFAULT_ON;
        }
        String value = config.get(CONFIG_KEY);
        return value == null ? DEFAULT_ON : "true".equalsIgnoreCase(value);
    }

    /** 运行时总控：开/关整个功能（关掉时会把已经装上的玻璃拆掉，卡片回到原样式）。 */
    public static void setEnabled(boolean value) {
        invoke(M_SET_ENABLED, value && ENABLED);
    }

    /** 给一张卡片装上玻璃（开关关闭时不会有任何效果）。 */
    public static void install(Node card) {
        if (card instanceof Region region) {
            invoke(M_ATTACH, region);
        }
    }

    /** 把一整页里所有「卡片」都装上玻璃（每次切页调用一次，已装过的会跳过）。 */
    public static void decoratePage(Node pageRoot) {
        invoke(M_DECORATE, pageRoot);
    }

    /** 当前场景里装着的玻璃卡片数量（调试/自检用）。 */
    public static int liveCount() {
        Object value = invoke(M_LIVE_COUNT);
        return value instanceof Integer n ? n : 0;
    }

    /**
     * 在设置页插入「通透模式」开关卡片，并接好开关的全部逻辑。
     *
     * <p>整块 UI 也放在这个文件里，是为了"改这一处就能改整个功能"：
     * 标题/说明文案走 i18n 键、开关状态走 {@link #CONFIG_KEY}、
     * 界面显隐由 {@link #ENABLED} 与 {@link #SHOW_TOGGLE} 决定。</p>
     *
     * @param ctx  启动器上下文（读写配置、重建设置页、弹 toast）
     * @param page 设置页根容器（`PageKit.settingsPage` 返回的那个 VBox）
     */
    public static void maybeAddToggle(com.example.starlight.newui.LauncherContext ctx,
                                      javafx.scene.layout.VBox page) {
        if (!ENABLED || !SHOW_TOGGLE || ctx == null || page == null) {
            return;
        }
        javafx.scene.control.CheckBox toggle = PageKit.toggle(isEnabled(ctx.config()));
        toggle.setOnAction(e -> {
            boolean on = toggle.isSelected();
            ctx.config().put(CONFIG_KEY, String.valueOf(on));
            // 先落总控再 saveConfig：saveConfig 会重建全部设置页，
            // 新页面里的卡片按新开关决定挂不挂玻璃（见 PageKit 各卡片工厂里的 GlassFeature.install）。
            setEnabled(on);
            ctx.saveConfig();
            if (ctx.currentSidebarItemId() != null) {
                ctx.switchToPage(ctx.currentSidebarItemId());
            }
            ctx.toast(com.example.starlight.util.I18N.get("theme.p0052",
                    com.example.starlight.util.I18N.get(on ? "theme.t0054" : "theme.t0055")));
        });
        page.getChildren().add(PageKit.settingsCard(
                com.example.starlight.util.I18N.get("theme.t0052"),
                com.example.starlight.util.I18N.get("theme.t0053"),
                toggle));
    }

    // 内部 //

    private static Class<?> resolveImpl() {
        if (!ENABLED) {
            return null;
        }
        try {
            return Class.forName(IMPL_CLASS);
        } catch (Throwable t) {
            // -Pno-glass 编译时本来就没有这个类，属于预期情况，只提示一行
            System.out.println("[Glass] 通透模式实现未参与编译（-Pno-glass），功能已跳过");
            return null;
        }
    }

    private static Method method(String name, Class<?>... params) {
        if (IMPL == null) {
            return null;
        }
        try {
            Method m = IMPL.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            System.err.println("[Glass] 找不到 " + IMPL_CLASS + "." + name + ": " + t);
            return null;
        }
    }

    private static Object invoke(Method method, Object... args) {
        if (method == null || !ENABLED) {
            return null;
        }
        try {
            return method.invoke(null, args);
        } catch (Throwable t) {
            // 通透模式是观感功能：任何反射失败都只让玻璃退场，不能影响启动器
            System.err.println("[Glass] 调用失败（" + method.getName() + "）: " + t);
            return null;
        }
    }
}

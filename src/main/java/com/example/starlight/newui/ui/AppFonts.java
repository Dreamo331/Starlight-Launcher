package com.example.starlight.newui.ui;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * UI 字体注册：把 classpath 上 {@code /ttf} 下的字体登记进 JavaFX 字体表，
 * 之后 {@code style.css} / {@code starlight-splash.css} 就能按家族名引用。
 *
 * <p><b>为什么必须在代码里加载：</b>JavaFX CSS 不支持 {@code @font-face}，只认「已注册的字体家族名」。
 * 所以 ttf 必须由代码在创建任何 {@link javafx.scene.Scene} 之前用 {@link Font#loadFont} 注册一次；
 * 光把 ttf 放进 resources 是没用的——CSS 里写了家族名也匹配不到，会静默回退到系统字体。
 *
 * <p><b>家族名 ≠ 文件名：</b>当前用的 {@code AlimamaFangYuanTiVF-Thin-2.ttf}，
 * JavaFX 报出的家族名是英文名 {@code Alimama FangYuanTi VF}
 * （**不是**中文名 {@code 阿里妈妈方圆体 VF}，也不是文件名）。CSS 必须写 JavaFX 报的那个名字。
 * 换字体时改 {@link #UI_FAMILY} 与 {@link #FONT_FILES}，并同步两个 css 里的 font-family。
 *
 * <p><b>覆盖情况（实测）：</b>阿里妈妈方圆体是简体中文字库，本项目界面真实用字
 * 1274 个里只缺 2 个（{@code U+3000} 全角空格、繁体 {@code 語}），按出现次数算缺 0.0% —— 基本无回退。
 * 这比之前的繁体粉圆体（缺 24%）和日文 GenJyuu（缺 22%）好得多。
 *
 * <p><b>⚠ 字重：这是可变字体（VF），JavaFX 17 不支持选取变体轴</b>，
 * 只会渲染 fvar 里的「默认实例」。该文件 wght 轴默认值是 700、BEVL 轴默认 100，
 * 所以实际渲染的是「Bold-Round」一档 —— 实测墨量比 0.2498，比微软雅黑 Bold（0.2361）还粗。
 * 请求 THIN/LIGHT/NORMAL/BOLD 四种字重得到的渲染结果完全相同。
 * 想要常规字重需要先用 fonttools 把它实例化成静态字体：
 * <pre>python -m pip install fonttools
 * fonttools varLib.instancer AlimamaFangYuanTiVF-Thin-2.ttf wght=400 BEVL=100 \
 *     -o AlimamaFangYuanTi-Regular.ttf</pre>
 * 然后把生成的文件放进 {@code src/main/resources/ttf/} 并改 {@link #FONT_FILES}。
 */
public final class AppFonts {

    private static final Logger log = LoggerFactory.getLogger(AppFonts.class);

    /**
     * 界面主字体家族名 —— 必须与 JavaFX {@code Font.loadFont(...).getFamily()} 报出的名字完全一致，
     * 也必须与 {@code style.css} / {@code starlight-splash.css} 里 font-family 的第一项一致。
     */
    public static final String UI_FAMILY = "Alimama FangYuanTi VF";

    /** 需要注册的字体资源（classpath 路径） */
    private static final String[] FONT_FILES = {
            "/ttf/AlimamaFangYuanTiVF-Thin-2.ttf",
    };

    /** 幂等标记：重复调用只加载一次 */
    private static boolean installed;

    private AppFonts() {
    }

    /**
     * 注册 UI 字体。幂等；必须在创建任何 Scene 之前、且在 JavaFX 应用线程上调用
     * （{@link Font#loadFont} 要求 JavaFX 运行时已初始化，{@code Application.start()} 里正合适）。
     *
     * <p>加载失败不抛异常：界面回退系统字体，只记一条告警，绝不能因为字体问题起不来。
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        for (String path : FONT_FILES) {
            try (InputStream in = AppFonts.class.getResourceAsStream(path)) {
                if (in == null) {
                    log.warn("UI 字体资源缺失，界面将回退系统字体: {}", path);
                    continue;
                }
                Font font = Font.loadFont(in, 12);
                if (font == null) {
                    log.warn("UI 字体加载失败（文件损坏或格式不支持），界面将回退系统字体: {}", path);
                } else {
                    log.info("UI 字体已注册: {} -> 家族名 [{}] 样式 [{}]",
                            path, font.getFamily(), font.getStyle());
                }
            } catch (Exception e) {
                log.warn("UI 字体加载异常，界面将回退系统字体: {}", path, e);
            }
        }

        // 家族名没登记成功时明确告警：否则 CSS 静默回退，界面看着「字体没生效」却查不出原因
        if (!Font.getFamilies().contains(UI_FAMILY)) {
            log.warn("UI 字体家族 [{}] 不在 JavaFX 字体表中，CSS 将回退系统字体"
                    + "（检查 pom.xml 的 resources includes 是否包含 **/*.ttf）", UI_FAMILY);
        }
    }
}

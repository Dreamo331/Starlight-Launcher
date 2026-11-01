package com.example.starlight.newui.glass;

import com.example.starlight.newui.ui.GlassFeature;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

/**
 * 一块「通透模式」卡片：把参考实现里 {@code GlassPane} 的那层玻璃，装进启动器既有的卡片节点。
 *
 * <p>为什么不包一层新的容器：启动器里很多页面先 {@code PageKit.settingsCard(...)} 拿到卡片，
 * 随后再往卡片上挂控件（{@code card.getChildren().add(...)}）。如果这里把卡片包进 StackPane，
 * 那些调用点全部要改，而且改完拿到的就不是原来那张卡片了。所以玻璃层是卡片内部的一枚
 * <b>不参与布局</b>的 ImageView（{@code managed=false}）：HBox / VBox 排版时会跳过它，
 * 但渲染时它按子节点顺序画在最底下，正好当卡片的背景。</p>
 *
 * <p>卡片自身的 CSS 背景由 {@code .glass-card} 让出来（见 style.css），文字与控件都保持原样，
 * 所以关掉开关、退回原方法时外观与改动前完全一致。</p>
 */
final class GlassSurface {

    private final Pane card;
    private final GlassParams params = new GlassParams();
    private final GlassRenderer renderer = new GlassRenderer();
    private final ImageView view = new ImageView();

    private WritableImage buffer;
    private double deviceScale = 1;

    /** 需要重画（悬停、尺寸、主题变化都会置位） */
    private boolean dirty = true;
    /** 高对比度模式下退回普通卡片：摘掉 glass-card，玻璃层隐藏 */
    private boolean plain;
    private boolean disposed;

    private double lastX = Double.NaN;
    private double lastY = Double.NaN;
    private double lastW = -1;
    private double lastH = -1;
    private double lastOpacity = -1;
    private boolean lastDark;

    GlassSurface(Pane card) {
        this.card = card;
        card.getStyleClass().add("glass-card");

        view.setManaged(false);
        view.setMouseTransparent(true);
        view.setSmooth(true);
        view.setPreserveRatio(false);
        view.setVisible(false);
        // 玻璃层必须和卡片严丝合缝。渲染缓冲四周带 MARGIN 的余量（投影 + 边缘采样），
        // 直接整张画会向卡片外多探 24px：设置页会被撑高 24px、撑宽 48px（滚动条、留白全跟着歪），
        // 所以这里用 viewport 把余量裁掉，只显示卡片本体那一块。
        view.setViewport(new Rectangle2D(0, 0, 1, 1));
        // 画在最底层：同层子节点按顺序绘制，文字与控件仍在玻璃之上
        card.getChildren().add(0, view);

        // 悬停只提亮一点（渲染一遍即可，不必重拍背景），与原本 :hover 上浮反馈对应
        card.hoverProperty().addListener((o, a, b) -> {
            if (disposed) {
                return;
            }
            dirty = true;
            GlassCards.wake();
            GlassCards.requestRenderOnly();
        });
        card.widthProperty().addListener((o, a, b) -> {
            dirty = true;
            layoutView();
            GlassCards.wake();
        });
        card.heightProperty().addListener((o, a, b) -> {
            dirty = true;
            layoutView();
            GlassCards.wake();
        });
    }

    Pane card() {
        return card;
    }

    boolean isDisposed() {
        return disposed;
    }

    /** 卡片在屏幕上的「有效不透明度」：页面切页淡入淡出期间小于 1，这时不能拍背景。 */
    double effectiveOpacity() {
        double o = 1;
        for (Node n = card; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return 0;
            }
            o *= n.getOpacity();
            if (o < 0.001) {
                return 0;
            }
        }
        return o;
    }

    /**
     * 只看祖先链（不含卡片自己）的不透明度。
     *
     * <p>拍背景时整张卡片会被藏起来，所以卡片自己的 opacity 不影响快照；只有页面级淡入
     * （新页面 {@code setOpacity(0)} → 淡到 1）会把整页拍成半透明的灰，那才是要等的。</p>
     */
    double ancestorOpacity() {
        double o = 1;
        for (Node n = card.getParent(); n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return 0;
            }
            o *= n.getOpacity();
            if (o < 0.001) {
                return 0;
            }
        }
        return o;
    }

    /**
     * 记下「这一帧卡片在哪、多大、淡到几分」，返回是否需要重拍背景。
     *
     * <p>每帧都会更新记录值（即使不重拍），所以淡入结束、滚动停止这类「最后一次变化」
     * 一定会被识别成一次变化，由调用方安排一次刷新。</p>
     */
    boolean detectChange(boolean dark) {
        if (disposed) {
            return false;
        }
        boolean changed = false;
        double opacity = effectiveOpacity();
        if (Math.abs(opacity - lastOpacity) > 0.001) {
            lastOpacity = opacity;
            changed = true;
        }
        Point2D p = card.localToScene(0, 0);
        if (p != null) {
            if (Math.abs(p.getX() - lastX) > 0.5 || Math.abs(p.getY() - lastY) > 0.5) {
                lastX = p.getX();
                lastY = p.getY();
                changed = true;
            }
        }
        double w = card.getWidth();
        double h = card.getHeight();
        if (Math.abs(w - lastW) > 0.5 || Math.abs(h - lastH) > 0.5) {
            lastW = w;
            lastH = h;
            changed = true;
        }
        if (dark != lastDark) {
            lastDark = dark;
            dirty = true;
            changed = true;
        }
        return changed;
    }

    /**
     * 高对比度模式优先于通透模式：对比度是可用性红线，玻璃的半透明背景会让文字对比度掉下去。
     * 这里只在样式类上切换，卡片本身和控件完全不重建。
     */
    void setPlain(boolean value) {
        if (plain == value) {
            return;
        }
        plain = value;
        if (value) {
            card.getStyleClass().remove("glass-card");
            view.setVisible(false);
        } else if (!card.getStyleClass().contains("glass-card")) {
            card.getStyleClass().add("glass-card");
        }
        dirty = true;
    }

    boolean isPlain() {
        return plain;
    }

    /** 用当前背景重画这一块玻璃。backdrop 必须是刚拍好的那一张。 */
    void render(NodeSnapshotBackdrop backdrop, boolean dark) {
        if (disposed || plain) {
            return;
        }
        double w = Math.round(card.getWidth());
        double h = Math.round(card.getHeight());
        if (w < 8 || h < 8) {
            return;
        }
        Point2D origin = card.localToScene(0, 0);
        if (origin == null) {
            return;
        }
        deviceScale = Math.max(0.5, Math.min(backdrop.scale(), GlassFeature.MAX_RENDER_SCALE));
        applyLook(dark);

        Field field = backdrop.blurred(params.blur);
        if (field == null || field.pixels == null) {
            return;
        }
        int[] pixels = renderer.render(field, field, null,
                origin.getX() - backdrop.originX(),
                origin.getY() - backdrop.originY(),
                (int) w, (int) h, deviceScale, params);
        if (pixels == null) {
            return;
        }
        int bw = renderer.bufferWidth();
        int bh = renderer.bufferHeight();
        if (buffer == null || (int) buffer.getWidth() != bw || (int) buffer.getHeight() != bh) {
            buffer = new WritableImage(bw, bh);
            view.setImage(buffer);
        }
        buffer.getPixelWriter().setPixels(0, 0, bw, bh, PixelFormat.getIntArgbInstance(), pixels, 0, bw);
        dirty = false;
        view.setVisible(true);
        layoutView();
    }

    /**
     * 玻璃层贴住卡片左上角，并且只显示「卡片本体」那一块：
     * 缓冲四周的 MARGIN（投影 / 边缘采样余量）被裁掉，ImageView 的布局尺寸就等于卡片本身，
     * 不会再往页面上多探一圈。卡片尺寸变化时先用卡片大小顶着，等下一次重画再校准。
     */
    private void layoutView() {
        if (disposed) {
            return;
        }
        int bw = renderer.bufferWidth();
        int bh = renderer.bufferHeight();
        if (buffer != null && bw > 0 && bh > 0) {
            int margin = (int) Math.round(GlassRenderer.MARGIN * deviceScale);
            int vw = Math.max(1, bw - margin * 2);
            int vh = Math.max(1, bh - margin * 2);
            view.setViewport(new Rectangle2D(margin, margin, vw, vh));
        }
        view.relocate(0, 0);
        double cw = card.getWidth();
        double ch = card.getHeight();
        if (cw > 0 && ch > 0) {
            view.setFitWidth(cw);
            view.setFitHeight(ch);
        }
    }

    /**
     * 观感参数：参考卡片（{@code Panels.referenceCard}）那套数字，按启动器卡片的尺寸与主题重调。
     *
     * <ul>
     *   <li>{@code refraction(0)} + {@code chroma(0)}：参考实现明确要求两者同时归零，
     *       否则边缘会变成「另一张图糊上来」而不是干净玻璃；</li>
     *   <li>{@code turbulence} 关闭：它是整套渲染里最贵的一项（每像素 3 层噪声），
     *       卡片数量多，留着会让一次刷新多花几十毫秒；</li>
     *   <li>{@code edgeWidth} 跟着卡片高度收：40px 的宽边缘带是按 168px 高的示范卡片定的，
     *       直接套到 60px 高的设置卡片上会糊成一片。</li>
     * </ul>
     */
    private void applyLook(boolean dark) {
        boolean hovered = card.isHover();
        // 所有可调数字都集中在 GlassFeature 一个文件里，这里只负责读
        params.radius(GlassFeature.RADIUS);
        params.blur(GlassFeature.BLUR);
        params.refraction(0);
        params.chroma(0);
        params.saturation = dark ? GlassFeature.SATURATION_DARK : GlassFeature.SATURATION_LIGHT;
        params.edgeWidth = Math.max(10, Math.min(26, card.getHeight() * GlassFeature.EDGE_WIDTH_RATIO));
        params.specular = GlassFeature.SPECULAR;
        params.turbulence = 0;
        params.edgeShade = dark ? 0.28 : 0.20;
        params.tint = dark ? 0x0B1220 : 0xFFFFFF;
        // 着色浓度：注意渲染器内部还会乘一个「主体/边缘」系数（玻璃中心只取 60%，越靠边越足），
        // 所以 GlassFeature 里填的数字要按中心效果倒推。参考卡片写 10%，那是压在照片上的示范值；
        // 启动器背景多为平缓渐变、卡片里还压着文字，太淡会让卡片几乎消失（只剩一道描边）。
        params.tintAlpha = (dark ? GlassFeature.TINT_ALPHA_DARK : GlassFeature.TINT_ALPHA_LIGHT)
                + (hovered ? GlassFeature.HOVER_TINT_BOOST : 0);
        params.brightness = dark ? GlassFeature.BRIGHTNESS_DARK : GlassFeature.BRIGHTNESS_LIGHT;
        params.shadowAlpha = hovered ? 0.26 : 0.18;
        params.shadowBlur = hovered ? 26 : 20;
        params.shadowDrop = hovered ? 7 : 4;
    }

    boolean isDirty() {
        return dirty;
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        card.getStyleClass().remove("glass-card");
        try {
            card.getChildren().remove(view);
        } catch (RuntimeException ignored) {
            // 卡片已经被移除或重建：什么都不用做
        }
        view.setImage(null);
        buffer = null;
    }
}

package com.example.starlight.newui;

import com.example.starlight.Controllers.PageView;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.util.Duration;

/**
 * 启动动画页面 —— Starlight 星轨闪屏
 * （核心动画移植自子项目「启动动画」：FXML 结构 + CSS 样式 + 控制器逐帧 Canvas 绘制）
 */
public class SplashView implements PageView {

    /** 闪屏窗口尺寸（与 StarlightSplash.fxml / 控制器常量保持一致） */
    public static final double SPLASH_WIDTH = 520;
    public static final double SPLASH_HEIGHT = 325;

    private final Parent root;
    private final StarlightSplashController controller;
    private Timeline finishTimeline;

    public SplashView() {
        try {
            FXMLLoader loader = new FXMLLoader(
                    SplashView.class.getResource("/fxml/StarlightSplash.fxml"));
            root = loader.load();
            controller = loader.getController();
        } catch (Exception e) {
            throw new IllegalStateException("加载启动动画 FXML 失败", e);
        }

        // 版权行用真实的年份 / 名称 / 版本号，不写死
        controller.setFooterText("© " + AppConfig.COPYRIGHT_YEAR + " "
                + AppConfig.APP_DISPLAY_NAME + "  ·  " + AppConfig.DISPLAY_VERSION);

        var cssUrl = SplashView.class.getResource("/fxml/css/starlight-splash.css");
        if (cssUrl != null) {
            root.getStylesheets().add(cssUrl.toExternalForm());
        }
    }

    @Override
    public Node getView() {
        return root;
    }

    /**
     * 设置动画完成后回调（退场动画播完时触发）
     */
    public void setOnFinished(Runnable onFinished) {
        controller.setOnFinished(onFinished);
    }

    /**
     * 播放启动动画并在指定时长后请求收尾（退场 → 完成回调）。
     * <p>动画本体由 {@link StarlightSplashController} 逐帧驱动；本方法只负责在
     * 后台初始化结束后请求收尾。若初始化很快（剩余时长仍早于进度条走满），
     * 收尾会等进度条走满再开始——闪屏完整呈现「进度填充 → 准备就绪 → 退场」。
     *
     * @param duration 收尾前至少再停留的时长（用于保证闪屏最低播放时长）
     */
    public void playAnimation(Duration duration) {
        // 防御：时长为 0（或负值）时没有可等待的时长，直接请求收尾进入主界面
        if (duration == null || duration.lessThanOrEqualTo(Duration.ZERO)) {
            controller.requestFinish();
            return;
        }
        if (finishTimeline != null) {
            finishTimeline.stop();
        }
        finishTimeline = new Timeline(new KeyFrame(duration, e -> controller.requestFinish()));
        finishTimeline.play();
    }
}

package com.example.starlight.newui;

/**
 * 应用全局配置常量 —— 版本号、显示名称等。
 * 以后修改版本号等配置只需改此文件。
 */
public final class AppConfig {

    /* 应用版本号（不含 v 前缀） */
    /**
     * 常见版本号列表
     * SNAPSHOT: 开发快照版本
     * ALPHA: 内部测试版本
     * BETA: 公测版本
     * RC: 发布候选版本
     * RELEASE: 正式发布版本
     * 版本号格式: MAJOR.MINOR.PATCH[-QUALIFIER]
     * 例如: 1.0.0-SNAPSHOT, 1.0.0-ALPHA, 1.0.0-BETA, 1.0.0-RC, 1.0.0-RELEASE
     * 其中 MAJOR、 MINOR、PATCH 为数字，QUALIFIER 为 SNAPSHOT、ALPHA、BETA、RC、RELEASE 中的一个
     * 版本号规则: MAJOR.MINOR.PATCH
     * MAJOR: 主版本号，表示重大更新，可能包含不兼容的 API 变更
     * MINOR: 次版本号，表示新增功能，向下兼容的 API 变更
     * PATCH: 修订号，表示向下兼容的问题修复
     * QUALIFIER: 版本后缀，表示版本的稳定性和发布阶段
     * SNAPSHOT: 开发快照版本，可能不稳定，仅供开发和测试使用
     * ALPHA: 内部测试版本，可能包含新功能和实验性特性，供内部测试使用
     * BETA: 公测版本，可能包含新功能和实验性特性，供公众测试使用
     * RC: 发布候选版本，接近正式发布，供公众测试使用
     * RELEASE: 正式发布版本，稳定可靠，供公众使用
     */
    public static final String APP_VERSION = "2.0.0-RC";

    /** 界面显示的版本号（含 v 前缀） */
    public static final String DISPLAY_VERSION = "v" + APP_VERSION;

    /** 网络请求 User-Agent（含版本号，所有外部请求统一使用） */
    public static final String USER_AGENT = "StarlightLauncher/" + APP_VERSION;

    /** 应用显示名称 */
    public static final String APP_DISPLAY_NAME = "Starlight Launcher";

    /** 版权年份 */
    public static final String COPYRIGHT_YEAR = "2026";

    // ===== 运行开关（开发者选项页可实时切换） =====

    /**
     * 调试模式：开启后记录底层页面刷新与网络数据包收发详情
     * （数据包大小、URL、请求/响应内容等），在开发者选项页实时显示。
     */
    public static volatile boolean DEBUG_MODE = false;

    /**
     * 中文日志开关：true=调试日志以中文输出，false=英文输出。
     */
    public static volatile boolean CHINESE_LOG = true;

    private AppConfig() {}
}

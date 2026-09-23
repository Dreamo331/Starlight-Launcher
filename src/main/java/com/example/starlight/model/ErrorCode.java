/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/**
 * 游戏启动错误码分类
 */
public enum ErrorCode {

    E0001("未知错误", "无法确定具体原因的启动失败"),

    // === JVM 相关 (E1xxx) ===
    E1001("JVM创建失败", "Java虚拟机无法创建或启动"),
    E1002("内存不足", "Java堆内存分配失败，可尝试增加最大内存"),
    E1003("无效JVM参数", "JVM启动参数中存在无法识别的选项或格式错误"),

    // === 模组相关 (E2xxx) ===
    E2001("模组加载冲突", "多个模组之间存在冲突，无法同时加载"),
    E2002("模组缺失依赖", "加载的模组缺少必要的依赖模组"),
    E2003("模组版本不兼容", "模组版本与当前游戏版本不兼容"),

    // === 文件/资源相关 (E3xxx) ===
    E3001("游戏文件损坏", "必要的游戏文件缺失或损坏"),
    E3002("资源文件缺失", "资源包或资源文件无法加载"),

    // === Java环境相关 (E4xxx) ===
    E4001("Java版本不兼容", "当前Java版本不符合游戏要求"),
    E4002("Java位宽错误", "需要32位Java但使用了64位（或反之）"),

    // === 图形/渲染相关 (E5xxx) ===
    E5001("显卡驱动错误", "显卡驱动异常或OpenGL版本不满足要求"),
    E5002("渲染初始化失败", "游戏渲染管线初始化失败"),

    // === 启动参数相关 (E6xxx) ===
    E6001("类路径错误", "游戏库文件类路径异常"),
    E6002("原生库加载失败", "操作系统原生库无法正确加载");

    private final String displayName;
    private final String description;

    ErrorCode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() {
        return name();
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据错误文本智能匹配错误码
     */
    public static ErrorCode detectFromText(String errorText) {
        if (errorText == null || errorText.isBlank()) return E0001;

        String text = errorText.toLowerCase();

        // JVM 相关
        // 无效JVM参数优先检测（避免被其他关键词误匹配）
        if (text.contains("unrecognized option")
                || text.contains("unrecognized jvm")
                || text.contains("invalid argument")
                || text.contains("invalid flag")
                || text.contains("error: could not find")) {
            return E1003;
        }
        if (text.contains("could not create the java virtual machine")
                || text.contains("unable to start jvm")
                || text.contains("jvm creation failed")
                || text.contains("could not reserve enough space")
                || text.contains("cannot create jvm")) {
            return E1001;
        }
        if (text.contains("outofmemoryerror")
                || text.contains("out of memory")
                || text.contains("java.lang.outofmemoryerror")
                || text.contains("gc overhead limit")) {
            return E1002;
        }

        // 模组相关
        if (text.contains("mod resolution conflict")
                || text.contains("mod冲突")
                || text.contains("incompatible mod")
                || (text.contains("conflict") && text.contains("mod"))) {
            return E2001;
        }
        if (text.contains("missing mod")
                || text.contains("missing dependency")
                || (text.contains("missing") && text.contains("mod"))) {
            return E2002;
        }
        if (text.contains("mod version")
                || text.contains("incompatible")
                || (text.contains("version") && text.contains("mod"))) {
            return E2003;
        }

        // 文件相关
        if (text.contains("filenotfoundexception")
                || text.contains("file not found")
                || text.contains("no such file")
                || text.contains("cannot find file")) {
            return E3001;
        }
        if (text.contains("resourcepack")
                || text.contains("pack.mcmeta")
                || text.contains("failed to load resource")) {
            return E3002;
        }

        // Java 环境
        if (text.contains("unsupportedclassversionerror")
                || text.contains("java version")
                || text.contains("unsupported major.minor version")
                || text.contains("class file has wrong version")) {
            return E4001;
        }
        if (text.contains("32-bit") || text.contains("64-bit")
                || (text.contains("jvm") && (text.contains("32") || text.contains("64")))) {
            return E4002;
        }

        // 图形/渲染
        if (text.contains("opengl")
                || text.contains("glfw")
                || text.contains("pixel format")
                || text.contains("gpu")
                || text.contains("graphics card")
                || text.contains("driver")
                || text.contains("wglinit")) {
            return E5001;
        }
        if (text.contains("rendering")
                || text.contains("gl error")
                || text.contains("shader")
                || text.contains("framebuffer")) {
            return E5002;
        }

        // 启动参数
        if (text.contains("classpath")
                || text.contains("class path")
                || text.contains("noclassdeffounderror")
                || text.contains("classnotfoundexception")) {
            return E6001;
        }
        if (text.contains("unsatisfiedlinkerror")
                || text.contains("could not find library")
                || text.contains("native library")
                || text.contains("failed to load native")) {
            return E6002;
        }

        return E0001;
    }
}

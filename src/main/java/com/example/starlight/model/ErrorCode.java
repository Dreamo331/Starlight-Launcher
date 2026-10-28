/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;


import com.example.starlight.util.I18N;
/**
 * 游戏启动错误码分类
 */
public enum ErrorCode {

    E0001(I18N.get("model.t0001"), I18N.get("model.t0002")),

    // JVM 相关 (E1xxx) //
    E1001(I18N.get("model.t0003"), I18N.get("model.t0004")),
    E1002(I18N.get("model.t0005"), I18N.get("model.t0006")),
    E1003(I18N.get("model.t0007"), I18N.get("model.t0008")),

    // 模组相关 (E2xxx) //
    E2001(I18N.get("model.t0009"), I18N.get("model.t0010")),
    E2002(I18N.get("model.t0011"), I18N.get("model.t0012")),
    E2003(I18N.get("model.t0013"), I18N.get("model.t0014")),

    // 文件/资源相关 (E3xxx) //
    E3001(I18N.get("model.t0015"), I18N.get("model.t0016")),
    E3002(I18N.get("model.t0017"), I18N.get("model.t0018")),

    // Java环境相关 (E4xxx) //
    E4001(I18N.get("model.t0019"), I18N.get("model.t0020")),
    E4002(I18N.get("model.t0021"), I18N.get("model.t0022")),

    // 图形/渲染相关 (E5xxx) //
    E5001(I18N.get("model.t0023"), I18N.get("model.t0024")),
    E5002(I18N.get("model.t0025"), I18N.get("model.t0026")),

    // 启动参数相关 (E6xxx) //
    E6001(I18N.get("model.t0027"), I18N.get("model.t0028")),
    E6002(I18N.get("model.t0029"), I18N.get("model.t0030")),
    E6003(I18N.get("model.t0031"), I18N.get("model.t0032"));

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

        // 原生崩溃（访问冲突 / 非法指令）：Java 层不会抛异常，证据只在退出码与 hs_err 日志里。
        // 放在图形 / 类路径判断之前：这类文本里常同时出现驱动名、dll 名，先判原生崩溃才不会误报成
        // 「显卡驱动错误」或「类路径错误」
        if (text.contains("0xc0000005")
                || text.contains("0xc000001d")
                || text.contains("0xc00000fd")
                || text.contains("0xc0000409")
                || text.contains("exception_access_violation")
                || text.contains("access violation")
                || text.contains("访问冲突")
                || text.contains("problematic frame")
                || text.contains("sigsegv")) {
            return E6003;
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
        // 类路径缺失优先于图形检查：NoClassDefFoundError: org/lwjgl/glfw/GLFW 文本里含 "glfw"，
        // 若先走下面的 glfw 分支会被误判成 E5001 显卡驱动错误，掩盖真实原因是启动库/类路径缺类
        if (text.contains("noclassdeffounderror")
                || text.contains("classnotfoundexception")) {
            return E6001;
        }
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

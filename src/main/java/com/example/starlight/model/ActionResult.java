/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/**
 * 通用操作结果包装
 */
public class ActionResult {
    public final boolean success;
    public final String message;
    public final Object data;

    public ActionResult(boolean success, String message) {
        this(success, message, null);
    }

    public ActionResult(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static ActionResult ok(String msg) {
        return new ActionResult(true, msg);
    }

    public static ActionResult ok(String msg, Object data) {
        return new ActionResult(true, msg, data);
    }

    public static ActionResult fail(String msg) {
        return new ActionResult(false, msg);
    }
}

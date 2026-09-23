/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/**
 * 通用回调接口
 */
public final class CallbackInterfaces {

    /** 通用进度回调 0-100 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent, String message);
    }

    /** 通用结果回调 */
    public interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    private CallbackInterfaces() {}
}

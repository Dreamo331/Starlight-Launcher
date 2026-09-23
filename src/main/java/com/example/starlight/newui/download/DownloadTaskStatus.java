package com.example.starlight.newui.download;

/** 下载任务状态（从 LauncherView 抽离，语义不变） */
public enum DownloadTaskStatus {
    /** 进行中（右下角悬浮按钮在此期间显示） */
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

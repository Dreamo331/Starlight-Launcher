package com.example.starlight.modpack;

/** 整合包内一个待下载的文件：目标相对路径 + 下载直链 */
public record PackFile(String relativePath, String url) {
}

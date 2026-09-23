package com.example.starlight.newui.download;

import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.ModsApi.RemoteModRepository;

/**
 * 一条搜索结果 + 它来自哪个仓库。
 * <p>「来源=全部」会同时查询 Modrinth 与 CurseForge，合并后必须记住每条来自哪里，
 * 否则点「详情」「安装」时会去错误的仓库查版本。
 *
 * <p>从 LauncherView 抽离（原为内部 record，字段与原定义一致）。
 */
public record ModHit(RemoteMod mod, RemoteModRepository repo) {
}

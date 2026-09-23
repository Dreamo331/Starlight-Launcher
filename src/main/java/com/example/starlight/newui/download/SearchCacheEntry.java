package com.example.starlight.newui.download;

import java.util.List;

/** 一次搜索的缓存内容：命中列表 + 总页数（从 LauncherView 抽离，字段与原定义一致） */
public record SearchCacheEntry(List<ModHit> hits, int totalPages) {
}

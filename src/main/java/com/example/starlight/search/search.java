/**
 * (C) Copyright 2026 Starlight. All rights reserved.
 * 搜索方法
 * 搜索引擎打算使用Bing
 * https://cn.bing.com/search?q= + 用户输入的数据UTF-8编码后的字符串
 */
package com.example.starlight.search;

/**
 * 旧入口，保留给可能存在的调用方；实际逻辑已挪到 {@link BingSearchUtil}（同一段实现，别再写第二份）。
 *
 * @deprecated 用 {@link BingSearchUtil#search(String)}。
 */
@Deprecated
public final class search {
    private search() {
    }

    /**
     * Open the default browser to search the given question on Bing.
     *
     * @deprecated use {@link BingSearchUtil#search(String)}
     */
    @Deprecated
    public static void search(String question) {
        BingSearchUtil.search(question);
    }
}


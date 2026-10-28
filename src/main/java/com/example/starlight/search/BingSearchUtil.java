package com.example.starlight.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Bing 搜索：把用户输入拼成结果页地址，再交给系统默认浏览器打开。
 *
 * <p><b>只拉起浏览器，不在启动器内发 HTTP 请求。</b>本地拼 URL 属于用户自己的真实搜索行为；
 * 若改成后端抓取 HTML，一来要处理 Bing 的反爬（验证码 / 临时封 IP），二来等于替用户做无头请求，
 * 量大时会被判定为爬虫 —— 这条路刻意不走。
 *
 * <p>域名用 {@code cn.bing.com}：国内网络直连国际版 {@code www.bing.com} 常常很慢或被重定向，
 * 国内版能直接出结果页。{@code ensearch=0} 固定走中文版界面，避免被跳去国际版。
 *
 * <p>编码口径：{@link URLEncoder#encode} 按表单规则会把空格编成 {@code +}，Bing 认这个；
 * 这里再换成 {@code %20}，让 URL 本身也符合一般意义上的路径规范。
 * 用户输入里自带的加号（如 {@code C++}）会被正常编成 {@code %2B}，不会被误当成空格。
 *
 * <p>打开浏览器优先走 {@link com.example.starlight.util.DesktopOpener}：Windows 下它用
 * {@code rundll32 url.dll,FileProtocolHandler}，比 {@code Desktop.browse} 更少受
 * 「当前系统不支持 BROWSE」这类环境影响。
 */
public final class BingSearchUtil {

    /** 国内版地址；{@code ensearch=0} 强制中文版界面 */
    private static final String ENDPOINT = "https://cn.bing.com/search?q=";
    private static final String SUFFIX = "&ensearch=0";

    private BingSearchUtil() {
    }

    /**
     * 拼出 Bing 结果页地址（不做任何 IO，便于自检与测试）。
     *
     * @param userInput 用户输入的关键词；空白返回 {@code null}
     */
    public static String buildUrl(String userInput) {
        if (userInput == null) return null;
        String keyword = userInput.trim();
        if (keyword.isEmpty()) return null;
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8).replace("+", "%20");
        return ENDPOINT + encoded + SUFFIX;
    }

    /**
     * 用系统默认浏览器搜索该关键词。
     *
     * @param userInput 用户输入的关键词；空白直接忽略
     * @return 浏览器已拉起返回 {@code true}；空关键词或拉起失败返回 {@code false}
     */
    public static boolean search(String userInput) {
        String url = buildUrl(userInput);
        if (url == null) return false;
        return com.example.starlight.util.DesktopOpener.openUrl(url);
    }
}

package com.example.starlight.newui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown → HTML 渲染器（供模组详情页用 WebView 展示描述正文）。
 *
 * <p>对应 VersePc2 详情页里 {@code marked.min.js} 的角色：那边把 Modrinth 的 Markdown 正文
 * 转成 HTML 再插进 DOM，这里用纯 Java 实现同一件事，避免为一个页面引入完整的 Markdown 库。
 *
 * <p>支持范围（覆盖 Modrinth / CurseForge 正文中的绝大多数写法）：
 * <ul>
 *   <li>标题 {@code #} ~ {@code ######}、水平线 {@code ---}；</li>
 *   <li>无序列表 {@code - * +}、有序列表 {@code 1.}；</li>
 *   <li>引用 {@code >}、围栏代码块 {@code ```}、行内代码 {@code `}；</li>
 *   <li>粗体、斜体、删除线、链接、图片；</li>
 *   <li>正文里直接写的常用 HTML 标签（{@code <details>}、{@code <img>}、{@code <center>} 等）
 *       按白名单放行，其余标签一律转义显示，避免正文里的脚本被当成页面执行。</li>
 * </ul>
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    /** 白名单放行的 HTML 标签名（正文里直接写的这些标签会被当成标签而不是文字） */
    private static final String ALLOWED_TAGS =
            "br|hr|b|i|u|s|strong|em|code|pre|p|div|span|center|small|big|sub|sup"
                    + "|h[1-6]|ul|ol|li|dl|dt|dd|blockquote|details|summary"
                    + "|table|thead|tbody|tfoot|tr|td|th|img|a|font|kbd|mark";

    /**
     * 匹配「已被转义的、标签名在白名单里的」开闭标签，用于把标签还原回 HTML。
     * <p>属性段允许出现 {@code &}：转义后的属性值里引号是 {@code &quot;}，
     * 若把 {@code &} 排除在外，{@code <img src="...">} 这类带属性的标签就永远还原不回来。
     */
    private static final Pattern ESCAPED_ALLOWED_TAG = Pattern.compile(
            "&lt;(/?)(?:" + ALLOWED_TAGS + ")((?:\\s[^<>]*?)?)\\s*(/?)&gt;",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([A-Za-z0-9_+-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern UL_ITEM = Pattern.compile("^\\s{0,3}[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^\\s{0,3}(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)(?:\\s+\"([^\"]*)\")?\\)");
    private static final Pattern BOLD_STAR = Pattern.compile("\\*\\*(.+?)\\*\\*", Pattern.DOTALL);
    private static final Pattern BOLD_UNDER = Pattern.compile("__(.+?)__", Pattern.DOTALL);
    private static final Pattern ITALIC_STAR = Pattern.compile("(?<!\\*)\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)", Pattern.DOTALL);
    private static final Pattern ITALIC_UNDER = Pattern.compile("(?<!_)_(?!\\s)(.+?)(?<!\\s)_(?!_)", Pattern.DOTALL);
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern AUTOLINK = Pattern.compile("(?<![\\w\"'=])(https?://[^\\s<>\"）)]+)");

    /**
     * 把 Markdown 渲染为完整的 HTML 文档（含内联样式，供 {@code WebView.getEngine().loadContent} 使用）。
     *
     * @param markdown 正文
     * @param dark     true 使用深色配色（跟随启动器深色主题）
     */
    public static String toDocument(String markdown, boolean dark) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<style>" + css(dark) + "</style></head><body>"
                + toHtml(markdown)
                + "</body></html>";
    }

    /**
     * 把一段<b>已经是 HTML</b> 的正文（CurseForge 的 {@code description} 字段）包成完整文档。
     * <p>这里仍然过一遍白名单：CurseForge 的正文里混有 {@code <script>} 与内联事件的风险，
     * 直接塞进 WebView 等于在启动器里执行远端脚本，因此只保留安全的展示型标签。
     */
    public static String htmlDocument(String htmlBody, boolean dark) {
        String safe = htmlBody == null ? "" : htmlBody;
        // 先整体转义，再把白名单标签还原回来，等价于「只允许展示型标签」
        safe = restoreAllowedTags(escape(safe));
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<style>" + css(dark) + "</style></head><body>"
                + safe
                + "</body></html>";
    }

    /** 把 Markdown 渲染为 HTML 片段 */
    public static String toHtml(String markdown) {        if (markdown == null || markdown.isBlank()) {
            return "<p class=\"md-empty\">该资源没有提供详细描述。</p>";
        }
        // 统一换行符并去掉零宽字符（Modrinth 正文里偶尔混入）
        String text = markdown.replace("\r\n", "\n").replace("\r", "\n").replace("\u200b", "");

        StringBuilder out = new StringBuilder(text.length() + 512);
        String[] lines = text.split("\n", -1);

        boolean inFence = false;
        String fenceLang = "";
        StringBuilder fenceBuf = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;
        boolean inQuote = false;
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine;

            // ===== 围栏代码块：内部不做任何 Markdown 解析 =====
            Matcher fenceMatcher = FENCE.matcher(line);
            if (fenceMatcher.matches()) {
                if (!inFence) {
                    closeParagraph(out, paragraph);
                    closeLists(out, inUl, inOl);
                    inUl = false;
                    inOl = false;
                    inFence = true;
                    fenceLang = fenceMatcher.group(2);
                    fenceBuf.setLength(0);
                } else {
                    out.append("<pre class=\"md-code\"><code>")
                            .append(escape(fenceBuf.toString()))
                            .append("</code></pre>");
                    inFence = false;
                    fenceLang = "";
                }
                continue;
            }
            if (inFence) {
                if (fenceBuf.length() > 0) fenceBuf.append('\n');
                fenceBuf.append(line);
                continue;
            }

            // ===== 空行：结束当前段落/列表/引用 =====
            if (line.isBlank()) {
                closeParagraph(out, paragraph);
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                if (inQuote) {
                    out.append("</blockquote>");
                    inQuote = false;
                }
                continue;
            }

            // ===== 标题 =====
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                closeParagraph(out, paragraph);
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                int level = h.group(1).length();
                out.append("<h").append(level).append('>')
                        .append(inline(h.group(2)))
                        .append("</h").append(level).append('>');
                continue;
            }

            // ===== 水平线 =====
            if (HR.matcher(line).matches()) {
                closeParagraph(out, paragraph);
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                out.append("<hr>");
                continue;
            }

            // ===== 引用 =====
            Matcher q = QUOTE.matcher(line);
            if (q.matches()) {
                closeParagraph(out, paragraph);
                closeLists(out, inUl, inOl);
                inUl = false;
                inOl = false;
                if (!inQuote) {
                    out.append("<blockquote>");
                    inQuote = true;
                }
                out.append("<p>").append(inline(q.group(1))).append("</p>");
                continue;
            } else if (inQuote) {
                out.append("</blockquote>");
                inQuote = false;
            }

            // ===== 列表 =====
            Matcher ul = UL_ITEM.matcher(line);
            Matcher ol = OL_ITEM.matcher(line);
            if (ul.matches()) {
                closeParagraph(out, paragraph);
                if (inOl) {
                    out.append("</ol>");
                    inOl = false;
                }
                if (!inUl) {
                    out.append("<ul>");
                    inUl = true;
                }
                out.append("<li>").append(inline(ul.group(1))).append("</li>");
                continue;
            }
            if (ol.matches()) {
                closeParagraph(out, paragraph);
                if (inUl) {
                    out.append("</ul>");
                    inUl = false;
                }
                if (!inOl) {
                    out.append("<ol>");
                    inOl = true;
                }
                out.append("<li>").append(inline(ol.group(2))).append("</li>");
                continue;
            }
            closeLists(out, inUl, inOl);
            inUl = false;
            inOl = false;

            // ===== 普通段落：连续行拼成一段（Markdown 的软换行不产生 <br>） =====
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(line.trim());
        }

        // 收尾：处理未闭合的块
        if (inFence) {
            out.append("<pre class=\"md-code\"><code>")
                    .append(escape(fenceBuf.toString()))
                    .append("</code></pre>");
        }
        closeParagraph(out, paragraph);
        closeLists(out, inUl, inOl);
        if (inQuote) out.append("</blockquote>");
        return out.toString();
    }

    private static void closeParagraph(StringBuilder out, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        out.append("<p>").append(inline(paragraph.toString())).append("</p>");
        paragraph.setLength(0);
    }

    private static void closeLists(StringBuilder out, boolean inUl, boolean inOl) {
        if (inUl) out.append("</ul>");
        if (inOl) out.append("</ol>");
    }

    /**
     * 行内元素解析。
     * <p>顺序很关键：先还原白名单 HTML 标签、再转义剩余裸 {@code <}，最后才是 Markdown 强调语法，
     * 否则正文里的 {@code <img>} 会被当成裸标签转义掉、而代码里的 {@code *} 会被误当成斜体。
     */
    private static String inline(String text) {
        // ① 先把行内代码提取出来占位，避免其中的 * _ ` 被当成强调语法
        java.util.List<String> codes = new java.util.ArrayList<>();
        Matcher codeM = INLINE_CODE.matcher(text);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (codeM.find()) {
            withPlaceholders.append(text, last, codeM.start());
            withPlaceholders.append("\u0002").append(codes.size()).append("\u0003");
            codes.add("<code class=\"md-inline-code\">" + escape(codeM.group(1)) + "</code>");
            last = codeM.end();
        }
        withPlaceholders.append(text.substring(last));

        String s = escape(withPlaceholders.toString());
        // ② 还原白名单标签
        s = restoreAllowedTags(s);
        // ③ 图片 → 链接 → 自动链接
        s = replaceAll(IMAGE, s, m -> "<img class=\"md-img\" src=\"" + attr(m.group(2)) + "\" alt=\""
                + attr(m.group(1)) + "\" loading=\"lazy\">");
        s = replaceAll(LINK, s, m -> "<a href=\"" + attr(m.group(2)) + "\" target=\"_blank\">"
                + m.group(1) + "</a>");
        s = replaceAll(AUTOLINK, s, m -> "<a href=\"" + attr(m.group(1)) + "\" target=\"_blank\">"
                + m.group(1) + "</a>");
        // ④ 强调语法
        s = BOLD_STAR.matcher(s).replaceAll("<strong>$1</strong>");
        s = BOLD_UNDER.matcher(s).replaceAll("<strong>$1</strong>");
        s = STRIKE.matcher(s).replaceAll("<del>$1</del>");
        s = ITALIC_STAR.matcher(s).replaceAll("<em>$1</em>");
        s = ITALIC_UNDER.matcher(s).replaceAll("<em>$1</em>");
        // ⑤ 行尾两个空格 = 强制换行
        s = s.replace("  \n", "<br>");
        // ⑥ 填回行内代码
        for (int i = 0; i < codes.size(); i++) {
            s = s.replace("\u0002" + i + "\u0003", codes.get(i));
        }
        return s;
    }

    /** 把已转义的、标签名在白名单内的标签还原成真正的 HTML 标签 */
    private static String restoreAllowedTags(String escaped) {
        Matcher m = ESCAPED_ALLOWED_TAG.matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // group(2) 是属性段（已被转义）。这里把 &quot; 还原成真正的引号：
            // 一是属性值在 HTML 里本来就用引号界定，二是后续的行内自动链接检测
            // 依赖引号才能判断「这个 URL 已经在属性里了，不要再包一层 <a>」。
            String attrs = m.group(2) == null ? "" : m.group(2).replace("&quot;", "\"");
            String replacement = "<" + m.group(1) + tagNameOf(m.group()) + attrs + m.group(3) + ">";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 从被转义的标签串里取出标签名 */
    private static String tagNameOf(String escapedTag) {
        Matcher m = Pattern.compile("&lt;(/?)([A-Za-z][A-Za-z0-9]*)").matcher(escapedTag);
        return m.find() ? m.group(2).toLowerCase(java.util.Locale.ROOT) : "";
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replaceAll(Pattern p, String input, Replacer replacer) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** HTML 转义（正文中的裸标签、脚本等一律按文字显示） */
    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 属性值转义：在 {@link #escape} 的基础上挡掉 javascript: 伪协议 */
    private static String attr(String value) {
        if (value == null) return "";
        String v = value.trim();
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("data:text/html")) {
            return "#";
        }
        return escape(v);
    }

    /** 详情页正文样式：只描述正文块自身，颜色随启动器深浅主题切换 */
    private static String css(boolean dark) {
        String text = dark ? "#d7dce5" : "#333a45";
        String muted = dark ? "#8b94a5" : "#6b7280";
        String codeBg = dark ? "rgba(255,255,255,0.07)" : "rgba(0,0,0,0.05)";
        String border = dark ? "rgba(255,255,255,0.12)" : "rgba(0,0,0,0.10)";
        String accent = dark ? "#6aa6ff" : "#2563eb";
        String quoteBg = dark ? "rgba(255,255,255,0.04)" : "rgba(0,0,0,0.03)";
        return "html,body{margin:0;padding:0;background:transparent;}"
                + "body{font-family:'Microsoft YaHei','Segoe UI',sans-serif;font-size:13px;line-height:1.75;"
                + "color:" + text + ";word-wrap:break-word;}"
                + "p{margin:0 0 10px;}"
                + "h1,h2,h3,h4,h5,h6{color:" + (dark ? "#ffffff" : "#1f2937") + ";margin:18px 0 8px;line-height:1.35;}"
                + "h1{font-size:20px;}h2{font-size:17px;}h3{font-size:15px;}h4,h5,h6{font-size:13px;}"
                + "a{color:" + accent + ";text-decoration:none;}a:hover{text-decoration:underline;}"
                + "ul,ol{margin:0 0 10px;padding-left:24px;}li{margin:2px 0;}"
                + "hr{border:none;border-top:1px solid " + border + ";margin:16px 0;}"
                + "blockquote{margin:10px 0;padding:8px 14px;border-left:3px solid " + accent + ";"
                + "background:" + quoteBg + ";color:" + muted + ";}"
                + ".md-code{background:" + codeBg + ";border:1px solid " + border + ";border-radius:6px;"
                + "padding:10px 12px;overflow-x:auto;font-size:12px;}"
                + ".md-code code{font-family:Consolas,'Courier New',monospace;white-space:pre;}"
                + ".md-inline-code{background:" + codeBg + ";border-radius:4px;padding:1px 5px;"
                + "font-family:Consolas,'Courier New',monospace;font-size:12px;}"
                + ".md-img{max-width:100%;border-radius:8px;margin:6px 0;}"
                + "img{max-width:100%;}"
                + "table{border-collapse:collapse;margin:10px 0;}"
                + "td,th{border:1px solid " + border + ";padding:5px 9px;font-size:12px;}"
                + "details{margin:8px 0;padding:6px 10px;border:1px solid " + border + ";border-radius:6px;}"
                + "summary{cursor:pointer;color:" + muted + ";}"
                + ".md-empty{color:" + muted + ";font-style:italic;}";
    }
}

package com.example.starlight.legal;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 把 docs/ 里的 Markdown 法律文本渲染成 JavaFX 节点。
 *
 * <p>为什么不用 WebView + Markdown 转 HTML：多一层转换就多一处和正本不一致的地方，
 * 而且 WebView 在原生 EXE 里启动慢、字号不可控。这里只实现法律文档实际用到的语法子集：
 * 标题（# / ## / ###）、段落、无序列表、有序列表、引用块（&gt;）、表格、分隔线（---）、
 * 粗体（**）、行内代码（`）与链接（[文字](地址)）。
 *
 * <p>视觉全部走 style.css 的 {@code .legal-*} 样式类（深浅主题各一套），
 * 正文可换行、表格等宽分列，窄窗口下不横向溢出。
 */
public final class LegalMarkdownView {

    /** 分隔线：至少三个连续 - * _ */
    private static final Pattern HR = Pattern.compile("^([-*_])\\1{2,}$");

    /** 表格分隔行：|---|:---:| 之类，只含 | - : 与空格，且至少一个 - */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^[\\s|:-]+$");

    private LegalMarkdownView() {
    }

    /** 渲染 Markdown 为可滚动的文档内容节点（不含滚动容器，由调用方决定放在哪） */
    public static VBox render(String markdown) {
        return render(markdown, null);
    }

    /**
     * 渲染 Markdown。
     *
     * @param markdown    Markdown 原文
     * @param linkHandler 点击 http(s) 链接时的回调（可为 null：链接只做样式，不响应点击）
     */
    public static VBox render(String markdown, Consumer<String> linkHandler) {
        VBox root = new VBox(10);
        root.getStyleClass().add("legal-doc");
        root.setFillWidth(true);
        if (markdown == null || markdown.isBlank()) return root;

        List<String> lines = Arrays.asList(
                markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                i++;
                continue;
            }
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                String text = line.substring(level).strip();
                if (!text.isEmpty()) {
                    Label heading = new Label(text);
                    heading.setWrapText(true);
                    heading.getStyleClass().add(level <= 1 ? "legal-h1"
                            : level == 2 ? "legal-h2" : "legal-h3");
                    VBox.setMargin(heading, new javafx.geometry.Insets(
                            level <= 1 ? 4 : 8, 0, 0, 0));
                    root.getChildren().add(heading);
                }
                i++;
                continue;
            }
            if (HR.matcher(line).matches()) {
                Region hr = new Region();
                hr.getStyleClass().add("legal-hr");
                hr.setMaxWidth(Double.MAX_VALUE);
                VBox.setMargin(hr, new javafx.geometry.Insets(6, 0, 6, 0));
                root.getChildren().add(hr);
                i++;
                continue;
            }
            if (line.startsWith(">")) {
                List<String> quoteLines = new ArrayList<>();
                int j = i;
                while (j < lines.size()) {
                    String q = lines.get(j).strip();
                    if (!q.startsWith(">")) break;
                    q = q.substring(1).strip();
                    if (!q.isEmpty()) quoteLines.add(q);
                    j++;
                }
                root.getChildren().add(buildQuote(quoteLines, linkHandler));
                i = j;
                continue;
            }
            if (isTableStart(lines, i)) {
                int j = i;
                List<List<String>> rows = new ArrayList<>();
                while (j < lines.size()) {
                    String t = lines.get(j).strip();
                    if (!t.startsWith("|")) break;
                    if (!isTableSeparator(t)) rows.add(splitRow(t));
                    j++;
                }
                root.getChildren().add(buildTable(rows, linkHandler));
                i = j;
                continue;
            }
            if (isBullet(line) || isOrdered(line)) {
                root.getChildren().add(buildList(lines, i, linkHandler));
                // buildList 已经把下标推进到列表结束
                i = nextBlockIndex(lines, i);
                continue;
            }
            // 普通段落：连续非空、非块级起始行合并为一段
            StringBuilder paragraph = new StringBuilder(line);
            int j = i + 1;
            while (j < lines.size()) {
                String next = lines.get(j).strip();
                if (next.isEmpty() || startsBlock(next)) break;
                paragraph.append(joinSeparator(paragraph.toString(), next)).append(next);
                j++;
            }
            root.getChildren().add(paragraph(paragraph.toString(), linkHandler));
            i = j;
        }
        return root;
    }

    // 块级元素 //

    private static Node paragraph(String text, Consumer<String> linkHandler) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("legal-p-flow");
        addInline(flow, text, linkHandler);
        return flow;
    }

    private static Node buildQuote(List<String> lines, Consumer<String> linkHandler) {
        VBox quote = new VBox(7);
        quote.getStyleClass().add("legal-quote");
        for (String line : lines) quote.getChildren().add(paragraph(line, linkHandler));
        return quote;
    }

    private static Node buildList(List<String> lines, int start, Consumer<String> linkHandler) {
        VBox list = new VBox(6);
        list.getStyleClass().add("legal-list");
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i).strip();
            String marker;
            String content;
            if (isBullet(line)) {
                marker = "•";
                content = line.substring(1).strip();
            } else if (isOrdered(line)) {
                int dot = line.indexOf('.');
                marker = line.substring(0, dot + 1);
                content = line.substring(dot + 1).strip();
            } else {
                break;
            }
            Label bullet = new Label(marker);
            bullet.getStyleClass().add("legal-bullet");
            bullet.setMinWidth(Region.USE_PREF_SIZE);
            TextFlow flow = new TextFlow();
            flow.getStyleClass().add("legal-p-flow");
            HBox.setHgrow(flow, Priority.ALWAYS);
            addInline(flow, content, linkHandler);
            HBox row = new HBox(8, bullet, flow);
            row.setAlignment(Pos.TOP_LEFT);
            list.getChildren().add(row);
            i++;
        }
        return list;
    }

    private static Node buildTable(List<List<String>> rows, Consumer<String> linkHandler) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("legal-table");
        if (rows.isEmpty()) return grid;
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / columns);
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            grid.getColumnConstraints().add(cc);
        }
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            for (int c = 0; c < columns; c++) {
                String value = c < cells.size() ? cells.get(c) : "";
                // 用 TextFlow 而不是 Label：表格里也有 `代码` / **粗体**，
                // Label 会把反引号原样画出来（隐私政策的域名列表就是这样）
                TextFlow cell = new TextFlow();
                cell.getStyleClass().add("legal-table-cell");
                cell.setMaxWidth(Double.MAX_VALUE);
                cell.setMinWidth(0);
                addInline(cell, value, linkHandler);
                if (r == 0) {
                    cell.getStyleClass().add("legal-table-head");
                    for (Node child : cell.getChildren()) {
                        child.getStyleClass().add("legal-strong");
                    }
                }
                GridPane.setHgrow(cell, Priority.ALWAYS);
                grid.add(cell, c, r);
            }
        }
        return grid;
    }

    // 行内格式 //

    /** 行内解析：**粗体** / `代码` / [文字](地址)，其余原样输出 */
    private static void addInline(TextFlow flow, String text, Consumer<String> linkHandler) {
        int i = 0;
        while (i < text.length()) {
            int boldAt = text.indexOf("**", i);
            int codeAt = text.indexOf('`', i);
            int linkAt = text.indexOf('[', i);
            int next = minPositive(boldAt, codeAt, linkAt);
            if (next < 0) {
                addText(flow, text.substring(i), "legal-p");
                return;
            }
            if (next > i) addText(flow, text.substring(i, next), "legal-p");

            if (next == boldAt) {
                int end = text.indexOf("**", boldAt + 2);
                if (end < 0) {
                    addText(flow, text.substring(boldAt), "legal-p");
                    return;
                }
                addText(flow, text.substring(boldAt + 2, end), "legal-strong");
                i = end + 2;
            } else if (next == codeAt) {
                int end = text.indexOf('`', codeAt + 1);
                if (end < 0) {
                    addText(flow, text.substring(codeAt), "legal-p");
                    return;
                }
                addText(flow, text.substring(codeAt + 1, end), "legal-code");
                i = end + 1;
            } else {
                int close = text.indexOf(']', linkAt);
                int paren = close >= 0 ? text.indexOf('(', close) : -1;
                int end = paren >= 0 ? text.indexOf(')', paren) : -1;
                if (close < 0 || paren != close + 1 || end < 0) {
                    addText(flow, text.substring(linkAt), "legal-p");
                    return;
                }
                String label = text.substring(linkAt + 1, close);
                String target = text.substring(paren + 1, end).strip();
                Text link = new Text(label);
                link.getStyleClass().add("legal-link");
                if (linkHandler != null
                        && (target.startsWith("http://") || target.startsWith("https://"))) {
                    link.setCursor(Cursor.HAND);
                    final String url = target;
                    link.setOnMouseClicked(e -> linkHandler.accept(url));
                }
                flow.getChildren().add(link);
                i = end + 1;
            }
        }
    }

    private static void addText(TextFlow flow, String text, String styleClass) {
        if (text == null || text.isEmpty()) return;
        Text node = new Text(text);
        node.getStyleClass().add(styleClass);
        flow.getChildren().add(node);
    }

    // 语法判断 //

    private static boolean isBullet(String line) {
        return line.length() > 1 && (line.startsWith("- ") || line.startsWith("* ")
                || line.startsWith("+ "));
    }

    private static boolean isOrdered(String line) {
        int dot = line.indexOf('.');
        if (dot <= 0 || dot > 3) return false;
        for (int k = 0; k < dot; k++) {
            if (!Character.isDigit(line.charAt(k))) return false;
        }
        return line.length() > dot + 1 && line.charAt(dot + 1) == ' ';
    }

    private static boolean startsBlock(String line) {
        return line.startsWith("#") || line.startsWith(">") || line.startsWith("|")
                || isBullet(line) || isOrdered(line) || HR.matcher(line).matches();
    }

    private static boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) return false;
        String head = lines.get(index).strip();
        String sep = lines.get(index + 1).strip();
        return head.startsWith("|") && isTableSeparator(sep);
    }

    private static boolean isTableSeparator(String line) {
        return line.startsWith("|") && TABLE_SEPARATOR.matcher(line).matches()
                && line.indexOf('-') >= 0;
    }

    private static List<String> splitRow(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) cells.add(cell.strip());
        return cells;
    }

    /** 列表块结束后的下标：跳过连续的空行与列表行 */
    private static int nextBlockIndex(List<String> lines, int start) {
        int i = start;
        while (i < lines.size()) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !isBullet(line) && !isOrdered(line)) break;
            i++;
        }
        return i;
    }

    /** 取三个候选位置中最小且非负的那个 */
    private static int minPositive(int a, int b, int c) {
        int best = -1;
        for (int v : new int[]{a, b, c}) {
            if (v >= 0 && (best < 0 || v < best)) best = v;
        }
        return best;
    }

    /** 折行合并：中文之间不留空格，拉丁文字之间补一个空格 */
    private static String joinSeparator(String previous, String next) {
        if (previous.isEmpty() || next.isEmpty()) return "";
        char left = previous.charAt(previous.length() - 1);
        char right = next.charAt(0);
        return isCjk(left) || isCjk(right) ? "" : " ";
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.GENERAL_PUNCTUATION;
    }
}

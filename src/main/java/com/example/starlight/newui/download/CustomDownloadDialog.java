package com.example.starlight.newui.download;


import com.example.starlight.util.I18N;
import com.example.starlight.download.engine.HttpDownloadEngine;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.util.ArchiveUtils;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 「自定义下载」弹窗：可填一个或多个文件直链、逐条自定义保存文件名、选择保存目录。
 *
 * <p>保存目录默认是系统「下载」文件夹（{@code ~/Downloads}），可以直接改文本框，也可以点「浏览」选；
 * 文件名留空时自动从地址末段推导（百分号解码 + 非法字符净化）。
 * 下载本身由 {@link com.example.starlight.newui.page.DownloadCenterPage} 登记成普通下载任务卡片，
 * 每个文件用 {@code <文件名>.<地址短哈希>.part} 落盘再接续，支持断点续传。
 */
public final class CustomDownloadDialog {

    /** 弹窗宽度：能放下较长的直链与文件名，又不至于撑满主窗口 */
    private static final double MODAL_WIDTH = 520;
    /** 文件列表最大高度：文件多时列表内部滚动，弹窗不会无限变高（窗口最小高度 620） */
    private static final double FILE_LIST_MAX_HEIGHT = 200;
    /** 超长文件名在部分文件系统上会失败，统一截到这个长度（保留扩展名） */
    private static final int MAX_NAME_LENGTH = 150;

    /** 单个待下载文件：地址 + 最终保存文件名（非空、已净化） */
    public record FileRequest(String url, String fileName) {
    }

    /** 用户确认后的结果：保存目录 + 文件列表（至少一个） */
    public record Request(Path dir, List<FileRequest> files) {
    }

    private final LauncherContext host;
    private final Consumer<Request> onConfirm;

    private final VBox fileListBox = new VBox(10);
    private final List<FileRow> rows = new ArrayList<>();
    private final TextField dirField = new TextField();
    private final Label targetPreview = new Label();
    private final Label errorLabel = new Label();
    /** 文件列表滚动区：新增一行后自动滚到底部，保证刚加的文件看得见 */
    private ScrollPane fileScroll;

    /** 一行「文件」输入：文件序号 + 地址 + 文件名 + 删除 */
    private final class FileRow {
        final VBox node = new VBox(6);
        final Label header = new Label();
        final TextField url = new TextField();
        final TextField name = new TextField();
        final Button remove = new Button();

        FileRow() {
            url.setPromptText("https://example.com/file.zip");
            url.getStyleClass().add("input-field");
            url.setMaxWidth(Double.MAX_VALUE);

            name.setPromptText(I18N.get("download.t0005"));
            name.getStyleClass().add("input-field");
            name.setMaxWidth(Double.MAX_VALUE);

            header.getStyleClass().add("modal-text-title");
            remove.setGraphic(AppIcons.icon("trash", 13, null));
            remove.getStyleClass().add("modal-close-btn");
            remove.setTooltip(new Tooltip(I18N.get("download.t0006")));
            remove.setOnAction(e -> removeRow(this));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox head = new HBox(8, header, spacer, remove);
            head.setAlignment(Pos.CENTER_LEFT);

            node.getChildren().addAll(head, url, name);
            node.setStyle("-fx-padding: 8; -fx-background-color: rgba(0,0,0,0.03); -fx-background-radius: 10;");
            url.textProperty().addListener((o, a, b) -> refreshPreview());
            name.textProperty().addListener((o, a, b) -> refreshPreview());
        }
    }

    private CustomDownloadDialog(LauncherContext host, Consumer<Request> onConfirm) {
        this.host = host;
        this.onConfirm = onConfirm;
    }

    /** 打开「自定义下载」弹窗；用户点「开始下载」并校验通过后回调 */
    public static void show(LauncherContext host, Consumer<Request> onConfirm) {
        new CustomDownloadDialog(host, onConfirm).open();
    }

    /** 默认保存目录：系统「下载」文件夹；不存在时退回用户主目录 */
    public static Path defaultDownloadDir() {
        String home = System.getProperty("user.home", ".");
        Path downloads = Paths.get(home, "Downloads");
        if (Files.isDirectory(downloads)) return downloads;
        return Paths.get(home);
    }

    /**
     * 从地址推导保存文件名：取 URL 末段 → 百分号解码 → 净化非法字符。
     * 末段为空（地址以 / 结尾）或净化后不可用时回退成「下载文件」。
     */
    public static String fileNameOf(String url) {
        String name = url == null ? "" : url.trim();
        int cut = name.length();
        int q = name.indexOf('?');
        if (q >= 0 && q < cut) cut = q;
        int hash = name.indexOf('#');
        if (hash >= 0 && hash < cut) cut = hash;
        name = name.substring(0, cut);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        try {
            // URLDecoder 是表单解码，会把 '+' 变成空格；URL 路径里的 '+' 是字面量，
            // 先转成 %2B 再解码，避免把 file+name.zip 存成 file name.zip
            name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 不是合法百分号编码就按原样使用
        }
        String safe = ArchiveUtils.sanitizeDownloadFileName(name);
        if (safe == null || safe.isBlank()) return I18N.get("download.t0007");
        return limitNameLength(safe);
    }

    /**
     * 用户手填的文件名：净化非法字符、去掉结尾的点和空格（Windows 不允许）。
     *
     * @return 可用的文件名；为空或净化后不可用时返回 null
     */
    public static String normalizeCustomName(String raw) {
        if (raw == null) return null;
        String name = raw.trim();
        if (name.isEmpty()) return null;
        String safe = ArchiveUtils.sanitizeDownloadFileName(name);
        if (safe == null) return null;
        // Windows 文件名不能以点/空格结尾；同时避免 ".." 这类路径穿越写法
        safe = safe.replaceAll("[. ]+$", "").trim();
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) return null;
        return limitNameLength(safe);
    }

    /** 超长文件名截断（优先保住扩展名） */
    private static String limitNameLength(String safe) {
        if (safe.length() <= MAX_NAME_LENGTH) return safe;
        int dot = safe.lastIndexOf('.');
        String ext = dot > 0 && safe.length() - dot <= 12 ? safe.substring(dot) : "";
        return safe.substring(0, Math.max(1, MAX_NAME_LENGTH - ext.length())) + ext;
    }

    // 构建 //

    private void open() {
        VBox body = new VBox(10);
        body.setAlignment(Pos.TOP_CENTER);

        StackPane icon = new StackPane(AppIcons.icon("download", 24, Color.web("#3b82f6")));
        icon.getStyleClass().addAll("modal-icon", "info");
        VBox.setMargin(icon, new Insets(0, 0, 6, 0));
        body.getChildren().add(icon);

        Label title = new Label(I18N.get("download.t0008"));
        title.getStyleClass().add("modal-card-title");
        body.getChildren().add(title);

        Label desc = new Label(I18N.get("download.t0009"));
        desc.getStyleClass().add("modal-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(Double.MAX_VALUE);
        body.getChildren().add(desc);

        // 文件列表：文件多时列表内部滚动，弹窗高度保持稳定
        fileScroll = new ScrollPane(fileListBox);
        fileScroll.setFitToWidth(true);
        fileScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        fileScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        fileScroll.setStyle("-fx-background-color: transparent;");
        fileScroll.setPrefViewportHeight(132);
        fileScroll.setMaxHeight(FILE_LIST_MAX_HEIGHT);
        body.getChildren().add(fileScroll);

        Button addBtn = AppIcons.button("plus", I18N.get("download.t0010"));
        addBtn.getStyleClass().add("modal-btn-cancel");
        addBtn.setMaxWidth(Double.MAX_VALUE);
        addBtn.setOnAction(e -> addRow("", ""));
        body.getChildren().add(addBtn);

        // 保存目录（默认系统「下载」文件夹，可改 / 可浏览）
        dirField.setText(defaultDownloadDir().toString());
        dirField.getStyleClass().add("input-field");
        dirField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dirField, Priority.ALWAYS);
        Button browse = AppIcons.button("folder-open", I18N.get("download.t0011"));
        browse.getStyleClass().add("modal-btn-cancel");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(e -> chooseDir());
        HBox dirRow = new HBox(8, dirField, browse);
        dirRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(fieldBox(I18N.get("download.t0012"), dirRow));

        targetPreview.getStyleClass().add("modal-hint");
        targetPreview.setWrapText(true);
        targetPreview.setMaxWidth(Double.MAX_VALUE);
        body.getChildren().add(targetPreview);

        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        body.getChildren().add(errorLabel);

        body.getChildren().add(actions());

        dirField.textProperty().addListener((o, a, b) -> refreshPreview());
        addRow("", "");
        refreshPreview();

        host.ui().modal("", body, MODAL_WIDTH, -1, true);
        Platform.runLater(() -> rows.get(0).url.requestFocus());
    }

    /** 一栏：小标题 + 控件（输入框 / 目录行） */
    private static Node fieldBox(String text, Node control) {
        Label label = new Label(text);
        label.getStyleClass().add("modal-text-title");
        VBox box = new VBox(6, label, control);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private HBox actions() {
        Button cancel = new Button(I18N.get("download.t0013"));
        cancel.getStyleClass().add("modal-btn-cancel");
        cancel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancel, Priority.ALWAYS);
        cancel.setOnAction(e -> host.ui().closeModal());

        Button start = new Button(I18N.get("download.t0014"));
        start.getStyleClass().add("modal-btn-ok");
        start.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(start, Priority.ALWAYS);
        start.setOnAction(e -> confirm());

        HBox box = new HBox(10, cancel, start);
        box.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(box, new Insets(6, 0, 0, 0));
        return box;
    }

    // 文件行管理 //

    private FileRow addRow(String url, String name) {
        FileRow row = new FileRow();
        row.url.setText(url);
        row.name.setText(name);
        rows.add(row);
        fileListBox.getChildren().add(row.node);
        reindexRows();
        refreshPreview();
        // 等这一帧布局完成再滚到底，保证「添加文件」后新行可见
        if (fileScroll != null) {
            Platform.runLater(() -> fileScroll.setVvalue(1.0));
        }
        return row;
    }

    private void removeRow(FileRow row) {
        if (rows.size() <= 1) return;
        rows.remove(row);
        fileListBox.getChildren().remove(row.node);
        reindexRows();
        refreshPreview();
    }

    /** 序号重排：只留一行时隐藏删除按钮（至少保留一个文件行） */
    private void reindexRows() {
        boolean multiple = rows.size() > 1;
        for (int i = 0; i < rows.size(); i++) {
            FileRow r = rows.get(i);
            r.header.setText(I18N.get("download.p0071", (i + 1)));
            r.remove.setVisible(multiple);
            r.remove.setManaged(multiple);
        }
    }

    // 交互 //

    private void chooseDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18N.get("download.t0015"));
        Path start = bestExistingDir(currentDirText());
        if (start != null) chooser.setInitialDirectory(start.toFile());
        File picked = chooser.showDialog(host.stage());
        if (picked != null) dirField.setText(picked.getAbsolutePath());
    }

    /** 保存目录输入框里的原始文本 */
    private String currentDirText() {
        String text = dirField.getText();
        return text == null ? "" : text.trim();
    }

    private void refreshPreview() {
        String dir = currentDirText();
        String shownDir = dir;
        try {
            // 预览里用规范化后的绝对路径，避免出现 C:\a\\file.zip 这种双分隔符
            shownDir = Paths.get(dir).toAbsolutePath().normalize().toString();
        } catch (Exception ignored) {
            // 输入还不是合法路径时按原文预览
        }
        List<String> names = resolvedNames();
        if (names.isEmpty()) {
            targetPreview.setText(I18N.get("download.t0016"));
        } else if (names.size() == 1) {
            targetPreview.setText(I18N.get("download.p0072", (shownDir.isEmpty() ? "" : shownDir + File.separator) + names.get(0)));
        } else {
            targetPreview.setText(I18N.get("cdd.r0001", names.size(), shownDir));
        }
    }

    /** 按当前输入算出的文件名列表（只统计填了地址的行） */
    private List<String> resolvedNames() {
        List<String> names = new ArrayList<>();
        for (FileRow row : rows) {
            String url = row.url.getText() == null ? "" : row.url.getText().trim();
            if (url.isEmpty()) continue;
            String custom = row.name.getText() == null ? "" : row.name.getText().trim();
            String norm = custom.isEmpty() ? fileNameOf(url) : normalizeCustomName(custom);
            names.add(norm == null ? custom : norm);
        }
        return names;
    }

    private void confirm() {
        List<FileRequest> files = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            FileRow row = rows.get(i);
            String url = row.url.getText() == null ? "" : row.url.getText().trim();
            if (url.isEmpty()) {
                // 多行时允许留空行（还没填完的槽位），单行时必须填
                if (rows.size() == 1) {
                    showError(I18N.get("download.t0017"));
                    row.url.requestFocus();
                    return;
                }
                continue;
            }
            if (parseHttpUri(url) == null) {
                showError(I18N.get("cdd.r0002", i + 1));
                row.url.requestFocus();
                return;
            }
            String custom = row.name.getText() == null ? "" : row.name.getText().trim();
            String name = custom.isEmpty() ? fileNameOf(url) : normalizeCustomName(custom);
            if (name == null) {
                showError(I18N.get("cdd.r0003", i + 1));
                row.name.requestFocus();
                return;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                showError(I18N.get("cdd.r0004", name));
                row.name.requestFocus();
                return;
            }
            files.add(new FileRequest(url, name));
        }
        if (files.isEmpty()) {
            showError(I18N.get("download.t0018"));
            return;
        }

        String dirText = currentDirText();
        if (dirText.isEmpty()) {
            showError(I18N.get("download.t0019"));
            return;
        }
        Path dir;
        try {
            dir = Paths.get(dirText).toAbsolutePath().normalize();
        } catch (Exception e) {
            showError(I18N.get("download.p0073", e.getMessage()));
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            showError(I18N.get("download.p0074", e.getMessage()));
            return;
        }
        if (!Files.isWritable(dir)) {
            showError(I18N.get("download.p0075", dir));
            return;
        }
        host.ui().closeModal();
        onConfirm.accept(new Request(dir, List.copyOf(files)));
    }

    private void showError(String text) {
        boolean show = text != null && !text.isBlank();
        errorLabel.setText(show ? text : "");
        errorLabel.setVisible(show);
        errorLabel.setManaged(show);
    }

    /**
     * 只接受 http/https 且带主机名的地址。
     * <p>先走下载引擎同一套 {@code sanitizeUrl}，含中文/空格的地址也能被正确编码后校验。
     */
    private static URI parseHttpUri(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(HttpDownloadEngine.sanitizeUrl(url));
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            String host = uri.getHost();
            return host == null || host.isBlank() ? null : uri;
        } catch (Exception e) {
            return null;
        }
    }

    /** 目录选择器的起始目录：当前输入目录存在就用它，否则用最近的已存在父目录 */
    private static Path bestExistingDir(String text) {
        Path p;
        try {
            p = text.isEmpty() ? defaultDownloadDir() : Paths.get(text);
        } catch (Exception e) {
            p = defaultDownloadDir();
        }
        p = p.toAbsolutePath();
        while (p != null && !Files.isDirectory(p)) p = p.getParent();
        return p;
    }
}

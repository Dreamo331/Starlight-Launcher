package com.example.starlight.newui.page;


import com.example.starlight.util.I18N;
import com.example.starlight.ModsApi.RemoteMod;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.newui.ui.SkeletonFactory;
import com.example.starlight.crash.CrashReportParser;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.log.sendlogs;
import com.example.starlight.model.LogUploadRecord;
import com.example.starlight.newui.ui.LogUploadResultWindow;
import com.example.starlight.service.LogUploadStore;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ResourceScanner;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import javafx.stage.DirectoryChooser;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;


/** 
资源管理页（存档 / 崩溃报告 / 截图 / 日志）与截图管理页
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class ResourcesPage {

    private final LauncherContext host;

    /** 资源页目录筛选：当前选中的来源（null=全部，其余为“全局”/版本名），切换分类时重置 */
    private String currentFilterOrigin;
    /** 资源页搜索关键词：删除/切换目录后重建页面时保留，切换分类时重置 */
    private String currentSearchKeyword;
    /** 截图管理页目录筛选：当前选中的来源（null=全部，其余为“全局”/版本名） */
    private String currentScreenshotFilterOrigin;
    /** 截图管理页搜索关键词：删除截图后重建页面时保留 */
    private String currentScreenshotKeyword;

    public ResourcesPage(LauncherContext host) {
        this.host = host;
    }

    // 资源页面 //

    public Node build() {
        HBox layout = new HBox(20);
        layout.setPadding(new Insets(0, 4, 0, 0));
        VBox nav = new VBox(4);
        nav.setPrefWidth(160);
        nav.setMinWidth(160);
        nav.setMaxWidth(160);
        nav.setMaxHeight(Double.MAX_VALUE);
        nav.getStyleClass().add("download-nav");
        Label title = new Label(I18N.get("page.t0405"));
        title.getStyleClass().add("download-nav-title");
        nav.getChildren().add(title);

        StackPane contentArea = new StackPane();
        contentArea.setPadding(new Insets(0, 0, 0, 16));
        HBox.setHgrow(contentArea, Priority.ALWAYS);

        ScrollPane contentScroll = new ScrollPane(contentArea);
        // 统一挂接惯性滚动（阻尼 + fling），与其它页面同一套手感
        PageKit.configureScrollPane(host, contentScroll);
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        // 分类导航：图标 + 选中高亮（默认选中第一个“存档”）
        String[][] cats = {
                {"resource_archives", I18N.get("page.t0406"), "archive"}, {"resource_mods", I18N.get("page.t0407"), "puzzle"},
                {"resource_resourcepacks", I18N.get("page.t0408"), "image"}, {"resource_shaders", I18N.get("page.t0409"), "sun"},
                {"resource_crashreports", I18N.get("page.t0410"), "warning"}, {"resource_screenshots", I18N.get("page.t0411"), "camera"},
                {"resource_uploaded_logs", I18N.get("page.t0412"), "upload"},
                {"resource_others", I18N.get("page.t0413"), "ellipsis"}
        };
        for (String[] c : cats) {
            Button btn = new Button(c[1]);
            btn.getStyleClass().add("sidebar-item");
            btn.setMaxWidth(Double.MAX_VALUE);
            AppIcons.apply(btn, c[2], 15, null);
            final StackPane finalContentArea = contentArea;
            btn.setOnAction(e -> {
                nav.getChildren().stream()
                        .filter(n -> n instanceof Button)
                        .forEach(n -> n.getStyleClass().remove("selected"));
                btn.getStyleClass().add("selected");
                currentFilterOrigin = null; // 切换分类时重置目录筛选
                currentSearchKeyword = null; // 切换分类时重置搜索关键词
                showResourceContent(c[0], finalContentArea);
            });
            nav.getChildren().add(btn);
        }
        ((Button) nav.getChildren().get(1)).getStyleClass().add("selected");
        showResourceContent("resource_archives", contentArea);
        layout.getChildren().addAll(nav, contentScroll);
        VBox.setVgrow(layout, Priority.ALWAYS);
        return layout;
    }

    private void showResourceContent(String type, StackPane container) {
        VBox content = new VBox(16);
        content.setPadding(new Insets(0, 4, 0, 0));
        String title = switch (type) {
            case "resource_archives" -> I18N.get("page.t0414");
            case "resource_mods" -> I18N.get("page.t0415");
            case "resource_resourcepacks" -> I18N.get("page.t0416");
            case "resource_shaders" -> I18N.get("page.t0417");
            case "resource_crashreports" -> I18N.get("page.t0418");
            case "resource_screenshots" -> I18N.get("page.t0419");
            case "resource_uploaded_logs" -> I18N.get("page.t0420");
            default -> I18N.get("page.t0421");
        };
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("content-title");
        content.getChildren().add(titleLabel);
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        if (type.equals("resource_archives")) {
            // 扫描全局 saves + 所有版本隔离目录下的 saves，避免版本隔离导致存档显示不全；可按目录筛选
            // 存档 NBT 解析是磁盘 IO 重活，移到后台线程执行，期间用骨架屏占位，避免阻塞 FX 线程导致窗口未响应。
            List<Path> saveDirs = ResourceScanner.collectResourceDirs(gameDir, "saves");
            Node skeleton = SkeletonFactory.buildListSkeleton(4);
            content.getChildren().add(skeleton);
            // 提交前捕获筛选值，避免后台扫描期间用户切换目录导致竞态
            final String scanOrigin = currentFilterOrigin;
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                java.util.List<UIGeneralControlClass.SaveInfo> saves =
                        ResourceScanner.listAllSaves(gameDir, scanOrigin);
                Platform.runLater(() -> {
                    content.getChildren().remove(skeleton);
                    titleLabel.setText(title + " (" + saves.size() + ")");
                    addDirComboFilter(content, saveDirs, gameDir,
                            scanOrigin, origin -> { currentFilterOrigin = origin; showResourceContent(type, container); });
                    if (saves.isEmpty()) {
                        content.getChildren().add(new Label(I18N.get("page.t0422")) {{
                            setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
                        }});
                    } else {
                        // 搜索 + 分批渲染：一次只铺 20 张存档卡片，其余点底部按钮继续往下加载
                        content.getChildren().add(pagedSearchSection(I18N.get("page.t0423"), saves,
                                s -> s.worldName + " " + s.version + " " + s.path,
                                s -> {
                                    String savePath = s.path;
                                    return createResourceCard(s.worldName, I18N.get("page.p0862", s.version),
                                            new String[]{I18N.get("page.t0671"), I18N.get("page.t0672"), I18N.get("page.t0673")},
                                            new Runnable[]{
                                                    () -> {
                                                        String backupDir = gameDir + "/backups";
                                                        var result = UIGeneralControlClass.backupSave(savePath, backupDir);
                                                        host.ui().toast(result.message);
                                                    },
                                                    () -> host.confirmDelete(I18N.get("res.r0001", s.worldName), () -> {
                                                        try {
                                                            java.nio.file.Path saveDir = java.nio.file.Paths.get(savePath);
                                                            java.nio.file.Files.walk(saveDir)
                                                                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                                                                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                                                            host.ui().toast(I18N.get("page.p0863", s.worldName));
                                                            showResourceContent(type, container);
                                                        } catch (Exception ex) {
                                                            host.ui().toast(I18N.get("page.p0864", ex.getMessage()));
                                                        }
                                                    }),
                                                    () -> host.openFile(savePath)
                                            });
                                },
                                false,
                                n -> setCountTitle(titleLabel, title, n, saves.size())));
                    }
                });
            });
        } else if (type.equals("resource_mods")) {
            loadToggleableItems(I18N.get("page.t0424"), ResourceScanner.collectResourceDirs(gameDir, "mods"), container, type, true);
            return;
        } else if (type.equals("resource_resourcepacks")) {
            loadToggleableItems(I18N.get("page.t0425"), ResourceScanner.collectResourceDirs(gameDir, "resourcepacks"), container, type, false);
            return;
        } else if (type.equals("resource_shaders")) {
            loadToggleableItems(I18N.get("page.t0426"), ResourceScanner.collectResourceDirs(gameDir, "shaderpacks"), container, type, false);
            return;
        } else if (type.equals("resource_crashreports")) {
            java.util.List<Path> reports = ResourceScanner.listCrashReports(gameDir, currentFilterOrigin);
            titleLabel.setText(title + " (" + reports.size() + ")");
            addDirComboFilter(content, ResourceScanner.collectResourceDirs(gameDir, "crash-reports"), gameDir,
                    currentFilterOrigin, origin -> { currentFilterOrigin = origin; showResourceContent(type, container); });
            if (reports.isEmpty()) {
                content.getChildren().add(new Label(I18N.get("page.t0427")) {{
                    setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
                }});
            } else {
                // 摘要要读文件，先缓存下来（只对当前这批用到的报告解析，避免几百份报告全部读一遍）
                Map<Path, String> summaryCache = new LinkedHashMap<>();
                content.getChildren().add(pagedSearchSection(I18N.get("page.t0428"), reports,
                        r -> summaryCache.computeIfAbsent(r, p -> CrashReportParser.summarize(p.toString()))
                                + " " + r.getFileName(),
                        r -> {
                            String summary = summaryCache.computeIfAbsent(r, p -> CrashReportParser.summarize(p.toString()));
                            String titleText = summary.length() > 48 ? summary.substring(0, 48) + "…" : summary;
                            return createResourceCard(titleText,
                                    r.getFileName() + "  |  " + FormatUtils.formatFileSize(r.toFile().length())
                                            + "  |  " + ResourceScanner.dirOriginLabel(r.getParent(), gameDir),
                                    new String[]{I18N.get("page.t0674"), I18N.get("page.t0675")},
                                    new Runnable[]{
                                            () -> host.openFile(r.toString()),
                                            () -> host.confirmDelete(I18N.get("res.r0002", r.getFileName()), () -> {
                                                try {
                                                    Files.delete(r);
                                                    host.ui().toast(I18N.get("page.p0865", r.getFileName()));
                                                } catch (Exception ex) {
                                                    host.ui().toast(I18N.get("page.p0866", ex.getMessage()));
                                                }
                                                showResourceContent(type, container);
                                            })
                                    });
                        },
                        false,
                        n -> setCountTitle(titleLabel, title, n, reports.size())));
            }
        } else if (type.equals("resource_screenshots")) {
            java.util.List<Path> shots = ResourceScanner.listScreenshots(gameDir, currentFilterOrigin, host.config().get("ScreenshotDir"));
            titleLabel.setText(title + " (" + shots.size() + ")");
            // 目录筛选按钮：全局 + 各版本隔离 + 自定义截图目录
            java.util.List<Path> ssDirs = new ArrayList<>(ResourceScanner.collectResourceDirs(gameDir, "screenshots"));
            String customScreenshotDir = host.config().get("ScreenshotDir");
            if (customScreenshotDir != null && !customScreenshotDir.isEmpty()) {
                Path customDir = Paths.get(customScreenshotDir);
                if (Files.isDirectory(customDir) && ssDirs.stream()
                        .noneMatch(p -> p.toString().equalsIgnoreCase(customDir.toString()))) {
                    ssDirs.add(customDir);
                }
            }
            addDirComboFilter(content, ssDirs, gameDir,
                    currentFilterOrigin, origin -> { currentFilterOrigin = origin; showResourceContent(type, container); });
            if (shots.isEmpty()) {
                content.getChildren().add(new Label(I18N.get("page.t0429")) {{
                    setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
                }});
            } else {
                // 缩略图网格：点击预览大图；每批只解码 20 张缩略图，避免几百张截图一次全部读进内存
                content.getChildren().add(pagedSearchSection(I18N.get("page.t0430"), shots,
                        shot -> shot.getFileName().toString(),
                        shot -> createScreenshotThumb(shot, ResourceScanner.dirOriginLabel(shot.getParent(), gameDir)),
                        true,
                        n -> setCountTitle(titleLabel, title, n, shots.size())));
            }
        } else if (type.equals("resource_uploaded_logs")) {
            java.util.List<LogUploadRecord> records = LogUploadStore.load();
            titleLabel.setText(title + " (" + records.size() + ")");
            if (records.isEmpty()) {
                content.getChildren().add(new Label(I18N.get("page.t0431")) {{
                    setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
                }});
            } else {
                content.getChildren().add(pagedSearchSection(I18N.get("page.t0432"), records,
                        r -> (r.name == null ? "" : r.name) + " " + (r.url == null ? "" : r.url),
                        r -> {
                            String timeStr = r.time > 0
                                    ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                            .format(new java.util.Date(r.time)) : I18N.get("page.t0433");
                            String url = r.url == null ? "" : r.url;
                            String sub = timeStr + "  |  "
                                    + (r.fileCount > 0 ? I18N.get("res.r0003", r.fileCount)
                                            : I18N.get("page.t0676"))
                                    + "  |  " + (url.length() > 56 ? url.substring(0, 56) + "…" : url);
                            return createResourceCard(
                                    r.name == null || r.name.isEmpty() ? I18N.get("page.t0434") : r.name, sub,
                                    new String[]{I18N.get("page.t0677"), I18N.get("page.t0678"), I18N.get("page.t0679")},
                                    new Runnable[]{
                                            () -> LogUploadResultWindow.show(host.stage(), r,
                                                    host.ui()::toast,
                                                    () -> {
                                                        host.ui().toast(I18N.get("page.t0435"));
                                                        showResourceContent(type, container);
                                                    }),
                                            () -> {
                                                if (r.url != null && !r.url.isEmpty()) {
                                                    ClipboardContent cc = new ClipboardContent();
                                                    cc.putString(r.url);
                                                    Clipboard.getSystemClipboard().setContent(cc);
                                                    host.ui().toast(I18N.get("page.t0436"));
                                                }
                                            },
                                            () -> host.confirmDelete(I18N.get("page.t0437"), () -> {
                                                try {
                                                    if (r.id != null && !r.id.isEmpty()) {
                                                        try { sendlogs.deleteLog(r.id, r.token); } catch (Exception ignored) {}
                                                        LogUploadStore.remove(r.id);
                                                    } else {
                                                        // 旧版记录无 id：按引用 + url + 时间兜底移除本地记录
                                                        java.util.List<LogUploadRecord> all = LogUploadStore.load();
                                                        all.removeIf(x -> x == r
                                                                || (r.url != null && r.url.equals(x.url) && r.time == x.time));
                                                        LogUploadStore.save(all);
                                                    }
                                                    host.ui().toast(I18N.get("page.t0438"));
                                                } catch (Exception ex) {
                                                    host.ui().toast(I18N.get("page.p0867", ex.getMessage()));
                                                }
                                                showResourceContent(type, container);
                                            })
                                    });
                        },
                        false,
                        n -> setCountTitle(titleLabel, title, n, records.size())));
            }
        } else {
            titleLabel.setText(title);
            // 日志目录快捷入口 + 游戏根目录（其余资源目录已分配至各自分类页）
            java.util.List<Path> logDirs = ResourceScanner.collectResourceDirs(gameDir, "logs");
            java.util.List<DirEntry> entries = new ArrayList<>();
            if (logDirs.isEmpty()) {
                Path missing = Paths.get(gameDir, "logs");
                entries.add(new DirEntry(I18N.get("page.t0680"), I18N.get("res.r0004", missing), missing.toString()));
            } else {
                for (Path p : logDirs) {
                    entries.add(new DirEntry(I18N.get("page.p0868", ResourceScanner.dirOriginLabel(p, gameDir) + "）"),
                            p.toString(), p.toString()));
                }
            }
            entries.add(new DirEntry(I18N.get("page.t0439"), gameDir, gameDir));
            content.getChildren().add(pagedSearchSection(I18N.get("page.t0440"), entries,
                    e -> e.name() + " " + e.desc(),
                    e -> createResourceCard(e.name(), e.desc(),
                            new String[]{I18N.get("page.t0441")},
                            new Runnable[]{() -> host.openFile(e.path())}),
                    false,
                    n -> setCountTitle(titleLabel, title, n, entries.size())));
        }
        container.getChildren().clear();
        container.getChildren().add(content);
    }

    /** 通用可切换资源列表（模组/资源包/光影包，支持 .disabled 后缀切换；dirs 为全局 + 各版本隔离目录） */
    private void loadToggleableItems(String label, List<Path> dirs, StackPane container, String type, boolean isMod) {
        VBox content = new VBox(16);
        content.setPadding(new Insets(0, 4, 0, 0));
        Label titleLabel = new Label(label);
        titleLabel.getStyleClass().add("content-title");
        content.getChildren().add(titleLabel);
        // 目录筛选：下拉框选择（全部 = 全局 + 各版本），选完立即刷新列表
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        addDirComboFilter(content, dirs, gameDir, currentFilterOrigin,
                origin -> { currentFilterOrigin = origin; loadToggleableItems(label, dirs, container, type, isMod); });

        String suffix = isMod ? ".jar" : ".zip";
        // 合并扫描所有目录（全局 + 各版本隔离，支持按目录筛选）
        List<Path> items = new ArrayList<>();
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            if (currentFilterOrigin != null && !ResourceScanner.dirOriginLabel(dir, gameDir).equals(currentFilterOrigin)) continue;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(suffix) || n.endsWith(suffix + ".disabled") || Files.isDirectory(p);
                        }).forEach(items::add);
            } catch (Exception ignored) {}
        }
        items.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

        if (items.isEmpty()) {
            titleLabel.setText(label + " (0)");
            String hint = dirs.isEmpty()
                ? I18N.get("res.r0005", label)
                : I18N.get("res.r0006", label);
            content.getChildren().add(new Label(hint) {{
                setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
            }});
        } else {
            // 搜索 + 分批渲染：模组 / 资源包 / 光影包常有上百个，一次只铺 20 行，其余点底部按钮继续加载
            content.getChildren().add(pagedSearchSection(I18N.get("res.r0007", label), items,
                    p -> p.getFileName().toString(),
                    p -> buildToggleableRow(p, label, suffix, dirs, container, type, isMod),
                    false,
                    n -> setCountTitle(titleLabel, label, n, items.size())));
        }

        container.getChildren().clear();
        container.getChildren().add(content);
    }

    /** 可切换资源（模组 / 资源包 / 光影包）列表里的一行：名称 + 状态 + 启用/禁用 / 删除 / 搜索资源 */
    private HBox buildToggleableRow(Path item, String label, String suffix,
                                    List<Path> dirs, StackPane container, String type, boolean isMod) {
        String fileName = item.getFileName().toString();
        boolean isDir = Files.isDirectory(item);
        boolean enabled = isDir || fileName.endsWith(suffix);
        String displayName = isDir ? fileName :
                (enabled ? fileName : fileName.replace(suffix + ".disabled", suffix));
        String sizeStr = isDir ? I18N.get("page.t0442") : FormatUtils.formatFileSize(item.toFile().length());
        String status = enabled ? I18N.get("page.t0681") : I18N.get("page.t0682");
        String origin = ResourceScanner.dirOriginLabel(item.getParent(), host.config().getOrDefault("GameDir", ".minecraft"));

        HBox row = new HBox(12);
        row.getStyleClass().add("settings-card");

        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        HBox.setHgrow(info, Priority.ALWAYS);

        Label nameLbl = new Label(displayName);
        nameLbl.getStyleClass().add("settings-card-title");

        Label metaLbl = new Label(status + " | " + sizeStr + " | " + origin);
        metaLbl.getStyleClass().add("settings-card-desc");
        // 来源路径可能很长：单行省略，悬停看完整信息
        PageKit.cardTooltip(metaLbl);
        info.getChildren().addAll(nameLbl, metaLbl);

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);

        // 启用/禁用按钮
        Path finalItem = item;
        boolean finalEnabled = enabled;
        boolean finalIsDir = isDir;
        Button toggleBtn = new Button(enabled ? I18N.get("page.t0683") : I18N.get("page.t0684"));
        toggleBtn.getStyleClass().add("btn-primary");
        toggleBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        toggleBtn.setOnAction(e -> {
            if (finalIsDir) {
                host.ui().toast(I18N.get("page.t0443"));
                return;
            }
            try {
                Path newPath;
                if (finalEnabled) {
                    newPath = finalItem.resolveSibling(finalItem.getFileName().toString() + ".disabled");
                } else {
                    String n = finalItem.getFileName().toString();
                    newPath = finalItem.resolveSibling(n.replace(suffix + ".disabled", suffix));
                }
                Files.move(finalItem, newPath);
                host.ui().toast(finalEnabled ? I18N.get("page.t0685") : I18N.get("page.t0686"));
                loadToggleableItems(label, dirs, container, type, isMod);
            } catch (Exception ex) {
                host.ui().toast(I18N.get("page.p0869", ex.getMessage()));
            }
        });
        actions.getChildren().add(toggleBtn);

        // 删除按钮
        Button delBtn = AppIcons.button("trash", I18N.get("page.t0444"));
        delBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px; -fx-background-color: #fee2e2; -fx-text-fill: #dc2626;");
        delBtn.setOnAction(e -> host.confirmDelete(I18N.get("res.r0008", displayName), () -> {
            if (finalIsDir) {
                try {
                    Files.walk(finalItem)
                            .sorted((a, b) -> b.toString().length() - a.toString().length())
                            .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            } else {
                try { Files.delete(finalItem); } catch (Exception ignored) {}
            }
            host.ui().toast(I18N.get("page.p0870", displayName));
            loadToggleableItems(label, dirs, container, type, isMod);
        }));
        actions.getChildren().add(delBtn);

        // 跳转下载中心对应分类搜索（模组/资源包/光影包）
        Button dlBtn = AppIcons.button("search", I18N.get("page.t0445"));
        dlBtn.getStyleClass().add("btn-primary");
        dlBtn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
        dlBtn.setOnAction(e -> {
            int dlIdx = switch (type) {
                case "resource_mods" -> 2;              // 下载中心：模组
                case "resource_resourcepacks" -> 3;     // 下载中心：资源包
                case "resource_shaders" -> 4;           // 下载中心：光影包
                default -> -1;
            };
            if (dlIdx >= 0) host.openDownloadCategory(dlIdx);
            else host.ui().toast(I18N.get("page.t0446"));
        });
        actions.getChildren().add(dlBtn);

        row.getChildren().addAll(info, actions);
        return row;
    }

    /**
     * 资源页共用的「搜索框 + 分批渲染」区块。
     *
     * <p>长列表一次只铺 {@link PageKit#LIST_PAGE_SIZE} 张卡片，其余由底部的「向下加载更多」
     * 按钮按需追加：几百个模组 / 几百张截图也不会一次性堆出成百上千个节点，避免窗口卡顿。
     *
     * @param prompt    搜索框提示语
     * @param all       全部条目（已按目录筛选过）
     * @param searchKey 取「参与关键词匹配的文本」（通常为名称 + 说明）
     * @param cardOf    单条 → 卡片节点
     * @param grid      true = 网格布局（截图缩略图），false = 竖向列表
     * @param onCount   过滤后条数回调（用于刷新标题里的计数）
     */
    private <T> Node pagedSearchSection(String prompt, List<T> all,
                                        Function<T, String> searchKey, Function<T, Node> cardOf,
                                        boolean grid, IntConsumer onCount) {
        return pagedSearchSection(prompt, all, searchKey, cardOf, grid, onCount,
                currentSearchKeyword, kw -> currentSearchKeyword = kw);
    }

    /**
     * 「搜索框 + 分批渲染」区块（可指定关键词的存放位置）。
     *
     * @param initialKeyword 初始关键词（删除条目后重建页面时保留上次搜索内容）
     * @param onKeyword      关键词变化回调（把关键词存回调用方自己的字段）
     */
    private <T> Node pagedSearchSection(String prompt, List<T> all,
                                        Function<T, String> searchKey, Function<T, Node> cardOf,
                                        boolean grid, IntConsumer onCount,
                                        String initialKeyword, Consumer<String> onKeyword) {
        VBox section = new VBox(12);
        final String[] keyword = {initialKeyword == null ? "" : initialKeyword};

        FlowPane gridPane = grid ? new FlowPane(12, 12) : null;
        VBox stack = grid ? new VBox(12, gridPane) : new VBox(16);   // 竖向列表的间距与原卡片间距一致
        Pane itemHost = grid ? gridPane : stack;

        Runnable render = () -> {
            String kw = keyword[0] == null ? "" : keyword[0].trim().toLowerCase(Locale.ROOT);
            List<T> filtered = new ArrayList<>();
            for (T item : all) {
                if (kw.isEmpty() || searchKey.apply(item).toLowerCase(Locale.ROOT).contains(kw)) {
                    filtered.add(item);
                }
            }
            if (onCount != null) onCount.accept(filtered.size());
            PageKit.resetBatch(stack, itemHost, filtered, cardOf);
        };
        section.getChildren().add(PageKit.searchField(prompt, keyword[0], kw -> {
            keyword[0] = kw;
            onKeyword.accept(kw);
            render.run();
        }));
        section.getChildren().add(stack);
        render.run();
        return section;
    }

    /** 资源页标题计数：搜索有命中时显示「命中 / 总数」 */
    private static void setCountTitle(Label titleLabel, String name, int shown, int total) {
        titleLabel.setText(shown == total
                ? name + " (" + total + ")"
                : name + " (" + shown + " / " + total + ")");
    }

    /** 「其他」类目里的一行（日志目录 / 游戏根目录），同样走搜索 + 分批渲染 */
    private record DirEntry(String name, String desc, String path) {
    }


    private void addDirComboFilter(VBox content, List<Path> dirs, String gameDir,
                                   String selectedOrigin, Consumer<String> onSelect) {
        if (dirs == null || dirs.isEmpty()) return;

        ComboBox<String> combo = new ComboBox<>();
        combo.getStyleClass().add("select-field");
        combo.setPrefWidth(360);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.getItems().add(I18N.get("page.t0447"));

        Map<String, Path> pathByOrigin = new LinkedHashMap<>();
        for (Path dir : dirs) {
            String origin = ResourceScanner.dirOriginLabel(dir, gameDir);
            if (pathByOrigin.putIfAbsent(origin, dir) == null) {
                combo.getItems().add(origin);
            }
        }
        boolean hasSelected = selectedOrigin != null && pathByOrigin.containsKey(selectedOrigin);
        combo.getSelectionModel().select(hasSelected ? selectedOrigin : I18N.get("page.t0448"));

        Runnable updateTip = () -> {
            String sel = combo.getSelectionModel().getSelectedItem();
            Path p = sel == null ? null : pathByOrigin.get(sel);
            combo.setTooltip(new Tooltip(p == null ? I18N.get("page.t0449") : p.toString()));
        };
        updateTip.run();

        Label hint = new Label(I18N.get("page.t0450"));
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim; -fx-font-weight: 600;");
        hint.setMinWidth(Region.USE_PREF_SIZE);

        // 与原来的右键菜单等价：打开当前选中目录
        Button openBtn = AppIcons.button("folder", I18N.get("page.t0451"));
        openBtn.getStyleClass().add("btn-primary");
        openBtn.setStyle("-fx-padding: 3 10; -fx-font-size: 11px;");
        openBtn.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(8, hint, combo, openBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(combo, Priority.ALWAYS);
        content.getChildren().add(row);

        combo.setOnAction(e -> {
            updateTip.run();
            String sel = combo.getSelectionModel().getSelectedItem();
            onSelect.accept(sel == null || combo.getItems().isEmpty()
                    || combo.getItems().get(0).equals(sel) ? null : sel);
        });
        openBtn.setOnAction(e -> {
            String sel = combo.getSelectionModel().getSelectedItem();
            Path p = sel == null ? null : pathByOrigin.get(sel);
            if (p != null) {
                host.openFile(p.toString());
            } else {
                host.openFile(Paths.get(gameDir, "screenshots").toString());
            }
        });
    }

    /** 截图缩略图解码并发上限：低配机上几十张大 PNG 同时解码会瞬间吃满 CPU/内存，
     *  用 Semaphore 把同时进行的解码压到 4 个，其余排队，滚出视口的缩略图也不会堆积解码任务 */
    private static final java.util.concurrent.Semaphore THUMB_DECODE_SEM = new java.util.concurrent.Semaphore(4);

    /**
     * 后台 + 限流地解码截图缩略图并回填到 ImageView。
     * <p>替代原先在 FX 线程同步 {@code new Image(...)}：解码（含 PNG 解压）是重 CPU/内存操作，
     * 移到 {@code ASYNC_POOL} 执行；Semaphore 保证同时最多 4 个解码，避免批量铺图时把低配机打满。
     */
    private void loadThumbnailThrottled(ImageView thumb, Path shot) {
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                THUMB_DECODE_SEM.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                // 指定目标尺寸后台解码：requestedWidth/Height 让 JavaFX 只解码到缩略图分辨率，省内存
                Image img = new Image(shot.toUri().toString(), 320, 192, true, true, true);
                if (!img.isError()) {
                    Platform.runLater(() -> thumb.setImage(img));
                }
            } finally {
                THUMB_DECODE_SEM.release();
            }
        });
    }

    /** 截图缩略图卡片：点击预览大图，origin 为来源（全局/版本名） */
    private VBox createScreenshotThumb(Path shot, String origin) {
        VBox box = new VBox(6);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.55); -fx-border-color: rgba(0,0,0,0.06);" +
                " -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 8; -fx-cursor: hand;");
        ImageView thumb = new ImageView();
        thumb.setFitWidth(160);
        thumb.setFitHeight(96);
        thumb.setPreserveRatio(true);
        thumb.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 6;");
        loadThumbnailThrottled(thumb, shot);
        Label name = new Label("[" + origin + "] " + shot.getFileName());
        name.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280; -fx-max-width: 176;");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        box.getChildren().addAll(thumb, name);
        box.setOnMouseClicked(e -> host.ui().localImagePreview(shot.toString()));
        return box;
    }

    /** 截图管理页缩略图卡片：点击预览大图，悬停显示删除按钮，右键菜单支持预览/打开所在文件夹/复制路径/删除 */
    private VBox createScreenshotManageThumb(Path shot, String origin) {
        VBox box = new VBox(6);
        box.setAlignment(Pos.CENTER);
        box.setStyle("-fx-background-color: rgba(255,255,255,0.55); -fx-border-color: rgba(0,0,0,0.06);" +
                " -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 8; -fx-cursor: hand;");
        ImageView thumb = new ImageView();
        thumb.setFitWidth(160);
        thumb.setFitHeight(96);
        thumb.setPreserveRatio(true);
        thumb.setStyle("-fx-background-color: #f3f4f6; -fx-background-radius: 6;");
        loadThumbnailThrottled(thumb, shot);

        // 悬停显示的删除按钮（缩略图右上角），点击删除并刷新页面
        Button delBtn = new Button();
        delBtn.setGraphic(AppIcons.icon("close", 12, javafx.scene.paint.Color.WHITE));
        delBtn.setStyle("-fx-background-color: rgba(239,68,68,0.9); -fx-text-fill: white;" +
                " -fx-font-size: 11px; -fx-min-width: 22; -fx-min-height: 22; -fx-max-width: 22; -fx-max-height: 22;" +
                " -fx-background-radius: 11; -fx-padding: 0; -fx-cursor: hand;");
        delBtn.setOpacity(0);
        StackPane thumbWrap = new StackPane(thumb, delBtn);
        StackPane.setAlignment(delBtn, Pos.TOP_RIGHT);
        thumbWrap.setOnMouseEntered(e -> delBtn.setOpacity(1));
        thumbWrap.setOnMouseExited(e -> delBtn.setOpacity(0));
        delBtn.setOnMouseClicked(e -> {
            e.consume(); // 阻止冒泡触发卡片预览
            deleteScreenshot(shot);
        });

        Label name = new Label("[" + origin + "] " + shot.getFileName());
        name.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280; -fx-max-width: 176;");
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        long lastModified = shot.toFile().lastModified();
        String timeStr = java.time.Instant.ofEpochMilli(lastModified)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Label meta = new Label(timeStr + " · " + FormatUtils.formatFileSize(shot.toFile().length()));
        meta.setStyle("-fx-font-size: 10px; -fx-text-fill: #9ca3af;");

        box.getChildren().addAll(thumbWrap, name, meta);
        // 左键预览（右键交给菜单，避免同时触发预览）
        box.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                host.ui().localImagePreview(shot.toString());
            }
        });

        // 右键菜单：预览 / 打开所在文件夹 / 复制路径 / 删除
        ContextMenu menu = new ContextMenu();
        MenuItem previewItem = new MenuItem(I18N.get("page.t0452"));
        previewItem.setOnAction(e -> host.ui().localImagePreview(shot.toString()));
        MenuItem openFolderItem = new MenuItem(I18N.get("page.t0453"));
        openFolderItem.setOnAction(e -> host.openFile(shot.getParent().toString()));
        MenuItem copyPathItem = new MenuItem(I18N.get("page.t0454"));
        copyPathItem.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(shot.toString());
            Clipboard.getSystemClipboard().setContent(cc);
            host.ui().toast(I18N.get("page.t0455"));
        });
        MenuItem deleteItem = new MenuItem(I18N.get("page.t0456"));
        deleteItem.setOnAction(e -> deleteScreenshot(shot));
        menu.getItems().addAll(previewItem, openFolderItem, copyPathItem, deleteItem);
        box.setOnContextMenuRequested(e -> menu.show(box, e.getScreenX(), e.getScreenY()));
        return box;
    }

    /** 删除截图：确认后删除文件并刷新截图管理页 */
    private void deleteScreenshot(Path shot) {
        host.confirmDelete(I18N.get("res.r0009", shot.getFileName()), () -> {
            try {
                Files.deleteIfExists(shot);
                host.ui().toast(I18N.get("page.t0457"));
                host.switchToPage("screenshots");
            } catch (Exception ex) {
                host.ui().toast(I18N.get("page.p0871", ex.getMessage()));
            }
        });
    }


    private HBox createResourceCard(String name, String desc, String... actions) {
        HBox card = new HBox(20);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("settings-card");
        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        Label cardTitle = new Label(name);
        cardTitle.getStyleClass().add("settings-card-title");
        Label description = new Label(desc);
        description.getStyleClass().add("settings-card-desc");
        PageKit.cardTooltip(description);
        info.getChildren().addAll(cardTitle, description);
        HBox btnBox = new HBox(6);
        for (String a : actions) {
            Button btn = new Button(a);
            btn.getStyleClass().add("btn-primary");
            btn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
            btn.setOnAction(e -> host.ui().toast(a + " " + name));
            btnBox.getChildren().add(btn);
        }
        HBox.setHgrow(info, Priority.ALWAYS);
        card.getChildren().addAll(info, btnBox);
        return card;
    }

    private HBox createResourceCard(String name, String desc, String[] actions, Runnable[] handlers) {
        HBox card = new HBox(20);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("settings-card");
        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        Label cardTitle = new Label(name);
        cardTitle.getStyleClass().add("settings-card-title");
        Label description = new Label(desc);
        description.getStyleClass().add("settings-card-desc");
        PageKit.cardTooltip(description);
        info.getChildren().addAll(cardTitle, description);
        HBox btnBox = new HBox(6);
        for (int i = 0; i < actions.length; i++) {
            Button btn = new Button(actions[i]);
            btn.getStyleClass().add("btn-primary");
            btn.setStyle("-fx-padding: 4 10; -fx-font-size: 11px;");
            final int idx = i;
            btn.setOnAction(e -> {
                if (handlers != null && idx < handlers.length && handlers[idx] != null) {
                    handlers[idx].run();
                } else {
                    host.ui().toast(actions[idx] + " " + name);
                }
            });
            btnBox.getChildren().add(btn);
        }
        HBox.setHgrow(info, Priority.ALWAYS);
        card.getChildren().addAll(info, btnBox);
        return card;
    }


    // 截图管理页面 //

    public Node buildScreenshots() {
        VBox root = PageKit.settingsPage(host, I18N.get("page.t0458"), "home");
        HBox pathCard = (HBox) PageKit.settingsCard(I18N.get("page.t0687"), I18N.get("page.t0688"), null);
        TextField pathField = new TextField(host.screenshotDir());
        pathField.getStyleClass().add("input-field");
        pathField.setPrefWidth(250);
        Button browseBtn = AppIcons.button("folder", I18N.get("page.t0459"));
        browseBtn.getStyleClass().add("btn-primary");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle(I18N.get("page.t0460"));
            File f = dc.showDialog(host.stage());
            if (f != null) {
                pathField.setText(f.getAbsolutePath());
                host.config().put("ScreenshotDir", f.getAbsolutePath());
                host.saveConfig();
            }
        });
        pathCard.getChildren().addAll(pathField, browseBtn);
        root.getChildren().add(pathCard);

        // 标题行：截图数量 + 刷新 + 打开截图文件夹
        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setPadding(new Insets(10, 0, 0, 0));
        Label listTitle = new Label(I18N.get("page.t0461"));
        listTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: 600;");
        // 撑满剩余空间，但空间不足时不许被压成省略号
        listTitle.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(listTitle, Priority.ALWAYS);
        Button refreshBtn = AppIcons.button("refresh", I18N.get("page.t0462"));
        refreshBtn.getStyleClass().add("btn-primary");
        refreshBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
        refreshBtn.setOnAction(e -> host.switchToPage("screenshots"));
        Button openDirBtn = AppIcons.button("folder", I18N.get("page.t0463"));
        openDirBtn.getStyleClass().add("btn-primary");
        openDirBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 11px;");
        openDirBtn.setOnAction(e -> {
            File dir = new File(host.screenshotDir());
            if (!dir.exists()) dir.mkdirs();
            host.openFile(dir.toString());
        });
        titleRow.getChildren().addAll(listTitle, refreshBtn, openDirBtn);
        root.getChildren().add(titleRow);

        // 目录筛选按钮：全局 + 各版本隔离 + 自定义截图目录（右键打开对应文件夹）
        String gameDir = host.config().getOrDefault("GameDir", ".minecraft");
        List<Path> ssDirs = new ArrayList<>(ResourceScanner.collectResourceDirs(gameDir, "screenshots"));
        String customScreenshotDir = host.config().get("ScreenshotDir");
        if (customScreenshotDir != null && !customScreenshotDir.isEmpty()) {
            Path customDir = Paths.get(customScreenshotDir);
            if (Files.isDirectory(customDir) && ssDirs.stream()
                    .noneMatch(p -> p.toString().equalsIgnoreCase(customDir.toString()))) {
                ssDirs.add(customDir);
            }
        }
        // 目录切换用下拉框（目录多时一排按钮会占满整行）
        addDirComboFilter(root, ssDirs, gameDir,
                currentScreenshotFilterOrigin, origin -> { currentScreenshotFilterOrigin = origin; host.switchToPage("screenshots"); });

        // 截图缩略图网格：点击预览大图，悬停可删除，右键更多操作
        List<Path> shots = ResourceScanner.listScreenshots(gameDir, currentScreenshotFilterOrigin, host.config().get("ScreenshotDir"));
        listTitle.setText(I18N.get("page.p0872", shots.size() + ")"));
        if (shots.isEmpty()) {
            Label empty = new Label(I18N.get("page.t0464"));
            empty.setStyle("-fx-text-fill: -sl-text-faint; -fx-padding: 20;");
            root.getChildren().add(empty);
        } else {
            // 搜索 + 分批渲染：截图常有几百张，一次只铺 20 张缩略图（点底部按钮继续往下加载）
            root.getChildren().add(pagedSearchSection(I18N.get("page.t0689"), shots,
                    shot -> shot.getFileName().toString(),
                    shot -> createScreenshotManageThumb(shot, ResourceScanner.dirOriginLabel(shot.getParent(), gameDir)),
                    true,
                    n -> listTitle.setText(n == shots.size()
                            ? I18N.get("res.r0010", n)
                            : I18N.get("res.r0011", n, shots.size())),
                    currentScreenshotKeyword, kw -> currentScreenshotKeyword = kw));
        }
        return root;
    }

}



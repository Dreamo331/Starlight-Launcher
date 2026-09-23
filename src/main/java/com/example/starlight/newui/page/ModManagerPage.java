package com.example.starlight.newui.page;

import com.example.starlight.ModsApi.LocalModMetadata;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ResourceScanner;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.example.starlight.ModsApi.ModTranslations;
import com.example.starlight.plugin.PluginManager;
import com.example.starlight.plugin.PluginMetadata;
import com.example.starlight.modfile.ModFileFormatter;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.FileChooser;
import java.util.concurrent.ConcurrentHashMap;

/** 
顶部导航「模组」页（上传 .jar / 列表 / 启用停用 / 重命名 / 删除）
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class ModManagerPage {

    private final LauncherContext host;

    /** 「模组」页：已安装模组列表容器与数量标签（模组页重建时更新引用） */
    private VBox modListContainer;
    private Label modCountLabel;
    /** 「模组」页：已安装模组的本地中文名缓存（key = 文件绝对路径，value = 字典条目；未收录的文件不会写入本表） */
    private final Map<String, ModTranslations.Mod> localModTranslations = new ConcurrentHashMap<>();
    /** 「模组」页：已扫描过的模组文件指纹（key = 绝对路径，value = 最后修改时间+大小），文件变化后自动重新识别 */
    private final Map<String, String> localModFingerprints = new ConcurrentHashMap<>();
    /** 「模组」页：正在后台识别元数据的模组路径，避免重复提交扫描任务 */
    private final java.util.Set<String> localModScanInFlight = ConcurrentHashMap.newKeySet();
    /** 「模组」页：当前搜索关键词（空串=不过滤），同时匹配文件名、modId 与中文名 */
    private String modSearchKeyword = "";

    /** 清空本地模组译名与指纹缓存（字典更新后强制重新识别） */
    public void clearCache() {
        localModTranslations.clear();
        localModFingerprints.clear();
    }

    public ModManagerPage(LauncherContext host) {
        this.host = host;
    }

    public Node build() {
        VBox root = new VBox(16);
        root.setPadding(new Insets(0, 4, 0, 0));

        // 标题行：返回按钮 + 页面名称
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Button backBtn = AppIcons.button("back", "返回");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> {
            // 返回首页并同步顶部标签选中状态
            host.selectHomeTab();
            host.switchToPage("home");
        });
        Label titleLabel = new Label("模组");
        titleLabel.getStyleClass().add("content-title");
        // 内测功能标注
        Label betaBadge = new Label("内测");
        betaBadge.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #f97316;"
                + " -fx-background-color: rgba(249,115,22,0.15); -fx-background-radius: 999; -fx-padding: 2 8;");
        header.getChildren().addAll(backBtn, titleLabel, betaBadge);
        root.getChildren().add(header);

        // 内测提示条
        Label betaNotice = new Label("启动器 MOD（扩展）功能当前为内测版，可能存在缺陷或行为变更，请谨慎使用。");
        betaNotice.setStyle("-fx-font-size: 12px; -fx-text-fill: #b45309;"
                + " -fx-background-color: rgba(245,158,11,0.12); -fx-background-radius: 8; -fx-padding: 8 12;");
        betaNotice.setWrapText(true);
        root.getChildren().add(betaNotice);

        // ===== 上传模组：选择 .jar 文件复制到 Starlight-Launcher\Mod =====
        Label uploadStatus = new Label("支持多选，重复上传会覆盖同名文件");
        uploadStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
        uploadStatus.setWrapText(true);
        Button chooseBtn = AppIcons.button("folder", "选择 .jar 模组文件");
        chooseBtn.getStyleClass().add("btn-primary");
        chooseBtn.setOnAction(e -> uploadModFiles(uploadStatus));
        HBox uploadRow = new HBox(10, chooseBtn, uploadStatus);
        uploadRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(uploadStatus, Priority.ALWAYS);
        root.getChildren().add(PageKit.settingsCard("上传模组",
                "选择本地的 .jar 模组文件，复制到 Starlight-Launcher\\Mod 目录", uploadRow));

        // ===== 已安装模组列表 =====
        VBox modArea = new VBox(8);
        // 搜索框：同时匹配文件名、modId 与中文名（如输入「机械动力」能找到 create-*.jar）
        TextField modSearchField = new TextField();
        modSearchField.setPromptText("搜索已安装模组（文件名 / modId / 中文名，如「机械动力」）");
        modSearchField.getStyleClass().add("input-field");
        modSearchField.setMaxWidth(Double.MAX_VALUE);
        modSearchField.setText(modSearchKeyword);
        modSearchField.textProperty().addListener((obs, oldValue, newValue) -> {
            modSearchKeyword = newValue == null ? "" : newValue.trim();
            refreshList();
        });
        modCountLabel = new Label();
        modCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #9ca3af;");
        modListContainer = new VBox(6);
        modArea.getChildren().addAll(modSearchField, modCountLabel, modListContainer);
        refreshList();
        root.getChildren().add(PageKit.settingsCard("已安装模组",
                "Starlight-Launcher\\Mod 目录下的模组文件；已收录于中文名字典的模组会额外显示中文名", modArea));
        return root;
    }

    /** 选择 .jar 模组文件并复制到 Starlight-Launcher\Mod 目录 */
    private void uploadModFiles(Label statusLabel) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 .jar 模组文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Minecraft 模组 (*.jar)", "*.jar"));
        List<File> files = chooser.showOpenMultipleDialog(host.stage());
        if (files == null || files.isEmpty()) return;

        File modDir = new File("Starlight-Launcher", "Mod");
        int ok = 0;
        List<String> copied = new ArrayList<>();
        for (File f : files) {
            try {
                if (!modDir.exists() && !modDir.mkdirs()) {
                    throw new IOException("无法创建目录: " + modDir.getAbsolutePath());
                }
                Files.copy(f.toPath(), new File(modDir, f.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                ok++;
                copied.add(f.getName());
            } catch (Exception ex) {
                System.err.println("[Mod] 复制失败: " + f.getName() + " -> " + ex.getMessage());
            }
        }

        if (statusLabel != null) {
            if (ok == 0) {
                statusLabel.setText("复制失败，请检查文件与目录权限");
            } else if (ok < files.size()) {
                statusLabel.setText("成功 " + ok + " 个，失败 " + (files.size() - ok) + " 个: " + String.join("、", copied));
            } else {
                statusLabel.setText("已复制: " + String.join("、", copied));
            }
        }
        if (ok > 0) {
            host.ui().toast("已复制 " + ok + " 个模组文件到 Starlight-Launcher\\Mod");
        }
        // 已安装列表与数量原地刷新
        refreshList();
    }

    /** 刷新「已安装模组」列表与数量：读取 Starlight-Launcher\Mod 目录下所有 .jar 文件（含 .disabled 禁用态）。
     *  含 launcher-plugin.json 的 jar 视为插件，额外显示 类型 / 运行状态 / 启用开关（改 PluginManager 状态）；
     *  普通 jar 支持 .disabled 重命名启停；所有行支持删除与右键菜单（打开文件夹/复制路径/重命名）。 */

    public void refreshList() {
        if (modListContainer == null || modCountLabel == null) return;
        modListContainer.getChildren().clear();
        File modDir = new File("Starlight-Launcher", "Mod");
        File[] jars = modDir.isDirectory()
                ? modDir.listFiles((d, name) -> {
                    String n = name.toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".jar") || n.endsWith(".jar.disabled");
                })
                : null;
        if (jars == null || jars.length == 0) {
            modCountLabel.setText("共 0 个模组");
            Label empty = new Label("暂无模组，点击上方「选择 .jar 模组文件」上传");
            empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
            modListContainer.getChildren().add(empty);
            return;
        }
        java.util.Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        // 中文名异步识别：已识别的先展示，未识别的按文件名展示并在识别完成后自动刷新
        scanLocalModTranslationsAsync(jars);

        String keyword = modSearchKeyword == null ? "" : modSearchKeyword.trim();
        String lowerKeyword = keyword.toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        PluginManager pluginManager = PluginManager.getInstance();
        for (File jar : jars) {
            String fname = jar.getName();
            boolean disabledFile = fname.endsWith(".disabled");
            String fileBase = disabledFile ? fname.substring(0, fname.length() - ".disabled".length()) : fname;

            // 中文名字典条目（可能尚未识别出来，此时为 null）
            ModTranslations.Mod translation = localModTranslations.get(jar.getAbsolutePath());

            // 关键词过滤：文件名 / modId / 中文名 / 英文名 / 缩写 任一命中即保留
            if (!lowerKeyword.isEmpty() && !ModFileFormatter.matchesKeyword(fname, translation, lowerKeyword)) continue;
            shown++;

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);

            // 若该 jar 是插件（含 launcher-plugin.json），用展示名/版本替代文件名
            PluginMetadata metadata = findMetadataForJar(jar, pluginManager);
            String displayName = metadata != null
                    ? metadata.name() + " " + metadata.version()
                    : fileBase;
            Label name = new Label("" + displayName + (disabledFile ? "（已禁用）" : ""));
            name.setStyle("-fx-font-size: 13px;"
                    + (disabledFile ? " -fx-text-fill: -sl-text-faint;" : ""));
            // 让「模组名」独占可伸缩空间并允许省略，其余元信息保持固有宽度 ——
            // 否则空间紧张时会被一起压成「…」，整行都读不出来
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label size = new Label(FormatUtils.formatFileSize(jar.length()));
            size.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
            size.setMinWidth(Region.USE_PREF_SIZE);
            // 命中中文名字典时补一个中文名标签（如「（机械动力）」），点击可打开 MC 百科
            if (translation != null && translation.hasChineseName()) {
                Label chineseName = new Label("（" + translation.getName() + "）");
                chineseName.setStyle("-fx-font-size: 12px; -fx-text-fill: #2563eb; -fx-cursor: hand;");
                chineseName.setMinWidth(Region.USE_PREF_SIZE);
                String mcmodUrl = ModTranslations.MOD.getMcmodUrl(translation);
                if (!mcmodUrl.isEmpty()) {
                    chineseName.setTooltip(new Tooltip("MC 百科：" + mcmodUrl));
                    chineseName.setOnMouseClicked(ev -> host.openWebUrl(mcmodUrl));
                }
                row.getChildren().addAll(name, chineseName, size);
            } else {
                row.getChildren().addAll(name, size);
            }

            boolean isPlugin = metadata != null && pluginManager != null;
            if (isPlugin) {
                // 插件行额外显示：类型 / 运行状态 / 启用开关（开关即改 PluginManager 状态）
                Label typeLabel = new Label("[" + metadata.typeLabel() + "]");
                typeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
                typeLabel.setMinWidth(Region.USE_PREF_SIZE);
                Label status = new Label();
                status.setStyle("-fx-font-size: 11px;");
                status.setMinWidth(Region.USE_PREF_SIZE);
                CheckBox toggle = PageKit.toggle(pluginManager.isEnabled(metadata.id()));
                toggle.setOnAction(e -> {
                    boolean ok = pluginManager.setEnabled(metadata.id(), toggle.isSelected());
                    updatePluginStatus(metadata, status, pluginManager);
                    if (ok) {
                        host.ui().toast("插件 " + metadata.name() + " " + (toggle.isSelected() ? "已启用" : "已禁用"));
                    } else {
                        // 启动失败：状态已回滚，开关回弹为禁用
                        toggle.setSelected(false);
                        updatePluginStatus(metadata, status, pluginManager);
                        host.ui().toast("插件 " + metadata.name() + " 启动失败（请查看启动器控制台日志）");
                    }
                });
                updatePluginStatus(metadata, status, pluginManager);
                row.getChildren().addAll(typeLabel, status, toggle);
            } else {
                // 普通 jar：.disabled 重命名启停按钮
                Label toggleBtn = new Label(disabledFile ? "启用" : "禁用");
                toggleBtn.setStyle("-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 2 8; -fx-background-radius: 999;"
                        + " -fx-text-fill: " + (disabledFile ? "#16a34a" : "#f59e0b") + ";"
                        + " -fx-background-color: " + (disabledFile ? "rgba(34,197,94,0.12)" : "rgba(245,158,11,0.12)") + ";");
                toggleBtn.setOnMouseClicked(ev -> toggleModFile(jar));
                row.getChildren().add(toggleBtn);
            }

            // 删除按钮（所有行）
            Label deleteBtn = new Label();
            deleteBtn.setGraphic(AppIcons.icon("close", 13, javafx.scene.paint.Color.web("#dc2626")));
            deleteBtn.setStyle("-fx-text-fill: #dc2626; -fx-cursor: hand; -fx-font-size: 13px; -fx-padding: 2 6;");
            deleteBtn.setOnMouseClicked(ev -> deleteModFile(jar));
            row.getChildren().add(deleteBtn);

            // 右键菜单：启用/禁用（普通 jar）/ 打开所在文件夹 / 复制路径 / 重命名（普通 jar）/ 删除
            ContextMenu menu = new ContextMenu();
            if (!isPlugin) {
                MenuItem toggleItem = new MenuItem(disabledFile ? "启用" : "禁用");
                toggleItem.setOnAction(e -> toggleModFile(jar));
                menu.getItems().add(toggleItem);
            }
            MenuItem openFolderItem = new MenuItem("打开所在文件夹");
            openFolderItem.setOnAction(e -> host.openFile(jar.getParentFile() != null ? jar.getParentFile().getAbsolutePath() : ""));
            MenuItem copyPathItem = new MenuItem("复制路径");
            copyPathItem.setOnAction(e -> copyModPath(jar));
            menu.getItems().addAll(openFolderItem, copyPathItem);
            if (!isPlugin) {
                MenuItem renameItem = new MenuItem("重命名");
                renameItem.setOnAction(e -> renameModFile(jar));
                menu.getItems().add(renameItem);
            }
            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> deleteModFile(jar));
            menu.getItems().add(deleteItem);
            row.setOnContextMenuRequested(e -> menu.show(row, e.getScreenX(), e.getScreenY()));

            modListContainer.getChildren().add(row);
        }

        if (lowerKeyword.isEmpty()) {
            modCountLabel.setText("共 " + jars.length + " 个模组");
        } else {
            modCountLabel.setText("匹配 " + shown + " / " + jars.length + " 个模组（关键词：" + keyword + "）");
            if (shown == 0) {
                Label empty = new Label("没有匹配的模组；中文名依赖内置字典，未收录的模组只能按文件名或 modId 搜索");
                empty.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-faint;");
                modListContainer.getChildren().add(empty);
            }
        }
    }


    /**
     * 后台识别已安装模组的 modId 并反查中文名。
     *
     * <p>识别顺序：先读 jar 内元数据拿 modId（fabric.mod.json / mods.toml / mcmod.info 等），
     * 命中字典最好；读不到再退回按文件名匹配。
     * 结果按「文件路径 + 修改时间 + 大小」缓存，列表刷新时不会重复解压；
     * 一旦识别出新的中文名就回到 FX 线程刷新列表（识别完即不再触发，不会反复刷新）。
     */
    private void scanLocalModTranslationsAsync(File[] jars) {
        List<File> pending = new ArrayList<>();
        for (File jar : jars) {
            String pathKey = jar.getAbsolutePath();
            if (localModScanInFlight.contains(pathKey)) continue;
            if (!ModFileFormatter.fingerprintOf(jar).equals(localModFingerprints.get(pathKey))) pending.add(jar);
        }
        if (pending.isEmpty()) return;

        for (File jar : pending) localModScanInFlight.add(jar.getAbsolutePath());
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            int found = 0;
            for (File jar : pending) {
                String pathKey = jar.getAbsolutePath();
                try {
                    LocalModMetadata.Info info = LocalModMetadata.read(jar.toPath());
                    ModTranslations.Mod translation = ModTranslations.MOD.getMod(info.modId(), jar.getName());
                    if (translation != null) {
                        localModTranslations.put(pathKey, translation);
                        found++;
                    }
                    localModFingerprints.put(pathKey, ModFileFormatter.fingerprintOf(jar));
                } catch (Exception e) {
                    // 读取失败（损坏 jar / 权限问题）：记录指纹避免反复重试，界面上按文件名展示
                    localModFingerprints.put(pathKey, ModFileFormatter.fingerprintOf(jar));
                } finally {
                    localModScanInFlight.remove(pathKey);
                }
            }
            if (found > 0) {
                Platform.runLater(this::refreshList);
            }
        });
    }


    /** 用系统浏览器打开网页地址（失败仅提示，不影响界面） */

    private void toggleModFile(File mod) {
        try {
            String fname = mod.getName();
            File target;
            if (fname.endsWith(".disabled")) {
                target = new File(mod.getParentFile(), fname.substring(0, fname.length() - ".disabled".length()));
            } else {
                target = new File(mod.getParentFile(), fname + ".disabled");
            }
            Files.move(mod.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            host.ui().toast(target.getName().endsWith(".disabled") ? "已禁用 " + target.getName() : "已启用 " + target.getName());
            refreshList();
        } catch (Exception e) {
            host.ui().toast("操作失败: " + e.getMessage());
        }
    }

    /** 删除模组：插件先停止并清除启用状态，再确认删除文件 */
    private void deleteModFile(File mod) {
        host.confirmDelete("确定要删除模组 \"" + mod.getName() + "\" 吗？此操作不可恢复！", () -> {
            try {
                // 已启用的插件：先停进程并清除启用状态，避免状态文件残留
                PluginManager pm = PluginManager.getInstance();
                PluginMetadata meta = findMetadataForJar(mod, pm);
                if (meta != null && pm != null && pm.isEnabled(meta.id())) {
                    pm.setEnabled(meta.id(), false);
                }
                Files.deleteIfExists(mod.toPath());
                host.ui().toast("已删除: " + mod.getName());
                refreshList();
            } catch (Exception ex) {
                host.ui().toast("删除失败: " + ex.getMessage());
            }
        });
    }

    /** 复制模组文件绝对路径到剪贴板 */
    private void copyModPath(File mod) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(mod.getAbsolutePath());
        Clipboard.getSystemClipboard().setContent(cc);
        host.ui().toast("已复制路径");
    }

    /** 重命名普通模组文件（禁用态重命名后仍保持禁用；插件不支持重命名，避免破坏插件关联） */
    private void renameModFile(File mod) {
        String fname = mod.getName();
        boolean disabled = fname.endsWith(".disabled");
        String oldName = disabled ? fname.substring(0, fname.length() - ".disabled".length()) : fname;
        host.ui().input("重命名模组", "输入新的文件名（自动补全 .jar 后缀）", oldName, newName -> {
            String trimmed = newName == null ? "" : newName.trim();
            if (trimmed.isEmpty()) return;
            if (!trimmed.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                trimmed = trimmed + ".jar";
            }
            String finalName = disabled ? trimmed + ".disabled" : trimmed;
            try {
                File target = new File(mod.getParentFile(), finalName);
                if (target.exists()) {
                    host.ui().toast("已存在同名文件: " + finalName);
                    return;
                }
                Files.move(mod.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                host.ui().toast("已重命名为: " + finalName);
                refreshList();
            } catch (Exception ex) {
                host.ui().toast("重命名失败: " + ex.getMessage());
            }
        });
    }

    /** 按文件名匹配 jar 对应的插件元数据（Windows 目录路径大小写不敏感，按文件名比较最稳妥） */
    private PluginMetadata findMetadataForJar(File jar, PluginManager pluginManager) {
        if (pluginManager == null) return null;
        for (PluginMetadata metadata : pluginManager.allMetadata()) {
            if (metadata.sourceJar() != null
                    && metadata.sourceJar().getFileName().toString()
                            .equalsIgnoreCase(jar.getName())) {
                return metadata;
            }
        }
        return null;
    }

    /** 刷新插件的运行状态标签：运行中（绿）/ 已启用未运行（黄）/ 已禁用（灰） */
    private void updatePluginStatus(PluginMetadata metadata, Label status, PluginManager pluginManager) {
        if (pluginManager.isEnabled(metadata.id())) {
            if (pluginManager.isRunning(metadata.id())) {
                status.setText("运行中");
                status.setStyle("-fx-font-size: 11px; -fx-text-fill: #22c55e;");
            } else {
                status.setText("已启用（未运行）");
                status.setStyle("-fx-font-size: 11px; -fx-text-fill: #eab308;");
            }
        } else {
            status.setText("已禁用");
            status.setStyle("-fx-font-size: 11px; -fx-text-fill: -sl-text-faint;");
        }
    }

    /** 根据「首页快速管理MOD」开关同步顶部导航栏「模组」页的显隐 */
}





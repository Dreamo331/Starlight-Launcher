package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.util.FormatUtils;
import com.example.starlight.util.ResourceScanner;
import com.example.starlight.gui.UIGeneralControlClass;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.example.starlight.config.GameDirManager;
import com.example.starlight.util.GameDirInitializer;
import java.util.ArrayList;
import java.util.List;

/** 
设置 → 游戏目录页（多目录管理 / 切换 / 重命名 / 删除）
（从 LauncherView 抽离，方法体逐字搬运，样式未改动） */
public final class GameDirPage {

    private final LauncherContext host;


    public GameDirPage(LauncherContext host) {
        this.host = host;
    }

    // 设置：游戏目录（多目录管理） //

    /**
     * 设置 - 游戏目录：上层「当前游戏目录」入口 + 下层「多目录管理」列表。
     *
     * <p>多目录列表持久化在 GameFolders（路径）与 GameDirNames（显示名称），当前使用的目录仍记录在
     * GameDir —— 启动、版本安装、资源管理等既有逻辑全部读 GameDir，因此切换目录 = 改写 GameDir，
     * 不需要改动启动链路。
     */
    public Node build() {
        VBox root = PageKit.settingsPage(host, "游戏目录");
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        String activePath = GameDirManager.activePath(host.config());

        // 当前游戏目录：输入框 + 浏览 + 应用（保留原来单目录的使用习惯，浏览即添加并切换） //
        TextField dirField = new TextField(activePath);
        dirField.getStyleClass().add("input-field");
        dirField.setPrefWidth(300);
        Button browseBtn = AppIcons.button("folder", "浏览");
        browseBtn.getStyleClass().add("btn-primary");
        browseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择 .minecraft 目录");
            File f = dc.showDialog(host.stage());
            if (f != null) host.ui().toast(switchGameDir(f.getAbsolutePath()));
        });
        Button applyBtn = AppIcons.button("check", "应用");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setOnAction(e -> host.ui().toast(switchGameDir(dirField.getText())));
        dirField.setOnAction(e -> host.ui().toast(switchGameDir(dirField.getText())));
        HBox row = new HBox(10, dirField, browseBtn, applyBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("当前游戏目录",
                "游戏数据存储位置（.minecraft）；启动、下载与资源管理均使用该目录", row));

        // 多目录管理：列表头（数量 + 添加） //
        HBox listHeader = new HBox(10);
        listHeader.setAlignment(Pos.CENTER_LEFT);
        Label listTitle = new Label("已保存的目录 (" + entries.size() + ")");
        // 显式指定文字颜色（浅色背景上默认文字色偏白看不清），深色主题由 CSS 覆盖
        listTitle.getStyleClass().add("section-title");
        HBox.setHgrow(listTitle, Priority.ALWAYS);
        Button addBtn = PageKit.actionButton("+ 添加目录");
        addBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择要管理的 .minecraft 目录");
            File f = dc.showDialog(host.stage());
            if (f != null) host.ui().toast(addGameDir(f.getAbsolutePath()));
        });
        listHeader.getChildren().addAll(listTitle, addBtn);
        root.getChildren().add(listHeader);

        // 每个目录一张卡片：设为当前 / 重命名 / 打开 / 移除 //
        for (GameDirManager.GameDirEntry entry : entries) {
            root.getChildren().add(buildGameDirCard(entry,
                    GameDirManager.samePath(entry.getPath(), activePath)));
        }

        // 模组管理折叠卡（重构 §6.4：原 ModPage 并入本页，旧导航 id sidebarMod 由 NavRouter 重定向） //
        root.getChildren().add(buildModSectionCard());
        return root;
    }

    /** 「模组管理」折叠卡：内测警告 + 首页快速管理 MOD 开关（内容来自原 ModPage，逐字保留） */
    private Node buildModSectionCard() {
        VBox content = new VBox(10);

        // 内测功能标注（原 ModPage 横幅）
        Label betaNotice = AppIcons.label("warning",
                "模组（启动器扩展）功能当前为内测版，可能存在缺陷或行为变更，请谨慎使用。");
        betaNotice.setStyle("-fx-font-size: 12px; -fx-text-fill: #b45309;"
                + " -fx-background-color: rgba(245,158,11,0.12); -fx-background-radius: 8; -fx-padding: 8 12;");
        betaNotice.setWrapText(true);
        content.getChildren().add(betaNotice);

        // 「首页快速管理MOD」开关（原 ModPage 卡片内容，配置键不变）
        CheckBox quickModToggle = PageKit.toggle(
                "true".equalsIgnoreCase(host.config().getOrDefault("HomeQuickManageMod", "false")));
        quickModToggle.setOnAction(e -> {
            host.config().put("HomeQuickManageMod", String.valueOf(quickModToggle.isSelected()));
            host.saveConfig();
            host.updateModTabVisibility();
            host.toast("首页快速管理MOD: " + (quickModToggle.isSelected() ? "开启" : "关闭"));
        });
        content.getChildren().add(PageKit.settingsCard("首页快速管理MOD",
                "开启后在顶部导航栏显示「模组」页，便于快速管理模组（将用户上传的 .jar 模组文件复制到指定位置）",
                quickModToggle));

        return PageKit.collapsibleSection("模组管理",
                "内测功能 · 首页快速管理 MOD 开关", content, false, null);
    }

    /**
     * 单个游戏目录卡片：第一行是名称 + 路径信息，第二行是操作按钮。
     * 按钮单独占一行并用整行宽度，避免与长路径挤在一行导致按钮文字被压成省略号。
     */
    private Node buildGameDirCard(GameDirManager.GameDirEntry entry, boolean isActive) {
        VBox card = new VBox(8);
        card.getStyleClass().add("settings-card");

        VBox info = new VBox(3);
        info.getStyleClass().add("settings-card-info");
        Label nameLabel = new Label(entry.getName());
        nameLabel.getStyleClass().add("settings-card-title");
        Label descLabel = new Label();
        descLabel.getStyleClass().add("settings-card-desc");
        // 路径超长/窗口变窄时换行收缩，而不是把按钮挤变形
        descLabel.setWrapText(true);
        info.getChildren().addAll(nameLabel, descLabel);
        card.getChildren().add(info);

        // 版本数统计是磁盘 IO：先显示路径占位，后台统计后回填（缓存 TTL 内不重复扫）
        fillGameDirDescAsync(entry, descLabel);

        HBox actions = new HBox(6);
        actions.setAlignment(Pos.CENTER_RIGHT);
        if (isActive) {
            Label badge = AppIcons.label("bullet", "当前使用");
            badge.getStyleClass().add("game-dir-current-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            actions.getChildren().add(badge);
        } else {
            Button useBtn = PageKit.actionButton("设为当前");
            useBtn.setOnAction(e -> host.ui().toast(switchGameDir(entry.getPath())));
            actions.getChildren().add(useBtn);
        }

        Button renameBtn = PageKit.actionButton("重命名");
        renameBtn.setOnAction(e -> host.ui().input("重命名目录", "为该目录设置一个便于识别的名称（只改名称，不改路径）",
                "例如：主目录 / 测试端", entry.getName(),
                name -> host.ui().toast(renameGameDir(entry.getPath(), name))));

        Button openBtn = PageKit.actionButton("打开");
        openBtn.setOnAction(e -> {
            if (!entry.exists()) {
                host.ui().toast("目录不存在: " + entry.getDisplayPath());
                return;
            }
            host.openFile(entry.getPath());
        });

        Button removeBtn = PageKit.actionButton("移除");
        removeBtn.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #dc2626;");
        removeBtn.setOnAction(e -> host.confirmDelete(
                "确定要从列表中移除目录 \"" + entry.getName() + "\" 吗？\n仅移除管理记录，不会删除磁盘上的任何文件。",
                () -> host.ui().toast(removeGameDir(entry.getPath()))));

        actions.getChildren().addAll(renameBtn, openBtn, removeBtn);
        card.getChildren().add(actions);
        return card;
    }

    /** 异步填充目录卡片的说明文字：路径立即显示，版本数后台统计后回填（缓存 TTL 内直接同步返回） */
    private void fillGameDirDescAsync(GameDirManager.GameDirEntry entry, Label descLabel) {
        String displayPath = entry.getDisplayPath();
        if (!entry.exists()) {
            descLabel.setText(displayPath + "  ·  目录不存在");
            return;
        }
        Integer cached = GameDirManager.cachedVersionCount(entry.getPath());
        if (cached != null) {
            descLabel.setText(displayPath + (cached > 0 ? "  ·  已安装 " + cached + " 个版本" : "  ·  未发现已安装版本"));
            return;
        }
        // 缓存未命中：先显示路径占位，后台统计后回填
        descLabel.setText(displayPath + "  ·  统计版本中…");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            int versions = entry.countVersions();
            GameDirManager.putVersionCount(entry.getPath(), versions);
            Platform.runLater(() -> descLabel.setText(displayPath
                    + (versions > 0 ? "  ·  已安装 " + versions + " 个版本" : "  ·  未发现已安装版本")));
        });
    }

    /** 加入列表并切换为当前游戏目录（已存在则只切换）；设置页调用，返回提示文案 */
    private String switchGameDir(String path) {
        return switchGameDir(path, "sidebarGameDir");
    }

    /**
     * 加入列表并切换为当前游戏目录（已存在则只切换）。
     *
     * @param reloadPage 切换后需要重新挂载的页面（设置页 / 版本选择页都是「构建一次后缓存」的节点，
     *                   不重挂的话看到的仍是切换前的旧内容）
     * @return 提示文案
     */

    public String switchGameDir(String path, String reloadPage) {
        String target = path == null ? "" : path.trim();
        if (target.isEmpty()) return "请输入游戏目录路径";

        String before = GameDirManager.activePath(host.config());
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        boolean existed = GameDirManager.containsPath(entries, target);
        if (!existed) {
            entries.add(new GameDirManager.GameDirEntry(GameDirManager.suggestName(target, entries), target));
        }
        GameDirManager.store(host.config(), entries, target);
        applyGameDirChanges(before, reloadPage);

        StringBuilder msg = new StringBuilder(existed ? "已切换当前游戏目录" : "已添加并切换当前游戏目录");
        // 切换后检测目录健康状态：未初始化的补齐骨架；launcher_profiles.json 缺失/不合法时重新生成
        GameDirInitializer.InitResult health = GameDirInitializer.detectAndFix(Path.of(target));
        if (health.ok() && (health.created() > 0 || health.filesCreated() > 0)) {
            msg.append("；").append(health.created() > 0
                    ? "已初始化目录骨架"
                    : "launcher_profiles.json 缺失或不合法，已重新生成");
        } else if (!health.ok()) {
            msg.append("；").append(health.summary());
        }
        String version = host.config().getOrDefault("Version", "");
        // 目录里没有当前选中的版本时提前提示，避免启动时才发现版本不存在
        if (!version.isEmpty() && !ResourceScanner.versionInstalledIn(target, version)) {
            msg.append("；该目录未找到版本 ").append(version).append("，请到「版本选择」重新选择");
        }
        return msg.toString();
    }

    /** 仅把目录加入多目录列表，不改变当前使用的目录；返回提示文案 */
    private String addGameDir(String path) {
        String target = path == null ? "" : path.trim();
        if (target.isEmpty()) return "请输入游戏目录路径";

        String before = GameDirManager.activePath(host.config());
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        if (GameDirManager.containsPath(entries, target)) return "该目录已在列表中";
        entries.add(new GameDirManager.GameDirEntry(GameDirManager.suggestName(target, entries), target));
        GameDirManager.store(host.config(), entries, before);
        applyGameDirChanges(before);
        String msg = "已添加目录，可在列表中点击「设为当前」启用";
        // 添加同样做一次健康检测，保证列表里的每个目录都是可用状态
        GameDirInitializer.InitResult health = GameDirInitializer.detectAndFix(Path.of(target));
        if (health.ok() && (health.created() > 0 || health.filesCreated() > 0)) {
            msg += "；" + (health.created() > 0
                    ? "已初始化目录骨架"
                    : "launcher_profiles.json 缺失或不合法，已重新生成");
        } else if (!health.ok()) {
            msg += "；" + health.summary();
        }
        return msg;
    }

    /** 重命名目录显示名称（只改名称，不改路径）；返回提示文案 */
    private String renameGameDir(String path, String newName) {
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        int index = GameDirManager.indexOf(entries, path);
        if (index < 0) return "目录不在列表中";
        String name = GameDirManager.sanitizeName(newName);
        if (name.isEmpty()) return "名称不能为空";

        String before = GameDirManager.activePath(host.config());
        entries.set(index, new GameDirManager.GameDirEntry(name, entries.get(index).getPath()));
        GameDirManager.store(host.config(), entries, before);
        applyGameDirChanges(before);
        return "已重命名为: " + name;
    }

    /** 从多目录列表移除目录（只删管理记录，不动磁盘文件；至少保留一项）；返回提示文案 */
    private String removeGameDir(String path) {
        List<GameDirManager.GameDirEntry> entries = GameDirManager.load(host.config());
        if (entries.size() <= 1) return "至少需要保留一个游戏目录";
        int index = GameDirManager.indexOf(entries, path);
        if (index < 0) return "目录不在列表中";

        String before = GameDirManager.activePath(host.config());
        entries.remove(index);
        // store 会校正 GameDir：移除的若是当前目录，自动切换到列表中第一项
        GameDirManager.store(host.config(), entries, before);
        applyGameDirChanges(before);
        return "已从列表移除（磁盘文件未删除）";
    }

    /**
     * 保存目录列表变更：写回 GameDir/GameFolders/GameDirNames，
     * 当前目录变化时重建按目录扫描的页面缓存，最后重新挂载 reloadPage（默认「游戏目录」设置页）
     */
    private void applyGameDirChanges(String beforeActive) {
        applyGameDirChanges(beforeActive, "sidebarGameDir");
    }

    /** 保存目录列表变更并重新挂载 reloadPage；未传页面时只刷新缓存 */
    private void applyGameDirChanges(String beforeActive, String reloadPage) {
        host.saveConfig();

        if (!GameDirManager.samePath(beforeActive, GameDirManager.activePath(host.config()))) {
            // 当前目录变了：重建按目录扫描的页面缓存（版本选择页左侧切换栏 + 右侧列表也在这里刷新）
            host.refreshDependentPageCaches();
        }
        // 设置页/版本选择页都是「构建一次后缓存」的节点：saveConfig 只重建缓存，
        // 必须重新挂载，否则点击「设为当前 / 切换文件夹 / 重命名」后页面看不出变化
        if (reloadPage != null && !reloadPage.isEmpty()) host.switchToPage(reloadPage);
    }

}


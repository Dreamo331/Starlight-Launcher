package com.example.starlight.newui.page.settings;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.PageKit;

import javafx.scene.Node;
import javafx.scene.layout.VBox;

import com.example.starlight.ModsApi.CurseForgeAPI;
import com.example.starlight.ModsApi.ModDictionaryManager;
import com.example.starlight.download.DownloadProvider;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.GameResourceCompleter;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.version.LoaderInstallEngine;
import com.example.starlight.version.VersionDownloadService;
import com.example.starlight.version.VersionManifest;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import java.io.File;
import java.util.List;

/** 
设置 → 下载页（下载源 / 并发 / CurseForge / 中文字典）
（从 LauncherView 抽离，逻辑与样式均未改动） */
public final class DownloadSettingsPage {

    private final LauncherContext ctx;

    public DownloadSettingsPage(LauncherContext ctx) {
        this.ctx = ctx;
    }

    public Node build() {
        VBox root = PageKit.settingsPage(ctx, "下载");

        // 下载源
        ComboBox<String> sourceCombo = new ComboBox<>();
        sourceCombo.getItems().addAll("BMCLAPI", "Mojang", "MCBBS");
        String curSource = ctx.config().getOrDefault("DownloadSource", "BMCLAPI");
        sourceCombo.setValue(sourceCombo.getItems().contains(curSource) ? curSource : "BMCLAPI");
        sourceCombo.getStyleClass().add("select-field");
        sourceCombo.setOnAction(e -> {
            String source = sourceCombo.getValue();
            if (source == null) return;
            ctx.config().put("DownloadSource", source);
            ctx.saveConfig();
            // 立即应用下载源：版本清单 / 原版安装 / 资源补全 / 加载器安装全部切换
            DownloadProvider provider = VersionDownloadService.applyDownloadSource(source);
            GameResourceCompleter.setDownloadProvider(provider);
            LoaderInstallEngine.setDownloadProvider(provider);
            VersionManifest.setDownloadProvider(provider);
            ctx.toast("下载源已切换: " + source);
        });
        root.getChildren().add(PageKit.settingsCard("下载源", "游戏文件与资源下载的来源服务器（MCBBS 镜像已停止服务，选择后自动回退）", sourceCombo));

        // 并发下载线程数
        int curThreads = PageKit.parseIntSafe(ctx.config().getOrDefault("DownloadThreads", "4"), 4);
        curThreads = Math.max(1, Math.min(16, curThreads));
        Label threadsLabel = new Label("当前: " + curThreads);
        threadsLabel.getStyleClass().add("black-value-label");
        // 锁固有宽度：卡片挤压时数值不许变成「当前…」
        threadsLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider threadsSlider = new Slider(1, 16, curThreads);
        threadsSlider.getStyleClass().add("black-ticks");
        threadsSlider.setShowTickLabels(true);
        threadsSlider.setMajorTickUnit(3);
        threadsSlider.setBlockIncrement(1);
        threadsSlider.setPrefWidth(200);
        threadsSlider.valueProperty().addListener((obs, old, val) -> threadsLabel.setText("当前: " + val.intValue()));
        Button saveThreads = AppIcons.button("save", "保存");
        saveThreads.getStyleClass().add("btn-primary");
        saveThreads.setOnAction(e -> {
            int val = (int) threadsSlider.getValue();
            ctx.config().put("DownloadThreads", String.valueOf(val));
            ctx.saveConfig();
            // 立即生效：分块下载 / 资源补全并发数全部切换
            DownloadSettings.setDownloadThreads(val);
            ctx.toast("并发下载数已保存: " + val);
        });
        HBox threadsRow = new HBox(10, threadsSlider, threadsLabel, saveThreads);
        threadsRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("并发下载数", "同时下载的分块数量（数值越大下载越快，占用带宽越高）", threadsRow));

        // CurseForge 数据源连通性自检（API Key 硬编码在 CurseForgeAPI，此处仅验证能否取到数据）
        Button cfTestBtn = AppIcons.button("signal", "测试连接");
        cfTestBtn.getStyleClass().add("btn-primary");
        Label cfStatus = new Label("未测试");
        cfStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        cfTestBtn.setOnAction(e -> {
            cfTestBtn.setDisable(true);
            cfStatus.setText("测试中...");
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                String result = CurseForgeAPI.testConnection();
                Platform.runLater(() -> {
                    cfStatus.setText(result);
                    cfTestBtn.setDisable(false);
                });
            });
        });
        HBox cfRow = new HBox(12, cfTestBtn, cfStatus);
        cfRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("CurseForge 数据源", "", cfRow));

        // ===== Mod 中文名字典（中文搜索的数据基础）=====
        Label dictStatus = new Label("正在读取字典...");
        dictStatus.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        dictStatus.setWrapText(true);
        refreshDictionaryStatus(dictStatus);

        TextField modDictUrlField = new TextField(ModDictionaryManager.resolveUrl(ctx.config(),
                ModDictionaryManager.CONFIG_URL_MOD, ModDictionaryManager.DEFAULT_MOD_URL));
        TextField packDictUrlField = new TextField(ModDictionaryManager.resolveUrl(ctx.config(),
                ModDictionaryManager.CONFIG_URL_MODPACK, ModDictionaryManager.DEFAULT_MODPACK_URL));
        for (TextField field : List.of(modDictUrlField, packDictUrlField)) {
            field.getStyleClass().add("input-field");
            field.setPrefWidth(420);
            // 允许随容器宽度伸缩；否则右侧控件偏窄时地址会被截成「https://raw.githubuserc」
            field.setMaxWidth(Double.MAX_VALUE);
        }
        modDictUrlField.textProperty().addListener((obs, oldValue, newValue) -> {
            ctx.config().put(ModDictionaryManager.CONFIG_URL_MOD, newValue == null ? "" : newValue.trim());
            UIGeneralControlClass.saveConfig(ctx.config());
        });
        packDictUrlField.textProperty().addListener((obs, oldValue, newValue) -> {
            ctx.config().put(ModDictionaryManager.CONFIG_URL_MODPACK, newValue == null ? "" : newValue.trim());
            UIGeneralControlClass.saveConfig(ctx.config());
        });

        Label modUrlLabel = new Label("模组字典地址");
        modUrlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        Label packUrlLabel = new Label("整合包字典地址");
        packUrlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");

        Button dictUpdateBtn = AppIcons.button("refresh", "更新字典");
        dictUpdateBtn.getStyleClass().add("btn-primary");
        Button dictClearBtn = AppIcons.button("trash", "清除更新缓存");
        Button dictOpenDirBtn = AppIcons.button("folder", "打开缓存目录");
        dictClearBtn.getStyleClass().add("btn-primary");
        dictOpenDirBtn.getStyleClass().add("btn-primary");
        Label dictResult = new Label();
        dictResult.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        dictResult.setWrapText(true);

        dictUpdateBtn.setOnAction(e -> {
            dictUpdateBtn.setDisable(true);
            dictResult.setText("更新中...（模组字典约 1.7 MB，请稍候）");
            String modUrl = modDictUrlField.getText() == null || modDictUrlField.getText().trim().isEmpty()
                    ? ModDictionaryManager.DEFAULT_MOD_URL : modDictUrlField.getText().trim();
            String packUrl = packDictUrlField.getText() == null || packDictUrlField.getText().trim().isEmpty()
                    ? ModDictionaryManager.DEFAULT_MODPACK_URL : packDictUrlField.getText().trim();
            UIGeneralControlClass.ASYNC_POOL.submit(() -> {
                ModDictionaryManager.UpdateResult modResult =
                        ModDictionaryManager.updateFromRemote(modUrl, ModDictionaryManager.MOD_CACHE_FILE);
                ModDictionaryManager.UpdateResult packResult =
                        ModDictionaryManager.updateFromRemote(packUrl, ModDictionaryManager.MODPACK_CACHE_FILE);
                Platform.runLater(() -> {
                    dictUpdateBtn.setDisable(false);
                    dictResult.setText("模组：" + modResult.message() + "　整合包：" + packResult.message());
                    refreshDictionaryStatus(dictStatus);
                    // 字典变了：已安装模组的中文名缓存整体失效，重新识别
                    ctx.clearLocalModCache();
                    ctx.refreshModList();
                });
            });
        });

        dictClearBtn.setOnAction(e -> {
            boolean cleared = ModDictionaryManager.clearCache(ModDictionaryManager.MOD_CACHE_FILE)
                    | ModDictionaryManager.clearCache(ModDictionaryManager.MODPACK_CACHE_FILE);
            ctx.clearLocalModCache();
            ctx.refreshModList();
            refreshDictionaryStatus(dictStatus);
            dictResult.setText(cleared ? "已清除运行时更新缓存，回退为随包内置字典"
                    : "没有可清除的运行时缓存（当前即随包内置字典）");
        });

        dictOpenDirBtn.setOnAction(e -> {
            File dir = ModDictionaryManager.DICTIONARY_DIR.toFile();
            if (!dir.exists() && !dir.mkdirs()) {
                ctx.toast("无法创建目录: " + dir.getAbsolutePath());
                return;
            }
            ctx.openFile(dir.getAbsolutePath());
        });

        HBox dictButtonRow = new HBox(12, dictUpdateBtn, dictClearBtn, dictOpenDirBtn, dictResult);
        dictButtonRow.setAlignment(Pos.CENTER_LEFT);

        VBox dictBox = new VBox(8);
        // 右侧控件区给足宽度并允许拉伸：之前固定 470 上限，窄窗口下字典条目、地址、
        // 按钮全被压成一列，既不好看也读不全
        dictBox.setMinWidth(420);
        dictBox.setPrefWidth(560);
        dictBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dictBox, Priority.ALWAYS);
        dictBox.getChildren().addAll(dictStatus, modUrlLabel, modDictUrlField,
                packUrlLabel, packDictUrlField, dictButtonRow);
        root.getChildren().add(PageKit.settingsCard("Mod 中文搜索字典",
                "",
                dictBox));

        return root;
    }

    /** 在后台统计并展示两本字典的条目数与来源（设置页打开时调用） */
    private void refreshDictionaryStatus(Label statusLabel) {
        statusLabel.setText("正在读取字典...");
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            ModDictionaryManager.DictionaryStatus modStatus = ModDictionaryManager.status(
                    ModDictionaryManager.MOD_RESOURCE, ModDictionaryManager.MOD_CACHE_FILE);
            ModDictionaryManager.DictionaryStatus packStatus = ModDictionaryManager.status(
                    ModDictionaryManager.MODPACK_RESOURCE, ModDictionaryManager.MODPACK_CACHE_FILE);
            String text = "模组字典：" + describeDictionary(modStatus)
                    + "　整合包字典：" + describeDictionary(packStatus);
            Platform.runLater(() -> statusLabel.setText(text));
        });
    }

    /** 字典状态的可读描述：条目数 + 来源 + 更新时间 */
    private static String describeDictionary(ModDictionaryManager.DictionaryStatus status) {
        StringBuilder builder = new StringBuilder();
        builder.append(status.entryCount()).append(" 条（").append(status.describeSource()).append('）');
        if (status.updatedAt() != null) {
            builder.append('，').append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(java.time.LocalDateTime.ofInstant(status.updatedAt(), java.time.ZoneId.systemDefault())));
        }
        return builder.toString();
    }
}


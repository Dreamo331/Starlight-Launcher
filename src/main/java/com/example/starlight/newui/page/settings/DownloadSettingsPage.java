package com.example.starlight.newui.page.settings;

import com.example.starlight.ModsApi.CurseForgeAPI;
import com.example.starlight.ModsApi.ModDictionaryManager;
import com.example.starlight.download.DownloadSettings;
import com.example.starlight.download.mod.DownloadCoordinator;
import com.example.starlight.download.source.DownloadProvider;
import com.example.starlight.download.task.GameResourceCompleter;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.download.task.LoaderInstallEngine;
import com.example.starlight.newui.LauncherContext;
import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.newui.ui.PageKit;
import com.example.starlight.version.VersionDownloadService;
import com.example.starlight.download.task.VersionManifest;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import java.io.File;
import java.util.List;

/** 
 * 设置 → 下载页（下载源 / 并发 / 高级下载设置 / CurseForge / 中文字典）。
 * <p>
 * （下载源与并发从 LauncherView 抽离，逻辑与样式均未改动）
 */
public final class DownloadSettingsPage {

    /** 控件标签列宽度：固定后各行的输入框左边缘对齐 */
    private static final double LABEL_WIDTH = 132;
    /** 数值输入框宽度 */
    private static final double FIELD_WIDTH = 96;

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

        // 并发下载线程数（全局配额上限：调大/调小会就地重建 download.mod 线程池）
        int curThreads = Math.max(DownloadSettings.MIN_THREADS,
                Math.min(DownloadSettings.SETTINGS_THREADS_MAX,
                        PageKit.parseIntSafe(ctx.config().getOrDefault(DownloadSettings.KEY_THREADS,
                                String.valueOf(DownloadSettings.DEFAULT_THREADS)), DownloadSettings.DEFAULT_THREADS)));
        Label threadsLabel = new Label("当前: " + curThreads);
        threadsLabel.getStyleClass().add("black-value-label");
        // 锁固有宽度：卡片挤压时数值不许变成「当前…」
        threadsLabel.setMinWidth(Region.USE_PREF_SIZE);
        Slider threadsSlider = new Slider(DownloadSettings.MIN_THREADS, DownloadSettings.SETTINGS_THREADS_MAX, curThreads);
        threadsSlider.getStyleClass().add("black-ticks");
        threadsSlider.setShowTickLabels(true);
        threadsSlider.setMajorTickUnit(4);
        threadsSlider.setBlockIncrement(1);
        threadsSlider.setPrefWidth(200);
        threadsSlider.valueProperty().addListener((obs, old, val) -> threadsLabel.setText("当前: " + val.intValue()));
        Button saveThreads = AppIcons.button("save", "保存");
        saveThreads.getStyleClass().add("btn-primary");
        saveThreads.setOnAction(e -> {
            int val = (int) threadsSlider.getValue();
            ctx.config().put(DownloadSettings.KEY_THREADS, String.valueOf(val));
            ctx.saveConfig();
            // 立即生效：全局线程池配额（DownloadCoordinator）+ 未接入 mod 的旧链路
            DownloadSettings.setDownloadThreads(val);
            DownloadSettings.applyAll();
            boolean resized = DownloadCoordinator.getInstance().setThreadLimit(val);
            ctx.toast(resized ? ("并发下载数已保存: " + val + "（线程池已调整）")
                    : ("并发下载数已保存: " + val));
        });
        HBox threadsRow = new HBox(10, threadsSlider, threadsLabel, saveThreads);
        threadsRow.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(PageKit.settingsCard("并发下载数",
                "全局同时下载的分块数量（数值越大越快，占用带宽越高；上限 32，默认 "
                        + DownloadSettings.DEFAULT_THREADS + "）", threadsRow));

        // 高级下载设置（折叠）
        root.getChildren().add(buildAdvancedCard());

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

        // Mod 中文名字典（中文搜索的数据基础） //
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

    // 高级下载设置（折叠） //
    /**
     * 折叠的「高级下载设置」卡片。
     *
     * <p>参数直接对应 {@code docs/2026-09-30-下载提速优化技术文档.md} §7 参数建议表里
     * <b>已经接线</b>的那几项（分块阈值 / 分裂点 / 最小碎片 / 慢速触发线 / 限速）：
     * 每一项都由 {@link DownloadSettings} 承载并被 download.mod 每轮循环读取，保存即生效；
     * 播种周期、连接超时、慢速掐线、合并重试仍是类内常量，故意不放出来（调错直接伤成功率）。
     *
     * <p>默认收起：这些都是「调错会更慢」的参数，不该让普通用户一进下载页就面对一堆数字。
     */
    private Node buildAdvancedCard() {
        VBox content = new VBox(12);
        content.setMinWidth(0);

        // —— 分块 ——
        NumberField threshold = numberField(DownloadSettings.KEY_THRESHOLD_MB,
                DownloadSettings.DEFAULT_THRESHOLD_MB, DownloadSettings.MIN_THRESHOLD_MB, DownloadSettings.MAX_THRESHOLD_MB, "MB");
        NumberField piece = numberField(DownloadSettings.KEY_PIECE_LIMIT_KB,
                DownloadSettings.DEFAULT_PIECE_LIMIT_KB, DownloadSettings.MIN_PIECE_LIMIT_KB, DownloadSettings.MAX_PIECE_LIMIT_KB, "KB");
        NumberField split = numberField(DownloadSettings.KEY_SPLIT_PERCENT,
                DownloadSettings.DEFAULT_SPLIT_PERCENT, DownloadSettings.MIN_SPLIT_PERCENT, DownloadSettings.MAX_SPLIT_PERCENT, "%");
        content.getChildren().addAll(
                fieldRow("分块阈值", threshold.node(),
                        "小于该大小的文件不开多线程，单连接下完（省掉合并开销），默认 "
                                + DownloadSettings.DEFAULT_THRESHOLD_MB + " MB"),
                fieldRow("最小碎片", piece.node(),
                        "剩余量小于该值的分块不再切分，越小分块越细但连接更多，默认 "
                                + DownloadSettings.DEFAULT_PIECE_LIMIT_KB + " KB"),
                fieldRow("分裂点", split.node(),
                        "在剩余量的该比例处切出新分块，值越小新线程越早接管尾部，默认 "
                                + DownloadSettings.DEFAULT_SPLIT_PERCENT + "%"));

        content.getChildren().add(sectionDivider("速度"));

        // —— 速度 ——
        NumberField lowSpeed = numberField(DownloadSettings.KEY_LOW_SPEED_KBPS,
                DownloadSettings.DEFAULT_LOW_SPEED_KBPS, DownloadSettings.MIN_LOW_SPEED_KBPS,
                DownloadSettings.MAX_LOW_SPEED_KBPS, "KB/s");
        content.getChildren().add(fieldRow("慢速触发线", lowSpeed.node(),
                "全局实测速度低于该值时才为慢文件追加分块线程（文档建议 512~1024 KB/s），默认 "
                        + DownloadSettings.DEFAULT_LOW_SPEED_KBPS + " KB/s"));

        // 限速：开关 + 数值（关闭时数值框禁用，避免「填了没生效」的困惑）。
        // 这一行不单独放「保存」——本区块统一由底部「保存高级设置」提交，避免同一页两个保存语义。
        NumberField limit = numberField(DownloadSettings.KEY_SPEED_LIMIT_KBPS,
                DownloadSettings.DEFAULT_SPEED_LIMIT_KBPS, 0, DownloadSettings.MAX_SPEED_LIMIT_KBPS, "KB/s");
        TextField limitField = limit.field();
        CheckBox limitToggle = PageKit.toggle(DownloadSettings.isSpeedLimitEnabled());
        limitToggle.selectedProperty().addListener((o, was, on) -> {
            limitField.setDisable(!on);
            if (on && "0".equals(limitField.getText())) {
                // 打开开关却留着 0（= 不限速）会让人以为限速没生效，给个常用值
                limitField.setText("1024");
            }
        });
        limitField.setDisable(!DownloadSettings.isSpeedLimitEnabled());
        HBox limitRow = new HBox(10, limitToggle, limit.node());
        limitRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(fieldRow("下载限速", limitRow,
                "默认不限速。开启后只做全局令牌桶（所有分块共享一个额度），不做线程级限速"));

        content.getChildren().add(sectionDivider("超时与换源"));

        // —— 停滞看门狗 ——
        // 这是补全链路上最关键的存活参数：单个 read 超过该时长没有拿到任何字节，
        // 就判定该源「连得上但不干活」，立刻换下一个候选源。
        // 注意它按「单次读取间隔」计，不是文件总时长 —— 慢但在推进的大文件不受影响。
        // 2026-09-30 增强 B：该值只作用于「最后可用候选」，前面的候选用更快的
        // DownloadFirstTryStallSec（默认 5s，可手动改 starlight-client.ini）。
        NumberField stall = numberField(DownloadSettings.KEY_STALL_TIMEOUT_SEC,
                DownloadSettings.DEFAULT_STALL_TIMEOUT_SEC, DownloadSettings.MIN_STALL_TIMEOUT_SEC,
                DownloadSettings.MAX_STALL_TIMEOUT_SEC, "秒");
        content.getChildren().add(fieldRow("停滞判定", stall.node(),
                "单个数据块超过该时长没到就判该源卡死并换源。太小会误杀慢速大文件，默认 "
                        + DownloadSettings.DEFAULT_STALL_TIMEOUT_SEC + " 秒"
                        + "（找到最后一条可用源时会自动放宽 2 倍，避免反复掐断唯一能用的路；"
                        + "前面的候选用更快的 5 秒快速判定，坏源代价降到 1/3）"));

        NumberField connect = numberField(DownloadSettings.KEY_CONNECT_TIMEOUT_SEC,
                DownloadSettings.DEFAULT_CONNECT_TIMEOUT_SEC, DownloadSettings.MIN_CONNECT_TIMEOUT_SEC,
                DownloadSettings.MAX_CONNECT_TIMEOUT_SEC, "秒");
        content.getChildren().add(fieldRow("建连超时", connect.node(),
                "与源建立连接的最长等待，默认 " + DownloadSettings.DEFAULT_CONNECT_TIMEOUT_SEC + " 秒"));

        content.getChildren().add(sectionDivider("操作"));

        // —— 保存 / 恢复默认 ——
        Label hint = new Label("保存后立即生效，无需重启（并发下载数会就地调整线程池）");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        hint.setWrapText(true);
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMinWidth(0);

        Button saveAll = AppIcons.button("save", "保存高级设置");
        saveAll.getStyleClass().add("btn-primary");
        saveAll.setMinWidth(Region.USE_PREF_SIZE);
        saveAll.setOnAction(e -> {
            saveConfigKey(DownloadSettings.KEY_THRESHOLD_MB, String.valueOf(
                    clampField(threshold.field(), DownloadSettings.MIN_THRESHOLD_MB, DownloadSettings.MAX_THRESHOLD_MB)));
            saveConfigKey(DownloadSettings.KEY_PIECE_LIMIT_KB, String.valueOf(
                    clampField(piece.field(), DownloadSettings.MIN_PIECE_LIMIT_KB, DownloadSettings.MAX_PIECE_LIMIT_KB)));
            saveConfigKey(DownloadSettings.KEY_SPLIT_PERCENT, String.valueOf(
                    clampField(split.field(), DownloadSettings.MIN_SPLIT_PERCENT, DownloadSettings.MAX_SPLIT_PERCENT)));
            saveConfigKey(DownloadSettings.KEY_LOW_SPEED_KBPS, String.valueOf(
                    clampField(lowSpeed.field(), DownloadSettings.MIN_LOW_SPEED_KBPS, DownloadSettings.MAX_LOW_SPEED_KBPS)));
            int kbps = limitToggle.isSelected() ? clampField(limitField, 0, DownloadSettings.MAX_SPEED_LIMIT_KBPS) : 0;
            saveConfigKey(DownloadSettings.KEY_SPEED_LIMIT_KBPS, String.valueOf(kbps));
            saveConfigKey(DownloadSettings.KEY_STALL_TIMEOUT_SEC, String.valueOf(
                    clampField(stall.field(), DownloadSettings.MIN_STALL_TIMEOUT_SEC, DownloadSettings.MAX_STALL_TIMEOUT_SEC)));
            saveConfigKey(DownloadSettings.KEY_CONNECT_TIMEOUT_SEC, String.valueOf(
                    clampField(connect.field(), DownloadSettings.MIN_CONNECT_TIMEOUT_SEC, DownloadSettings.MAX_CONNECT_TIMEOUT_SEC)));
            // 统一从配置表整体加载，避免「只改了字段没改 DownloadSettings」的分叉
            DownloadSettings.applyAll(ctx.config());
            ctx.toast("高级下载设置已保存");
        });

        Button resetAll = AppIcons.button("refresh", "恢复默认");
        resetAll.setMinWidth(Region.USE_PREF_SIZE);
        resetAll.setOnAction(e -> {
            DownloadSettings.resetToDefaults();
            DownloadSettings.applyAll();
            DownloadCoordinator.getInstance().setThreadLimit(DownloadSettings.getDownloadThreads());
            // 把默认值显式写回配置表（而不是删键）：saveClientConfig 是「读-改-写」，
            // 删掉的键会被磁盘上的旧值补回来，只有显式赋值才能真正覆盖。
            ctx.config().put(DownloadSettings.KEY_THREADS, String.valueOf(DownloadSettings.getDownloadThreads()));
            ctx.config().put(DownloadSettings.KEY_PIECE_LIMIT_KB, String.valueOf(DownloadSettings.getPieceLimitKb()));
            ctx.config().put(DownloadSettings.KEY_SPLIT_PERCENT, String.valueOf(DownloadSettings.getSplitPercent()));
            ctx.config().put(DownloadSettings.KEY_THRESHOLD_MB, String.valueOf(DownloadSettings.getMultithreadThresholdMb()));
            ctx.config().put(DownloadSettings.KEY_LOW_SPEED_KBPS, String.valueOf(DownloadSettings.getLowSpeedThresholdKbps()));
            ctx.config().put(DownloadSettings.KEY_SPEED_LIMIT_KBPS, String.valueOf(DownloadSettings.getSpeedLimitKbps()));
            ctx.config().put(DownloadSettings.KEY_STALL_TIMEOUT_SEC, String.valueOf(DownloadSettings.getStallTimeoutSec()));
            ctx.config().put(DownloadSettings.KEY_FIRST_TRY_STALL_SEC, String.valueOf(DownloadSettings.getFirstTryStallSec()));
            ctx.config().put(DownloadSettings.KEY_CONNECT_TIMEOUT_SEC, String.valueOf(DownloadSettings.getConnectTimeoutSec()));
            // saveConfig 顺带重建设置页 → 各控件回显默认值
            ctx.saveConfig();
            ctx.toast("下载设置已恢复默认（含并发下载数）");
        });

        HBox actionRow = new HBox(10, hint, resetAll, saveAll);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(actionRow);

        return collapsibleCard("高级下载设置",
                "分块、分裂与限速参数；调错可能更慢，不确定就保持默认", content);
    }

    /**
     * 折叠卡片：标题行点击展开/收起，右侧箭头指示状态。
     *
     * <p>复用 {@code settings-card} 外观，展开区挂在同一张卡里——不额外引样式类，
     * 浅色/深色主题都跟随既有样式表。
     */
    private static VBox collapsibleCard(String title, String desc, Node content) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-card-title");
        Label descLabel = new Label(desc);
        descLabel.getStyleClass().add("settings-card-desc");
        descLabel.setWrapText(true);
        VBox info = new VBox(3, titleLabel, descLabel);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label chevron = new Label("▾  展开");
        chevron.getStyleClass().add("settings-card-desc");
        chevron.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(20, info, chevron);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setCursor(Cursor.HAND);

        content.setVisible(false);
        content.setManaged(false);

        header.setOnMouseClicked(e -> {
            boolean open = !content.isVisible();
            content.setVisible(open);
            content.setManaged(open);
            chevron.setText(open ? "▴  收起" : "▾  展开");
        });

        VBox card = new VBox(12, header, content);
        card.getStyleClass().addAll("settings-card", "settings-card-stacked");
        return card;
    }

    /** 一行「标签 + 控件」；desc 非空时置于控件下方做灰字说明 */
    private static VBox fieldRow(String labelText, Node control, String desc) {
        Label label = new Label(labelText);
        label.setMinWidth(LABEL_WIDTH);
        label.setPrefWidth(LABEL_WIDTH);
        HBox row = new HBox(12, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(3, row);
        box.setMinWidth(0);
        if (desc != null && !desc.isEmpty()) {
            Label descLabel = new Label(desc);
            descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
            descLabel.setWrapText(true);
            // 说明文字与控件列左对齐（标签列宽 + 行间距）
            descLabel.setPadding(new Insets(0, 0, 0, LABEL_WIDTH + 12));
            box.getChildren().add(descLabel);
        }
        return box;
    }

    /** 分区小标题（速度 / 操作） */
    private static Label sectionDivider(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim; -fx-font-weight: bold;");
        label.setPadding(new Insets(4, 0, 0, 0));
        return label;
    }

    /**
     * 一行数值输入的「输入框 + 单位后缀」组合。
     *
     * <p>之所以不直接返回 {@link TextField}：调用方既要把控件挂进卡片，又要读它的值，
     * 而带单位的行在界面上是一个 HBox；把两者一起交出去比到处 {@code lookup} 干净。
     */
    private record NumberField(TextField field, HBox node) {
    }

    /**
     * 数值输入框 + 单位后缀。
     *
     * <p>初值优先取配置项（用户在 ini 里手改过也认），非法/越界回落默认值；
     * 只允许数字，越界在失焦时收紧到范围内并回写，避免留一个 0 或 99999 在框里。
     */
    private NumberField numberField(String key, int fallback, int min, int max, String unit) {
        int initial = Math.max(min, Math.min(max,
                PageKit.parseIntSafe(ctx.config().getOrDefault(key, String.valueOf(fallback)), fallback)));
        TextField field = new TextField(String.valueOf(initial));
        field.getStyleClass().add("input-field");
        field.setPrefWidth(FIELD_WIDTH);
        field.setMaxWidth(FIELD_WIDTH);
        field.setMinWidth(FIELD_WIDTH);
        // 只收数字：非数字键直接吞掉，省得保存时才发现输了中文
        field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d*") ? change : null));
        field.focusedProperty().addListener((o, was, focused) -> {
            if (!focused) field.setText(String.valueOf(clampField(field, min, max)));
        });
        Label unitLabel = new Label(unit);
        unitLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: -sl-text-dim;");
        unitLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(8, field, unitLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return new NumberField(field, box);
    }

    /** 读数值框当前值并收紧到 [min,max]（空串按 min 处理，避免 NPE） */
    private static int clampField(TextField field, int min, int max) {
        int value = PageKit.parseIntSafe(field.getText(), min);
        return Math.max(min, Math.min(max, value));
    }

    /** 写一个配置项并立即落盘（不重建设置页：连续保存多个键时重建会打断当前输入） */
    private void saveConfigKey(String key, String value) {
        ctx.config().put(key, value);
        com.example.starlight.main.ConfigManager.saveClientConfig(ctx.config());
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

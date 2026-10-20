/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.offline.Skin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * 离线皮肤设置面板 —— 集成在启动器窗口内（替代独立 Stage 的 SkinSettingsController）。
 * 支持来源：默认 / 内置 / 本地文件 / LittleSkin / CSL API，实时预览。
 * 视觉样式由 style.css 的 .skin-* 类驱动（浅色/深色主题自适应）。
 */
public class SkinSettingsPanel extends VBox {

    private Label previewNameLabel, previewTypeBadge;
    private ImageView previewSkinImage;
    private ToggleGroup sourceToggleGroup;
    private ToggleButton srcDefaultBtn, srcBuiltinBtn, srcLocalBtn, srcLittleSkinBtn, srcCslApiBtn;
    private VBox skinConfigArea;
    private Button applyBtn;

    private final AccountManager.Account account;
    private Skin editingSkin;
    private final Runnable onApplied;
    private final Runnable closeHandler;
    private String pendingLocalSkinPath, pendingLocalCapePath;
    private Skin.Type selectedBuiltinType;
    private String cslApiUrl;
    private CheckBox skinRemoveBgBox, capeRemoveBgBox;
    private boolean removeSkinBgFlag, removeCapeBgFlag;

    public SkinSettingsPanel(AccountManager.Account account, Runnable onApplied, Runnable closeHandler) {
        super(14);
        getStyleClass().add("skin-settings-panel");
        // 固定弹窗尺寸：样式与高度不随内容变化（内容区内部滚动兜底）
        setMinHeight(440);
        setPrefHeight(440);
        setMaxHeight(440);
        this.account = account;
        this.onApplied = onApplied;
        this.closeHandler = closeHandler;
        this.editingSkin = account.getSkin();
        this.pendingLocalSkinPath = editingSkin.getLocalSkinPath();
        this.pendingLocalCapePath = editingSkin.getLocalCapePath();
        this.removeSkinBgFlag = editingSkin.isRemoveSkinBg();
        this.removeCapeBgFlag = editingSkin.isRemoveCapeBg();
        this.selectedBuiltinType = (editingSkin.getType().ordinal() >= Skin.Type.ALEX.ordinal()
                && editingSkin.getType().ordinal() <= Skin.Type.ZURI.ordinal())
                ? editingSkin.getType() : Skin.Type.STEVE;
        this.cslApiUrl = editingSkin.getCslApi();

        // 主体：左侧预览卡 + 右侧设置卡（标题行已由弹窗标题栏承担，面板内不再重复） //
        HBox centerContent = new HBox(20);
        VBox.setVgrow(centerContent, Priority.ALWAYS);

        // 左侧：预览卡
        VBox previewSection = new VBox(12);
        previewSection.setAlignment(Pos.CENTER);
        previewSection.setPrefWidth(182);
        previewSection.getStyleClass().add("skin-preview-card");
        Label previewSectionTitle = new Label("预览");
        previewSectionTitle.getStyleClass().add("skin-card-label");
        StackPane avatarFrame = new StackPane();
        avatarFrame.getStyleClass().add("skin-avatar-frame");
        previewSkinImage = new ImageView();
        previewSkinImage.setFitWidth(118);
        previewSkinImage.setFitHeight(118);
        previewSkinImage.setPreserveRatio(true);
        avatarFrame.getChildren().add(previewSkinImage);
        previewNameLabel = new Label(account.name);
        previewNameLabel.getStyleClass().add("skin-player-name");
        previewTypeBadge = new Label("宽模型 (Steve)");
        previewTypeBadge.getStyleClass().add("skin-model-badge");
        previewSection.getChildren().addAll(previewSectionTitle, avatarFrame, previewNameLabel, previewTypeBadge);

        // 右侧：设置卡
        VBox settingsSection = new VBox(10);
        VBox.setVgrow(settingsSection, Priority.ALWAYS);
        settingsSection.getStyleClass().add("skin-config-card");

        Label sourceLabel = new Label("皮肤来源");
        sourceLabel.getStyleClass().add("skin-card-label");

        sourceToggleGroup = new ToggleGroup();
        FlowPane sourceButtons = new FlowPane(6, 6);
        sourceButtons.getStyleClass().add("skin-source-seg");
        srcDefaultBtn = AppIcons.toggle("check", "默认");
        srcDefaultBtn.setToggleGroup(sourceToggleGroup);
        srcDefaultBtn.setUserData("DEFAULT");
        srcDefaultBtn.getStyleClass().add("skin-source-btn");
        srcBuiltinBtn = AppIcons.toggle("package", "内置");
        srcBuiltinBtn.setToggleGroup(sourceToggleGroup);
        srcBuiltinBtn.setUserData("BUILTIN");
        srcBuiltinBtn.getStyleClass().add("skin-source-btn");
        srcLocalBtn = AppIcons.toggle("folder", "本地文件");
        srcLocalBtn.setToggleGroup(sourceToggleGroup);
        srcLocalBtn.setUserData("LOCAL_FILE");
        srcLocalBtn.getStyleClass().add("skin-source-btn");
        srcLittleSkinBtn = AppIcons.toggle("globe", "LittleSkin");
        srcLittleSkinBtn.setToggleGroup(sourceToggleGroup);
        srcLittleSkinBtn.setUserData("LITTLE_SKIN");
        srcLittleSkinBtn.getStyleClass().add("skin-source-btn");
        srcCslApiBtn = AppIcons.toggle("network", "CSL API");
        srcCslApiBtn.setToggleGroup(sourceToggleGroup);
        srcCslApiBtn.setUserData("CUSTOM_SKIN_LOADER_API");
        srcCslApiBtn.getStyleClass().add("skin-source-btn");
        sourceButtons.getChildren().addAll(srcDefaultBtn, srcBuiltinBtn, srcLocalBtn, srcLittleSkinBtn, srcCslApiBtn);

        skinConfigArea = new VBox(10);
        skinConfigArea.getStyleClass().add("skin-config-area");

        // 内容区滚动兜底：来源/文件内容超高时滚动，保证弹窗 660x440 固定
        ScrollPane configScroll = new ScrollPane(skinConfigArea);
        configScroll.getStyleClass().add("skin-config-scroll");
        configScroll.setFitToWidth(true);
        configScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(configScroll, Priority.ALWAYS);

        settingsSection.getChildren().addAll(sourceLabel, sourceButtons, configScroll);
        centerContent.getChildren().addAll(previewSection, settingsSection);

        // 底部：按钮 //
        HBox bottomButtons = new HBox(10);
        bottomButtons.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = AppIcons.button("close", "取消");
        cancelBtn.getStyleClass().add("skin-btn-ghost");
        cancelBtn.setOnAction(e -> closeHandler.run());
        applyBtn = AppIcons.button("check", "应用");
        applyBtn.getStyleClass().add("skin-btn-primary");
        applyBtn.setOnAction(e -> handleApply());
        bottomButtons.getChildren().addAll(cancelBtn, applyBtn);

        getChildren().addAll(centerContent, bottomButtons);

        sourceToggleGroup.selectedToggleProperty().addListener((obs, old, nev) -> {
            if (nev != null) onSourceChanged((String) nev.getUserData());
        });

        populateUI();
    }

    private void populateUI() {
        Skin.Type currentType = editingSkin.getType();
        String sourceKey = switch (currentType) {
            case DEFAULT -> "DEFAULT";
            case ALEX, ARI, EFE, KAI, MAKENA, NOOR, STEVE, SUNNY, ZURI -> "BUILTIN";
            case LOCAL_FILE -> "LOCAL_FILE";
            case LITTLE_SKIN -> "LITTLE_SKIN";
            case CUSTOM_SKIN_LOADER_API -> "CUSTOM_SKIN_LOADER_API";
        };
        for (Toggle toggle : sourceToggleGroup.getToggles()) {
            if (sourceKey.equals(toggle.getUserData())) { sourceToggleGroup.selectToggle(toggle); break; }
        }
        if (sourceToggleGroup.getSelectedToggle() == null) sourceToggleGroup.selectToggle(srcDefaultBtn);
        onSourceChanged(sourceKey);
        updatePreview();
    }

    private void onSourceChanged(String sourceKey) {
        skinConfigArea.getChildren().clear();
        Skin.Type skinType = Skin.Type.DEFAULT;
        switch (sourceKey) {
            case "DEFAULT" -> { skinType = Skin.Type.DEFAULT; showDefaultHint(); }
            case "BUILTIN" -> { skinType = selectedBuiltinType; showBuiltinGrid(); }
            case "LOCAL_FILE" -> { skinType = Skin.Type.LOCAL_FILE; showLocalFileSelector(); }
            case "LITTLE_SKIN" -> { skinType = Skin.Type.LITTLE_SKIN; showLittleSkinHint(); }
            case "CUSTOM_SKIN_LOADER_API" -> { skinType = Skin.Type.CUSTOM_SKIN_LOADER_API; showCslApiInput(); }
        }
        if (skinType != Skin.Type.LOCAL_FILE && skinType != Skin.Type.CUSTOM_SKIN_LOADER_API) {
            editingSkin = new Skin(skinType, editingSkin.getCslApi(), editingSkin.getTextureModel(), pendingLocalSkinPath, pendingLocalCapePath);
        }
        updatePreview();
    }

    private void showDefaultHint() {
        Label hint = new Label("使用 Minecraft 默认皮肤（Steve/Alex 模型按玩家名确定）");
        hint.getStyleClass().add("modal-text");
        ImageView defaultIv = new ImageView(new Image(getClass().getResourceAsStream("/Skins/Steve.png"), 80, 80, true, true));
        HBox previewBox = new HBox(12, defaultIv, new Label("Steve\n默认宽模型皮肤"));
        previewBox.setAlignment(Pos.CENTER_LEFT);
        previewBox.getStyleClass().add("skin-default-card");
        skinConfigArea.getChildren().addAll(hint, previewBox);
    }

    private void showBuiltinGrid() {
        Label hint = new Label("选择一个内置皮肤：");
        hint.getStyleClass().add("modal-text-title");
        GridPane grid = new GridPane();
        Skin.Type[] builtins = Skin.getBuiltinTypes();
        ToggleGroup builtinGroup = new ToggleGroup();
        int col = 0, row = 0;
        for (Skin.Type bt : builtins) {
            VBox cell = new VBox(4);
            cell.setAlignment(Pos.CENTER);
            cell.setPrefWidth(84);
            cell.setUserData(bt);
            cell.getStyleClass().add("skin-builtin-cell");
            String resPath = "/Skins/" + bt.name() + ".png";
            Image img = new Image(getClass().getResourceAsStream(resPath), 44, 58, true, true);
            ImageView iv = new ImageView(img);
            Label nameLabel = new Label(bt.name());
            nameLabel.getStyleClass().add("modal-hint");
            RadioButton rb = new RadioButton();
            rb.setToggleGroup(builtinGroup);
            rb.setUserData(bt);
            rb.setOpacity(0);
            rb.setManaged(false);
            cell.getChildren().addAll(iv, nameLabel, rb);
            cell.setOnMouseClicked(e -> { builtinGroup.selectToggle(rb); updateBuiltinCellSelection(grid, bt); });
            if (bt == selectedBuiltinType) {
                builtinGroup.selectToggle(rb);
                cell.getStyleClass().add("selected");
            }
            grid.add(cell, col, row); col++; if (col >= 4) { col = 0; row++; }
        }
        builtinGroup.selectedToggleProperty().addListener((obs, old, nev) -> {
            if (nev != null) {
                selectedBuiltinType = (Skin.Type) nev.getUserData();
                editingSkin = new Skin(selectedBuiltinType, null, null, null, null);
                updatePreview();
            }
        });
        skinConfigArea.getChildren().addAll(hint, grid);
    }

    private void updateBuiltinCellSelection(GridPane grid, Skin.Type selected) {
        for (Node node : grid.getChildren()) {
            if (node instanceof VBox cell) {
                Skin.Type bt = (Skin.Type) cell.getUserData();
                if (bt == selected) {
                    if (!cell.getStyleClass().contains("selected")) cell.getStyleClass().add("selected");
                } else {
                    cell.getStyleClass().remove("selected");
                }
            }
        }
    }

    private void showLocalFileSelector() {
        Label hint = new Label("皮肤文件：角色外观；披风文件：角色背后的披风（PNG，仅自己可见）");
        hint.getStyleClass().add("modal-text-title");

        // 皮肤文件区：小节标题 + 按钮行 + 完整文件名（可换行） //
        VBox skinSection = new VBox(12);
        skinSection.getStyleClass().add("skin-file-card");
        Label skinSectionTitle = new Label("皮肤文件");
        skinSectionTitle.getStyleClass().add("skin-file-title");
        Label skinFileLabel = new Label((pendingLocalSkinPath != null ? new File(pendingLocalSkinPath).getName() : "未选择皮肤文件"));
        skinFileLabel.getStyleClass().add("skin-file-label");
        skinFileLabel.setWrapText(true);
        skinFileLabel.setMaxWidth(Double.MAX_VALUE);
        Button browseSkinBtn = AppIcons.button("folder", "浏览皮肤...");
        browseSkinBtn.getStyleClass().add("skin-browse-btn");
        skinRemoveBgBox = new CheckBox("去背景");
        skinRemoveBgBox.setSelected(removeSkinBgFlag);
        skinRemoveBgBox.getStyleClass().add("skin-checkbox");
        skinRemoveBgBox.setOnAction(e -> {
            removeSkinBgFlag = skinRemoveBgBox.isSelected();
            editingSkin = new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(),
                    pendingLocalSkinPath, pendingLocalCapePath, removeSkinBgFlag, removeCapeBgFlag);
            updatePreview();
        });
        browseSkinBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择皮肤文件");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG图片", "*.png"));
            File file = fc.showOpenDialog(getScene().getWindow());
            if (file != null) {
                pendingLocalSkinPath = file.getAbsolutePath();
                skinFileLabel.setText(file.getName());
                editingSkin = new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(), pendingLocalSkinPath, pendingLocalCapePath);
                updatePreview();
            }
        });
        HBox skinBtnRow = new HBox(8, browseSkinBtn, skinRemoveBgBox);
        skinBtnRow.setAlignment(Pos.CENTER_LEFT);
        skinSection.getChildren().addAll(skinSectionTitle, skinBtnRow, skinFileLabel);

        // 皮肤模型（对应 PCL2 LaunchSkinSlim）：宽体用 Steve、纤细用 Alex //
        HBox modelRow = new HBox(8);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        Label modelLabel = new Label("皮肤模型:");
        modelLabel.getStyleClass().add("modal-text");
        ToggleGroup modelGroup = new ToggleGroup();
        RadioButton wideModel = new RadioButton("宽模型 (Steve)");
        RadioButton slimModel = new RadioButton("纤细模型 (Alex)");
        wideModel.setToggleGroup(modelGroup);
        slimModel.setToggleGroup(modelGroup);
        boolean slimNow = editingSkin != null && editingSkin.isSlim();
        (slimNow ? slimModel : wideModel).setSelected(true);
        Runnable applyModel = () -> {
            boolean isSlim = modelGroup.getSelectedToggle() == slimModel;
            editingSkin = new Skin(Skin.Type.LOCAL_FILE, null,
                    isSlim ? Skin.TextureModel.SLIM : Skin.TextureModel.WIDE,
                    pendingLocalSkinPath, pendingLocalCapePath, removeSkinBgFlag, removeCapeBgFlag);
            updatePreview();
        };
        wideModel.setOnAction(e -> applyModel.run());
        slimModel.setOnAction(e -> applyModel.run());
        modelRow.getChildren().addAll(modelLabel, wideModel, slimModel);

        // 披风文件区：小节标题 + 按钮行 + 完整文件名（可换行） //
        VBox capeSection = new VBox(12);
        capeSection.getStyleClass().add("skin-file-card");
        Label capeSectionTitle = new Label("披风文件");
        capeSectionTitle.getStyleClass().add("skin-file-title");
        Label capeFileLabel = new Label((pendingLocalCapePath != null ? new File(pendingLocalCapePath).getName() : "未选择披风文件"));
        capeFileLabel.getStyleClass().add("skin-file-label");
        capeFileLabel.setWrapText(true);
        capeFileLabel.setMaxWidth(Double.MAX_VALUE);
        Button browseCapeBtn = AppIcons.button("folder", "浏览披风...");
        browseCapeBtn.getStyleClass().add("skin-browse-btn");
        capeRemoveBgBox = new CheckBox("去背景");
        capeRemoveBgBox.setSelected(removeCapeBgFlag);
        capeRemoveBgBox.getStyleClass().add("skin-checkbox");
        capeRemoveBgBox.setOnAction(e -> {
            removeCapeBgFlag = capeRemoveBgBox.isSelected();
            editingSkin = new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(),
                    pendingLocalSkinPath, pendingLocalCapePath, removeSkinBgFlag, removeCapeBgFlag);
            updatePreview();
        });
        browseCapeBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择披风文件");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG图片", "*.png"));
            File file = fc.showOpenDialog(getScene().getWindow());
            if (file != null) {
                pendingLocalCapePath = file.getAbsolutePath();
                capeFileLabel.setText(file.getName());
                editingSkin = new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(), pendingLocalSkinPath, pendingLocalCapePath);
                updatePreview();
            }
        });
        HBox capeBtnRow = new HBox(8, browseCapeBtn, capeRemoveBgBox);
        capeBtnRow.setAlignment(Pos.CENTER_LEFT);
        capeSection.getChildren().addAll(capeSectionTitle, capeBtnRow, capeFileLabel);

        skinConfigArea.getChildren().addAll(hint, skinSection, modelRow, capeSection);
    }

    private void showLittleSkinHint() {
        Label hint = new Label("LittleSkin (littleskin.cn)");
        hint.getStyleClass().add("modal-text-title");
        Label desc = new Label("将自动从 LittleSkin 获取此账号的皮肤和披风纹理。\n确保已在 LittleSkin 官网上传了皮肤");
        desc.getStyleClass().add("modal-text");
        VBox card = new VBox(6, desc);
        card.getStyleClass().add("skin-default-card");
        skinConfigArea.getChildren().addAll(hint, card);
    }

    private void showCslApiInput() {
        Label hint = new Label("Custom Skin Loader API 地址:");
        hint.getStyleClass().add("modal-text-title");
        Label desc = new Label("输入支持 CSL API 标准的服务器地址");
        desc.getStyleClass().add("modal-hint");
        TextField apiField = new TextField(cslApiUrl != null ? cslApiUrl : "");
        apiField.getStyleClass().add("input-field");
        apiField.setPromptText("https://example.com/csl");
        apiField.textProperty().addListener((obs, old, nev) -> {
            cslApiUrl = nev;
            editingSkin = new Skin(Skin.Type.CUSTOM_SKIN_LOADER_API, nev, editingSkin.getTextureModel(), null, null);
        });
        VBox card = new VBox(6, desc, apiField);
        card.getStyleClass().add("skin-default-card");
        skinConfigArea.getChildren().addAll(hint, card);
    }

    private void updatePreview() {
        if (editingSkin == null) {
            previewSkinImage.setImage(null);
            previewTypeBadge.setText("未选择皮肤");
            return;
        }
        boolean isSlim = editingSkin.isSlim();
        previewTypeBadge.setText(isSlim ? "纤细模型 (Alex)" : "宽模型 (Steve)");
        Skin.LoadedSkin loaded = editingSkin.load("preview");
        if (loaded != null && loaded.getSkin() != null) {
            previewSkinImage.setImage(loaded.getSkin().getImage());
            return;
        }
        if (editingSkin.getType().ordinal() >= Skin.Type.ALEX.ordinal() && editingSkin.getType().ordinal() <= Skin.Type.ZURI.ordinal()) {
            previewSkinImage.setImage(new Image(getClass().getResourceAsStream("/Skins/" + editingSkin.getType().name() + ".png"), 118, 118, true, true));
        } else if (editingSkin.getType() == Skin.Type.DEFAULT) {
            previewSkinImage.setImage(new Image(getClass().getResourceAsStream("/Skins/Steve.png"), 118, 118, true, true));
        } else if (editingSkin.getType() == Skin.Type.LOCAL_FILE && pendingLocalSkinPath != null) {
            previewSkinImage.setImage(new Image(new File(pendingLocalSkinPath).toURI().toString(), 118, 118, true, true));
        }
    }

    private void handleApply() {
        Toggle selected = sourceToggleGroup.getSelectedToggle();
        if (selected == null) { closeHandler.run(); return; }
        String sourceKey = (String) selected.getUserData();
        Skin finalSkin = switch (sourceKey) {
            case "DEFAULT" -> new Skin(Skin.Type.DEFAULT, null, null, null, null);
            case "BUILTIN" -> new Skin(selectedBuiltinType, null, null, null, null);
            case "LOCAL_FILE" -> new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(),
                    pendingLocalSkinPath, pendingLocalCapePath, removeSkinBgFlag, removeCapeBgFlag);
            case "LITTLE_SKIN" -> new Skin(Skin.Type.LITTLE_SKIN, null, null, null, null);
            case "CUSTOM_SKIN_LOADER_API" -> new Skin(Skin.Type.CUSTOM_SKIN_LOADER_API, cslApiUrl, null, null, null);
            default -> new Skin(Skin.Type.DEFAULT, null, null, null, null);
        };
        String skinJson = finalSkin.toJson().toString();
        boolean saved = AccountManager.updateAccountSkin(account.id, skinJson);
        closeHandler.run();
        if (saved) {
            if (onApplied != null) onApplied.run();
        } else {
            System.err.println("[SkinSettingsPanel] 皮肤保存失败，未生效: " + account.id);
        }
    }
}

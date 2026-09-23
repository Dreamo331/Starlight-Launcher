/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.newui;

import com.example.starlight.newui.ui.AppIcons;
import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.offline.Skin;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * 离线皮肤设置面板 —— 集成在启动器窗口内（替代独立 Stage 的 SkinSettingsController）。
 * 支持来源：默认 / 内置 / 本地文件 / LittleSkin / CSL API，实时预览。
 */
public class SkinSettingsPanel extends VBox {

    private Label headerSubLabel, previewNameLabel, previewTypeBadge;
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

    public SkinSettingsPanel(AccountManager.Account account, Runnable onApplied, Runnable closeHandler) {
        super(10);
        this.account = account;
        this.onApplied = onApplied;
        this.closeHandler = closeHandler;
        this.editingSkin = account.getSkin();
        this.pendingLocalSkinPath = editingSkin.getLocalSkinPath();
        this.pendingLocalCapePath = editingSkin.getLocalCapePath();
        this.selectedBuiltinType = (editingSkin.getType().ordinal() >= Skin.Type.ALEX.ordinal()
                && editingSkin.getType().ordinal() <= Skin.Type.ZURI.ordinal())
                ? editingSkin.getType() : Skin.Type.STEVE;
        this.cslApiUrl = editingSkin.getCslApi();
        setPadding(new Insets(12, 8, 0, 8));

        // 顶部说明
        headerSubLabel = new Label("为\"" + account.name + "\" 设置自定义皮肤");
        headerSubLabel.getStyleClass().add("modal-hint");

        // 主体：左侧预览 + 右侧设置
        HBox centerContent = new HBox(20);
        VBox.setVgrow(centerContent, Priority.ALWAYS);

        // 左侧：预览
        VBox previewSection = new VBox(12);
        previewSection.setAlignment(Pos.CENTER);
        previewSection.setMinWidth(180);
        Label previewSectionTitle = new Label("预览");
        previewSectionTitle.getStyleClass().add("modal-text-title");
        previewSkinImage = new ImageView();
        previewSkinImage.setFitWidth(128);
        previewSkinImage.setFitHeight(128);
        previewSkinImage.setPreserveRatio(true);
        previewNameLabel = new Label(account.name);
        previewNameLabel.getStyleClass().add("modal-text-title");
        previewTypeBadge = new Label("宽模型 (Steve)");
        previewTypeBadge.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569;" +
                " -fx-background-color: #e2e8f0; -fx-padding: 2 8; -fx-background-radius: 30px;");
        previewSection.getChildren().addAll(previewSectionTitle, previewSkinImage, previewNameLabel, previewTypeBadge);

        // 右侧：设置区
        VBox settingsSection = new VBox(12);
        VBox.setVgrow(settingsSection, Priority.ALWAYS);

        Label sourceLabel = new Label("皮肤来源");
        sourceLabel.getStyleClass().add("modal-text-title");

        sourceToggleGroup = new ToggleGroup();
        HBox sourceButtons = new HBox(6);
        srcDefaultBtn = AppIcons.toggle("check", "默认");
        srcDefaultBtn.setToggleGroup(sourceToggleGroup);
        srcDefaultBtn.setUserData("DEFAULT");
        srcBuiltinBtn = AppIcons.toggle("package", "内置");
        srcBuiltinBtn.setToggleGroup(sourceToggleGroup);
        srcBuiltinBtn.setUserData("BUILTIN");
        srcLocalBtn = AppIcons.toggle("folder", "本地文件");
        srcLocalBtn.setToggleGroup(sourceToggleGroup);
        srcLocalBtn.setUserData("LOCAL_FILE");
        srcLittleSkinBtn = AppIcons.toggle("globe", "LittleSkin");
        srcLittleSkinBtn.setToggleGroup(sourceToggleGroup);
        srcLittleSkinBtn.setUserData("LITTLE_SKIN");
        srcCslApiBtn = AppIcons.toggle("network", "CSL API");
        srcCslApiBtn.setToggleGroup(sourceToggleGroup);
        srcCslApiBtn.setUserData("CUSTOM_SKIN_LOADER_API");
        sourceButtons.getChildren().addAll(srcDefaultBtn, srcBuiltinBtn, srcLocalBtn, srcLittleSkinBtn, srcCslApiBtn);

        skinConfigArea = new VBox(10);
        VBox.setVgrow(skinConfigArea, Priority.ALWAYS);

        settingsSection.getChildren().addAll(sourceLabel, sourceButtons, skinConfigArea);
        centerContent.getChildren().addAll(previewSection, settingsSection);

        // 底部：按钮
        HBox bottomButtons = new HBox(10);
        bottomButtons.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = AppIcons.button("close", "取消");
        cancelBtn.getStyleClass().add("modal-btn-cancel");
        // 与同排「应用」按钮（6 20 / 12px）对齐：.modal-btn-cancel 是弹窗底栏的尺寸，直接用会比同排高一截
        cancelBtn.setStyle("-fx-padding: 6 20; -fx-font-size: 12px; -fx-cursor: hand;");
        cancelBtn.setOnAction(e -> closeHandler.run());
        applyBtn = AppIcons.button("check", "应用");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setStyle("-fx-padding: 6 20; -fx-font-size: 12px; -fx-cursor: hand;");
        applyBtn.setOnAction(e -> handleApply());
        bottomButtons.getChildren().addAll(cancelBtn, applyBtn);

        getChildren().addAll(headerSubLabel, centerContent, bottomButtons);

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
        Label hint = new Label("使用 Minecraft 默认的 Steve 皮肤");
        hint.getStyleClass().add("modal-text");
        ImageView defaultIv = new ImageView(new Image(getClass().getResourceAsStream("/Skins/Steve.png"), 80, 80, true, true));
        HBox previewBox = new HBox(12, defaultIv, new Label("Steve\n默认宽模型皮肤"));
        previewBox.setAlignment(Pos.CENTER_LEFT);
        previewBox.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-padding: 10;");
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
            cell.setPadding(new Insets(6));
            cell.setPrefWidth(86);
            cell.setUserData(bt);
            String resPath = "/Skins/" + bt.name() + ".png";
            Image img = new Image(getClass().getResourceAsStream(resPath), 48, 64, true, true);
            ImageView iv = new ImageView(img);
            Label nameLabel = new Label(bt.name());
            nameLabel.getStyleClass().add("modal-hint");
            RadioButton rb = new RadioButton();
            rb.setToggleGroup(builtinGroup);
            rb.setUserData(bt);
            rb.setOpacity(0);
            cell.getChildren().addAll(iv, nameLabel, rb);
            cell.setOnMouseClicked(e -> { builtinGroup.selectToggle(rb); updateBuiltinCellSelection(grid, bt); });
            if (bt == selectedBuiltinType) {
                builtinGroup.selectToggle(rb);
                cell.setStyle("-fx-background-color: #eef2ff; -fx-border-color: #6366f1; -fx-border-width: 2; -fx-border-radius: 8;");
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
                cell.setStyle(bt == selected
                        ? "-fx-background-color: #eef2ff; -fx-border-color: #6366f1; -fx-border-width: 2; -fx-border-radius: 8;"
                        : "-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 2; -fx-border-radius: 8;");
            }
        }
    }

    private void showLocalFileSelector() {
        Label hint = new Label("选择本地皮肤和披风文件(PNG)");
        hint.getStyleClass().add("modal-text-title");
        HBox skinRow = new HBox(8);
        skinRow.setAlignment(Pos.CENTER_LEFT);
        Label skinFileLabel = new Label(pendingLocalSkinPath != null ? new File(pendingLocalSkinPath).getName() : "未选择皮肤文件");
        skinFileLabel.getStyleClass().add("modal-text");
        skinFileLabel.setPrefWidth(200);
        Button browseSkinBtn = AppIcons.button("folder", "浏览皮肤...");
        browseSkinBtn.getStyleClass().add("btn-primary");
        browseSkinBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 12px; -fx-cursor: hand;");
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
        skinRow.getChildren().addAll(browseSkinBtn, skinFileLabel);
        HBox capeRow = new HBox(8);
        capeRow.setAlignment(Pos.CENTER_LEFT);
        Label capeFileLabel = new Label(pendingLocalCapePath != null ? new File(pendingLocalCapePath).getName() : "未选择披风文件");
        capeFileLabel.getStyleClass().add("modal-text");
        capeFileLabel.setPrefWidth(200);
        Button browseCapeBtn = AppIcons.button("folder", "浏览披风...");
        browseCapeBtn.getStyleClass().add("btn-primary");
        browseCapeBtn.setStyle("-fx-padding: 4 12; -fx-font-size: 12px; -fx-cursor: hand;");
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
        capeRow.getChildren().addAll(browseCapeBtn, capeFileLabel);
        skinConfigArea.getChildren().addAll(hint, skinRow, capeRow);
    }

    private void showLittleSkinHint() {
        Label hint = new Label("LittleSkin (littleskin.cn)");
        hint.getStyleClass().add("modal-text-title");
        Label desc = new Label("将自动从 LittleSkin 获取此账号的皮肤和披风纹理。\n确保已在 LittleSkin 官网上传了皮肤");
        desc.getStyleClass().add("modal-text");
        skinConfigArea.getChildren().addAll(hint, desc);
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
        skinConfigArea.getChildren().addAll(hint, desc, apiField);
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
            previewSkinImage.setImage(new Image(getClass().getResourceAsStream("/Skins/" + editingSkin.getType().name() + ".png"), 128, 128, true, true));
        } else if (editingSkin.getType() == Skin.Type.DEFAULT) {
            previewSkinImage.setImage(new Image(getClass().getResourceAsStream("/Skins/Steve.png"), 128, 128, true, true));
        } else if (editingSkin.getType() == Skin.Type.LOCAL_FILE && pendingLocalSkinPath != null) {
            previewSkinImage.setImage(new Image(new File(pendingLocalSkinPath).toURI().toString(), 128, 128, true, true));
        }
    }

    private void handleApply() {
        Toggle selected = sourceToggleGroup.getSelectedToggle();
        if (selected == null) { closeHandler.run(); return; }
        String sourceKey = (String) selected.getUserData();
        Skin finalSkin = switch (sourceKey) {
            case "DEFAULT" -> new Skin(Skin.Type.DEFAULT, null, null, null, null);
            case "BUILTIN" -> new Skin(selectedBuiltinType, null, null, null, null);
            case "LOCAL_FILE" -> new Skin(Skin.Type.LOCAL_FILE, null, editingSkin.getTextureModel(), pendingLocalSkinPath, pendingLocalCapePath);
            case "LITTLE_SKIN" -> new Skin(Skin.Type.LITTLE_SKIN, null, null, null, null);
            case "CUSTOM_SKIN_LOADER_API" -> new Skin(Skin.Type.CUSTOM_SKIN_LOADER_API, cslApiUrl, null, null, null);
            default -> new Skin(Skin.Type.DEFAULT, null, null, null, null);
        };
        String skinJson = finalSkin.toJson().toString();
        AccountManager.updateAccountSkin(account.id, skinJson);
        closeHandler.run();
        if (onApplied != null) onApplied.run();
    }
}

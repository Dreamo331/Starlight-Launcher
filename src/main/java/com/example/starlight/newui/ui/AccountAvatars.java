package com.example.starlight.newui.ui;

import com.example.starlight.auth.AccountManager;
import com.example.starlight.auth.AccountManager.Account;
import com.example.starlight.gui.UIGeneralControlClass;
import com.example.starlight.util.AvatarGenerator;
import com.example.starlight.util.MojangAvatarFetcher;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.util.UUID;

/**
 * 账号真实头像：读取账号皮肤并渲染成圆形头像，填进指定容器。
 *
 * <p>与首页头像同一套来源与回退链（{@code HomePage.loadHomeAvatar} 的逻辑）：
 * 微软 / 第三方走其 Yggdrasil session API 取皮肤（本地缓存 24h），离线账号读本地皮肤配置，
 * 都没有时按 UUID 哈希取内置默认皮肤。加载在后台线程，生成头像必须在 FX 线程
 * （{@code AvatarGenerator.generateAvatar} 内部走 Canvas.snapshot）。
 *
 * <p>容器里原有的字母占位在成功后才被替换：加载中或失败时界面仍是字母头像，不会出现空白。
 * 头像按原始方形显示——按需求不加圆角或圆形遮罩。
 */
public final class AccountAvatars {

    private AccountAvatars() {
    }

    /**
     * 异步把真实头像填进 {@code holder}。
     *
     * @param holder  头像容器（通常已放着一个字母占位节点）
     * @param account 账号；为 null 时直接渲染默认皮肤
     * @param size    显示边长（px），圆形裁剪
     */
    public static void load(StackPane holder, Account account, double size) {
        if (holder == null || size <= 0) return;
        UUID uuid = AvatarGenerator.parseUuid(account != null ? account.id : null);
        UIGeneralControlClass.ASYNC_POOL.submit(() -> {
            try {
                // 后台线程：皮肤加载含网络 / 文件 IO
                Image skinImage = (account != null
                        && (account.type == AccountManager.AccountType.MICROSOFT
                        || account.type == AccountManager.AccountType.THIRD_PARTY))
                        ? MojangAvatarFetcher.getAccountAvatar(account)
                        : AvatarGenerator.loadAccountSkin(account);
                Platform.runLater(() -> {
                    try {
                        Image avatar = skinImage != null
                                ? AvatarGenerator.generateAvatar(skinImage, 64)
                                : AvatarGenerator.generateAvatar(AvatarGenerator.getDefaultSkin(uuid), 64);
                        if (avatar == null) return;   // 生成失败：保留字母占位
                        ImageView view = new ImageView(avatar);
                        view.setFitWidth(size);
                        view.setFitHeight(size);
                        // 头像框是正方形：preserveRatio=true 时实际显示尺寸小于裁剪区，脸会显示不全
                        view.setPreserveRatio(false);
                        // 按需求不加圆角/圆形遮罩：头像按原始方形显示
                        view.setClip(null);
                        holder.getChildren().setAll(view);
                    } catch (Exception ignored) {
                        // 保留字母占位
                    }
                });
            } catch (Exception ignored) {
                // 网络 / IO 异常：保留字母占位
            }
        });
    }
}
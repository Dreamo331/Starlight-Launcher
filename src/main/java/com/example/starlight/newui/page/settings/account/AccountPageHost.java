package com.example.starlight.newui.page.settings.account;

import com.example.starlight.auth.AccountManager.Account;

/**
 * 账户页各子面板与页面主体之间的契约：面板只管自己的界面与校验，
 * 账号读写、页面流转、Toast、弹窗都交给页面实现。
 *
 * <p>拆出这层是为了让每个面板都能独立构建与离屏渲染自检（探针里注入一个空实现即可）。
 */
public interface AccountPageHost {

    /** 轻提示（走主壳的统一 Toast） */
    void toast(String message);

    /** 用系统浏览器打开地址（第三方注册页、微软授权链接） */
    void openWeb(String url);

    /** 危险操作确认面板（删除账号） */
    void confirmDelete(String message, Runnable onConfirm);

    /** 打开离线/第三方账号的皮肤设置面板（微软账号无此项） */
    void openSkinSettings(Account account);

    /** 账号集合或当前账号发生变化：页面刷新列表与问候语（不强制切面板，例如在详情页切换账号） */
    void accountChanged(String message);

    /** 登录成功：刷新列表与问候语、展开折叠控件并回到列表面板 */
    void accountLoggedIn(String message);

    /** 切换到指定面板（面板内的「返回」按钮用） */
    void showPane(String paneKey);

    /** 真实异步请求期间显示「正在处理…」面板与全屏遮罩 */
    void showBusy(String message);

    /** 结束忙碌态 */
    void hideBusy();
}
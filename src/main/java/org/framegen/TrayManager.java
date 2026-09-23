/*
 * 帧生成器 · 系统托盘（由启动器页面开关启用/停用）
 * 说明：原独立版「INS 键呼出悬浮菜单 / 悬浮窗控制页」已随整合移除，
 *      托盘仅保留：状态提示、倍率快捷切换、打开启动器内的帧生成器页面。
 */
package org.framegen;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.Timer;

public class TrayManager {
    private final AppConfig config;
    private final GameMonitor monitor;
    private final FrameGenController controller;
    /** 打开启动器内的「帧生成器」页面（调用方负责切到 UI 线程） */
    private final Runnable openPageAction;

    private TrayIcon trayIcon;
    private SystemTray tray;
    private Timer tooltipTimer;
    private boolean active = false;

    public TrayManager(AppConfig appConfig, GameMonitor gameMonitor, FrameGenController frameGenController, Runnable openPageAction) {
        this.config = appConfig;
        this.monitor = gameMonitor;
        this.controller = frameGenController;
        this.openPageAction = openPageAction;
    }

    /**
     * 启动托盘图标。返回是否已处于/已成功启用。
     */
    public synchronized boolean start() {
        if (this.active && this.trayIcon != null) {
            return true;
        }
        if (!SystemTray.isSupported()) {
            System.err.println("[FrameGenTray] 系统托盘不支持");
            return false;
        }
        try {
            this.tray = SystemTray.getSystemTray();
            Image image = this.createTrayIcon();
            this.trayIcon = new TrayIcon(image, "Starlight 帧生成器", this.createPopupMenu());
            this.trayIcon.setImageAutoSize(true);
            this.trayIcon.addMouseListener(new MouseAdapter(){
                @Override
                public void mouseClicked(MouseEvent mouseEvent) {
                    // 左键单击：打开启动器内的帧生成器页面（替代原悬浮菜单）
                    if (mouseEvent.getButton() == 1 && TrayManager.this.openPageAction != null) {
                        TrayManager.this.openPageAction.run();
                    }
                }
            });
            this.tray.add(this.trayIcon);
            this.active = true;
            System.out.println("[FrameGenTray] 托盘图标已添加");
            this.trayIcon.displayMessage("Starlight 帧生成器", "帧生成系统托盘已启用（可在启动器页面关闭）", TrayIcon.MessageType.INFO);

            this.tooltipTimer = new Timer(3000, actionEvent -> this.updateTooltip());
            this.tooltipTimer.start();
            return true;
        }
        catch (AWTException aWTException) {
            System.err.println("[FrameGenTray] 添加托盘图标失败: " + aWTException.getMessage());
            this.active = false;
            this.trayIcon = null;
            this.tray = null;
            return false;
        }
        catch (Throwable t) {
            System.err.println("[FrameGenTray] 托盘启动异常: " + t.getMessage());
            this.active = false;
            this.trayIcon = null;
            this.tray = null;
            return false;
        }
    }

    /**
     * 移除托盘图标并停止后台刷新。
     */
    public synchronized void stop() {
        if (this.tooltipTimer != null) {
            this.tooltipTimer.stop();
            this.tooltipTimer = null;
        }
        if (this.tray != null && this.trayIcon != null) {
            try {
                this.tray.remove(this.trayIcon);
            }
            catch (Throwable t) {
                // ignore
            }
        }
        this.trayIcon = null;
        this.tray = null;
        this.active = false;
        System.out.println("[FrameGenTray] 托盘图标已移除");
    }

    public boolean isActive() {
        return this.active;
    }

    public void showMessage(String string, String string2, TrayIcon.MessageType messageType) {
        if (this.trayIcon != null) {
            try {
                this.trayIcon.displayMessage(string, string2, messageType);
            }
            catch (Throwable t) {
                // ignore
            }
        }
    }

    /**
     * 选取一个可正常显示中文的菜单字体。
     * AWT 托盘弹出菜单若使用默认字体（不含中文字形），中文会渲染成“方块”。
     */
    private static Font resolveMenuFont() {
        String test = "帧生成倍率中文测试";
        String[] preferred = {"Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑", "PingFang SC", "SimHei", "黑体", "SimSun", "宋体"};
        for (String name : preferred) {
            try {
                Font f = new Font(name, Font.PLAIN, 12);
                if (f.canDisplayUpTo(test) == -1) {
                    return f;
                }
            }
            catch (Throwable ignored) {
                // continue
            }
        }
        // 兜底：扫描系统已安装字体，取第一个能显示中文的
        try {
            String[] families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            for (String name : families) {
                try {
                    Font f = new Font(name, Font.PLAIN, 12);
                    if (f.canDisplayUpTo(test) == -1) {
                        return f;
                    }
                }
                catch (Throwable ignored) {
                    // continue
                }
            }
        }
        catch (Throwable ignored) {
            // continue
        }
        return new Font(Font.DIALOG, Font.PLAIN, 12);
    }

    /** 递归为弹出菜单及其全部子项设置字体（含二级子菜单），解决中文显示为方块的问题 */
    private static void applyFontRecursive(java.awt.MenuComponent component, Font font) {
        if (component == null) {
            return;
        }
        component.setFont(font);
        if (component instanceof java.awt.Menu) {
            java.awt.Menu subMenu = (java.awt.Menu) component;
            int count = subMenu.getItemCount();
            for (int i = 0; i < count; i++) {
                applyFontRecursive(subMenu.getItem(i), font);
            }
        }
    }

    private PopupMenu createPopupMenu() {
        PopupMenu popupMenu = new PopupMenu();

        MenuItem menuItemOpen = new MenuItem("打开帧生成器页面");
        menuItemOpen.addActionListener(actionEvent -> {
            if (this.openPageAction != null) {
                this.openPageAction.run();
            }
        });
        popupMenu.add(menuItemOpen);
        popupMenu.addSeparator();

        Menu menu = new Menu("帧生成倍率");
        int[] nArray = new int[]{0, 2, 4, 6, 8};
        String[] stringArray = new String[]{"关闭", "2x", "4x", "6x", "8x"};
        for (int i = 0; i < nArray.length; ++i) {
            int n = nArray[i];
            MenuItem menuItem = new MenuItem(stringArray[i]);
            menuItem.addActionListener(actionEvent -> this.controller.applyFrameGen(n));
            menu.add(menuItem);
        }
        popupMenu.add(menu);
        popupMenu.addSeparator();

        MenuItem menuItemAbout = new MenuItem("关于");
        menuItemAbout.addActionListener(actionEvent -> this.showMessage(
                "关于", "Starlight 帧生成器（已整合进启动器）\n自动监听 Minecraft 进程，支持 2x/4x/6x/8x 帧生成", TrayIcon.MessageType.INFO));
        popupMenu.add(menuItemAbout);

        // 关键：为菜单及所有子项设置可显示中文的字体，避免文字渲染成方块
        applyFontRecursive(popupMenu, resolveMenuFont());
        return popupMenu;
    }

    private void updateTooltip() {
        if (this.trayIcon == null) {
            return;
        }
        String string = this.monitor.isGameDetected() ? "游戏运行中" : "等待游戏";
        int n = this.controller.getCurrentMultiplier();
        Object object = n == 0 ? "未启用" : n + "x";
        this.trayIcon.setToolTip("Starlight 帧生成器 | " + string + " | 帧生成: " + (String)object);
    }

    private Image createTrayIcon() {
        int n = 16;
        BufferedImage bufferedImage = new BufferedImage(n, n, 2);
        Graphics2D graphics2D = bufferedImage.createGraphics();
        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics2D.setColor(new Color(60, 50, 120));
        graphics2D.fillRoundRect(0, 0, n, n, 4, 4);
        graphics2D.setColor(new Color(200, 180, 255));
        graphics2D.setFont(new Font("Arial", 1, 11));
        FontMetrics fontMetrics = graphics2D.getFontMetrics();
        String string = "F";
        int n2 = (n - fontMetrics.stringWidth(string)) / 2;
        int n3 = (n - fontMetrics.getHeight()) / 2 + fontMetrics.getAscent();
        graphics2D.drawString(string, n2, n3);
        graphics2D.dispose();
        return bufferedImage;
    }
}

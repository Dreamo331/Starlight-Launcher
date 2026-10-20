package com.example.starlight.newui.festival;

/**
 * 一个节日的定义（数据驱动）：日期规则、问候语、按钮配色、图标、灯笼色。
 *
 * <p>壁纸按约定命名，放在 {@code src/main/resources/wallpaper/<id>.png|jpg|jpeg|webp}，
 * 运行期由 {@code LauncherView} 按 id 加载，与本地定义解耦。
 */
public final class Festival {

    public final String id;           // 唯一标识，也是壁纸文件名前缀
    public final String name;         // 显示名，如「中秋」
    public final String greeting;     // 问候语，如「中秋快乐」
    public final boolean lunar;       // true=农历，false=公历
    public final int month;           // 农历月或公历月
    public final int day;             // 农历日或公历日
    public final int beforeDays;      // 提前几天开始生效
    public final int afterDays;       // 延后几天结束

    public final String icon;         // AppIcons 图标名（启动按钮图标）
    public final String gradientA;    // 按钮渐变起点
    public final String gradientB;    // 按钮渐变终点
    public final String buttonText;   // 按钮文字色
    public final String greetingColor;// 问候语文字色
    public final String lanternA;     // 灯笼主色（左）
    public final String lanternB;     // 灯笼主色（右）

    public Festival(String id, String name, String greeting, boolean lunar, int month, int day,
                    int beforeDays, int afterDays, String icon,
                    String gradientA, String gradientB, String buttonText, String greetingColor,
                    String lanternA, String lanternB) {
        this.id = id;
        this.name = name;
        this.greeting = greeting;
        this.lunar = lunar;
        this.month = month;
        this.day = day;
        this.beforeDays = beforeDays;
        this.afterDays = afterDays;
        this.icon = icon;
        this.gradientA = gradientA;
        this.gradientB = gradientB;
        this.buttonText = buttonText;
        this.greetingColor = greetingColor;
        this.lanternA = lanternA;
        this.lanternB = lanternB;
    }

    /** 启动按钮文案 */
    public String launchLabel() {
        return name + "限定 · 启动游戏";
    }
}

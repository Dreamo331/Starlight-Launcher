package com.example.starlight.colorblind;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 色障模拟矩阵 / 误差再分配矩阵：直接调用 {@code ColorBlindOverlay.exe --list} 解析，
 * 保证「快速调节」网页里生成的伪等色图与矫正工具内部用的是同一套算法。
 *
 * <p>exe 输出格式（每类一段）：
 * <pre>
 * 绿色盲 (Deuteranopia)  (deuteranopia)
 *   S                            [  0.62500   0.37500   0.00000]
 *                                [  0.70000   0.30000   0.00000]
 *                                [  0.00000   0.30000   0.70000]
 *   E                            [  1.00000   1.00000   0.00000]
 *                                ...
 * </pre>
 * 含义：S = 模拟矩阵（正常视觉 → 该类型的色觉），E = 误差再分配矩阵，
 * 工具输出 = 原色 + strength × E·(原色 − S·原色)。据此可在网页里：
 * ① 只挑「正常人看得出、该类型几乎看不出」的色对生成测试图；
 * ② 按不同 strength 渲染补偿后的观感，让用户挑出最清楚的强度。
 */
public final class ColorBlindMatrices {

    /** 类型标记：形如 (deuteranopia)，只认内置类型名，避免误匹配中文名里的括号 */
    private static final Pattern CODE = Pattern.compile("\\(([a-z]+)\\)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+\\.\\d+");

    private static Map<String, double[][]> simMatrix;
    private static Map<String, double[][]> errMatrix;
    private static boolean loaded;

    private ColorBlindMatrices() {
    }

    /** 是否已成功解析到矩阵 */
    public static synchronized boolean available() {
        ensureLoaded();
        return loaded;
    }

    /** 转成网页用的 JSON：{"types":{code:{"s":[[..]],"e":[[..]]}, ...}} */
    public static synchronized JsonObject toJson() {
        ensureLoaded();
        JsonObject types = new JsonObject();
        for (String code : ColorBlindOverlayManager.typeCodes()) {
            double[][] s = simMatrix == null ? null : simMatrix.get(code);
            double[][] e = errMatrix == null ? null : errMatrix.get(code);
            if (s == null || e == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.add("s", toJsonArray(s));
            entry.add("e", toJsonArray(e));
            types.add(code, entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", types.size() > 0);
        result.add("types", types);
        if (types.size() == 0) {
            result.addProperty("message", "未能从 ColorBlindOverlay.exe 读取模拟矩阵");
        }
        return result;
    }

    private static JsonArray toJsonArray(double[][] matrix) {
        JsonArray rows = new JsonArray();
        for (double[] row : matrix) {
            JsonArray values = new JsonArray();
            for (double value : row) {
                values.add(value);
            }
            rows.add(values);
        }
        return rows;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Map<String, double[][]> sim = new LinkedHashMap<>();
        Map<String, double[][]> err = new LinkedHashMap<>();
        String output = runListCommand();
        if (output != null) {
            parse(output, sim, err);
        }
        if (!sim.isEmpty()) {
            simMatrix = sim;
            errMatrix = err;
            System.out.println("[ColorBlind] 已读取模拟矩阵: " + sim.keySet());
        } else {
            System.err.println("[ColorBlind] 未能读取模拟矩阵（网页里的自测小游戏将不可用）");
        }
    }

    /** 执行 exe --list（失败返回 null；打印完即退出，不会留下后台进程） */
    private static String runListCommand() {
        try {
            File exe = resolveExe();
            if (exe == null) {
                return null;
            }
            ProcessBuilder builder = new ProcessBuilder(exe.getAbsolutePath(), "--list");
            builder.directory(exe.getParentFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // 代码标记都是 ASCII，中文名的编码差异不影响解析
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return text;
        } catch (Exception e) {
            System.err.println("[ColorBlind] 读取模拟矩阵失败: " + e);
            return null;
        }
    }

    private static File resolveExe() {
        File file = new File("Starlight-Launcher" + File.separator + "ColorBlindOverlay"
                + File.separator + "ColorBlindOverlay.exe");
        if (file.isFile()) {
            return file;
        }
        String userDir = System.getProperty("user.dir");
        File alt = userDir == null ? null : new File(userDir,
                "Starlight-Launcher" + File.separator + "ColorBlindOverlay" + File.separator + "ColorBlindOverlay.exe");
        return (alt != null && alt.isFile()) ? alt : null;
    }

    /** 逐行解析：类型行开新块，S/E 行各收三行系数 */
    private static void parse(String text, Map<String, double[][]> sim, Map<String, double[][]> err) {
        String currentCode = null;
        double[][] target = null;
        int row = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher mark = CODE.matcher(line);
            if (mark.find()) {
                String code = mark.group(1);
                if (ColorBlindOverlayManager.typeCodes().contains(code)) {
                    currentCode = code;
                    target = null;
                    row = 0;
                    sim.put(code, new double[3][3]);
                    err.put(code, new double[3][3]);
                }
                continue;
            }
            if (currentCode == null) {
                continue;
            }
            // S/E 标记只在段首出现一次，之后是纯数值行
            if (line.startsWith("S")) {
                target = sim.get(currentCode);
                row = 0;
                line = line.substring(1);
            } else if (line.startsWith("E")) {
                target = err.get(currentCode);
                row = 0;
                line = line.substring(1);
            }
            if (target == null || row >= 3) {
                continue;
            }
            Matcher numbers = NUMBER.matcher(line);
            double[] values = new double[3];
            int count = 0;
            while (numbers.find() && count < 3) {
                values[count++] = Double.parseDouble(numbers.group());
            }
            if (count == 3) {
                target[row++] = values;
            }
        }
    }
}

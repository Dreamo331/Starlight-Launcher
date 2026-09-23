package com.example.starlight.colorblind;

import com.example.starlight.main.ConfigManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 色盲辅助「快速调节」本地网页工具（JDK 内置 HttpServer，仅监听 127.0.0.1 随机端口）。
 *
 * <p>页面包含两部分：
 * <ol>
 *   <li><b>色觉自测小游戏</b>：用矫正工具自己的模拟矩阵现场生成伪等色图（只有「目标色觉类型看不出、
 *       正常色觉看得出」的色对才会被选中），看图选数字定位色障类型；再用误差矩阵按不同强度渲染
 *       同一张图，让用户挑出「数字最清楚」的强度档，一键应用。</li>
 *   <li><b>手动调节</b>：类型下拉 / 强度滑块 / 游戏窗口标题，以及保存、重启、停止按钮。</li>
 * </ol>
 *
 * <p>只在用户点击设置页的「打开调节网页」时懒启动，启动器退出时随 {@code Application.stop()} 一起停止。
 */
public final class ColorBlindTuneServer {

    private static HttpServer server;
    private static ExecutorService executor;
    private static int port = -1;

    private ColorBlindTuneServer() {
    }

    /** 启动服务并返回网页地址；启动失败返回 null（幂等） */
    public static synchronized String ensureStarted() {
        if (server != null) {
            return url();
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            register("/", ColorBlindTuneServer::handleIndex);
            register("/api/config", ColorBlindTuneServer::handleConfig);
            register("/api/save", ColorBlindTuneServer::handleSave);
            register("/api/restart", ColorBlindTuneServer::handleRestart);
            register("/api/stop", ColorBlindTuneServer::handleStop);
            register("/api/status", ColorBlindTuneServer::handleStatus);
            register("/api/matrices", ColorBlindTuneServer::handleMatrices);
            // 守护线程：启动器退出时不靠这个线程池存活，避免 JVM 卡在非守护线程上关不掉
            executor = Executors.newCachedThreadPool(r -> {
                Thread worker = new Thread(r, "colorblind-tune-http");
                worker.setDaemon(true);
                return worker;
            });
            server.setExecutor(executor);
            server.start();
            port = server.getAddress().getPort();
            Runtime.getRuntime().addShutdownHook(new Thread(ColorBlindTuneServer::stop, "colorblind-tune-shutdown"));
            System.out.println("[ColorBlind] 快速调节网页已启动: " + url());
            return url();
        } catch (IOException e) {
            System.err.println("[ColorBlind] 快速调节网页启动失败: " + e.getMessage());
            server = null;
            executor = null;
            port = -1;
            return null;
        }
    }

    /** 停止服务（幂等） */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        port = -1;
    }

    /** 网页地址；未启动返回 null */
    public static String url() {
        return port > 0 ? "http://127.0.0.1:" + port + "/" : null;
    }

    // ==================== 请求处理 ====================

    private static void handleIndex(HttpExchange exchange) throws IOException {
        send(exchange, 200, "text/html; charset=utf-8", buildHtml().getBytes(StandardCharsets.UTF_8));
    }

    /** 当前配置 + 运行状态 */
    private static void handleConfig(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, configJson().toString());
    }

    /** 模拟矩阵 / 误差矩阵（自测小游戏用；取自 ColorBlindOverlay.exe --list） */
    private static void handleMatrices(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, ColorBlindMatrices.toJson().toString());
    }

    /** 保存配置（不重启进程） */
    private static void handleSave(HttpExchange exchange) throws IOException {
        JsonObject body = readBody(exchange);
        String message = saveValues(body);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", message);
        sendJson(exchange, 200, result.toString());
    }

    /** 保存配置并重启矫正工具进程（改完立刻看效果） */
    private static void handleRestart(HttpExchange exchange) throws IOException {
        JsonObject body = readBody(exchange);
        String saved = saveValues(body);
        String message = ColorBlindOverlayManager.restartOverlay();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", saved + "；" + message);
        result.addProperty("running", ColorBlindOverlayManager.isRunning());
        sendJson(exchange, 200, result.toString());
    }

    /** 停止矫正工具进程 */
    private static void handleStop(HttpExchange exchange) throws IOException {
        String message = ColorBlindOverlayManager.stopOverlay();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("message", message);
        result.addProperty("running", ColorBlindOverlayManager.isRunning());
        sendJson(exchange, 200, result.toString());
    }

    /** 仅运行状态（网页定时轮询） */
    private static void handleStatus(HttpExchange exchange) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", ColorBlindOverlayManager.isEnabled());
        json.addProperty("running", ColorBlindOverlayManager.isRunning());
        json.addProperty("gameRunning", ColorBlindOverlayManager.isGameRunning());
        sendJson(exchange, 200, json.toString());
    }

    // ==================== 内部实现 ====================

    private static void register(String path, ThrowingHandler handler) {
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable t) {
                System.err.println("[ColorBlind] 调节网页请求处理失败 (" + path + "): " + t);
                try {
                    sendJson(exchange, 500, "{\"ok\":false,\"message\":\"服务器内部错误\"}");
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** 组装当前配置与状态的 JSON */
    private static JsonObject configJson() {
        Map<String, String> config = ColorBlindOverlayManager.readMergedConfig();
        JsonObject json = new JsonObject();
        json.addProperty("type", ColorBlindOverlayManager.resolveType(config));
        json.addProperty("strength", ColorBlindOverlayManager.resolveStrength(config));
        json.addProperty("window", config.getOrDefault(ColorBlindOverlayManager.KEY_WINDOW, ""));
        json.addProperty("defaultWindow", ColorBlindOverlayManager.DEFAULT_WINDOW);
        json.addProperty("enabled", ColorBlindOverlayManager.isEnabled());
        json.addProperty("running", ColorBlindOverlayManager.isRunning());
        json.addProperty("gameRunning", ColorBlindOverlayManager.isGameRunning());
        return json;
    }

    /** 读取请求体（GET / 无体 → 空对象） */
    private static JsonObject readBody(HttpExchange exchange) {
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (body.isEmpty()) {
                return new JsonObject();
            }
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Throwable t) {
            return new JsonObject();
        }
    }

    /** 把网页提交的数值写回配置文件（只写提交了的字段，避免用旧值覆盖别处的新值） */
    private static String saveValues(JsonObject body) {
        Map<String, String> updates = new LinkedHashMap<>();
        if (body.has("type") && !body.get("type").isJsonNull()) {
            updates.put(ColorBlindOverlayManager.KEY_TYPE,
                    ColorBlindOverlayManager.normalizeType(body.get("type").getAsString()));
        }
        if (body.has("strength") && !body.get("strength").isJsonNull()) {
            double strength = ColorBlindOverlayManager.parseStrength(body.get("strength").getAsString());
            updates.put(ColorBlindOverlayManager.KEY_STRENGTH, ColorBlindOverlayManager.formatStrength(strength));
        }
        if (body.has("window") && !body.get("window").isJsonNull()) {
            updates.put(ColorBlindOverlayManager.KEY_WINDOW, sanitizeWindow(body.get("window").getAsString()));
        }
        if (updates.isEmpty()) {
            return "没有需要保存的改动";
        }
        ConfigManager.saveClientConfig(updates);
        return "配置已保存";
    }

    /** 窗口标题清洗：去掉换行（ini 单行值）并限制长度 */
    private static String sanitizeWindow(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.replace("\r", " ").replace("\n", " ").trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        send(exchange, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 允许抛 IOException 的处理器：便于统一 try-catch 包装 */
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    /** 调节网页（单页，无外部依赖；{{TYPES}} 由类型表填充） */
    private static String buildHtml() {
        StringBuilder options = new StringBuilder();
        for (Map.Entry<String, String> entry : typeOptions().entrySet()) {
            options.append("<option value=\"").append(entry.getKey()).append("\">")
                    .append(entry.getValue()).append("（").append(entry.getKey()).append("）</option>");
        }
        return HTML.replace("{{TYPES}}", options.toString());
    }

    /** 类型下拉项：CLI 参数值 → 中文名 */
    private static Map<String, String> typeOptions() {
        Map<String, String> options = new LinkedHashMap<>();
        for (String code : ColorBlindOverlayManager.typeCodes()) {
            options.put(code, ColorBlindOverlayManager.typeLabel(code));
        }
        return options;
    }

    private static final String HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>星光启动器 · 色盲辅助</title>
            <style>
            :root { color-scheme: light dark; }
            body { font-family: "Microsoft YaHei", "PingFang SC", system-ui, sans-serif; max-width: 760px;
                   margin: 32px auto; padding: 0 16px; line-height: 1.6; }
            h1 { font-size: 20px; margin: 0 0 4px; }
            h2 { font-size: 16px; margin: 0 0 6px; }
            .hint { font-size: 13px; opacity: .78; margin: 0 0 14px; }
            .card { border: 1px solid rgba(128,128,128,.35); border-radius: 12px; padding: 16px 18px; margin-bottom: 14px; }
            .card.game { border-color: #3b82f6; }
            label { display: block; font-size: 13px; font-weight: 600; margin: 12px 0 6px; }
            label:first-child { margin-top: 0; }
            select, input[type=text] { width: 100%; box-sizing: border-box; padding: 8px 10px; font-size: 14px;
                   border: 1px solid rgba(128,128,128,.45); border-radius: 8px; background: transparent; color: inherit; }
            input[type=range] { width: 100%; }
            .value { font-weight: 700; }
            .actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
            button { padding: 9px 16px; font-size: 14px; border-radius: 8px; cursor: pointer;
                     border: 1px solid rgba(128,128,128,.45); background: transparent; color: inherit; }
            button.primary { background: #3b82f6; border-color: #3b82f6; color: #fff; font-weight: 600; }
            button.digit { min-width: 56px; font-size: 18px; font-weight: 700; }
            button:hover { filter: brightness(1.08); }
            button:disabled { opacity: .5; cursor: default; filter: none; }
            .plate-wrap { display: flex; justify-content: center; margin: 10px 0; }
            #plate { border-radius: 50%; }
            .options { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
            .thumbs { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
            .thumb { text-align: center; font-size: 12px; opacity: .85; }
            .thumb canvas { border-radius: 50%; cursor: pointer; border: 2px solid transparent; }
            .thumb.sel canvas { border-color: #3b82f6; }
            .progress { font-size: 13px; opacity: .8; text-align: center; }
            #status { font-size: 14px; }
            #log { font-size: 13px; opacity: .85; min-height: 20px; }
            .result-type { font-size: 18px; font-weight: 700; }
            .rec { background: rgba(59,130,246,.10); border-radius: 8px; padding: 10px 12px; margin: 10px 0; font-size: 14px; }
            </style>
            </head>
            <body>
            <h1>色盲辅助 · 快速调节</h1>
            <p class="hint">自测小游戏用矫正工具自己的色觉模拟矩阵现场生成检查图：只有「该类型看不出、正常色觉看得出」的色对才会被选中，所以你看不清的题正好能说明问题。</p>

            <!-- ==================== 色觉自测 ==================== -->
            <div class="card game">
              <h2>色觉自测小游戏</h2>
              <div id="gameHint" class="hint">开始后会依次出现 6 张检查图，每张只需回答「你看到的数字」。看不到数字就选「看不到」，不会读错就直接跳过。</div>

              <div id="gameStart">
                <div class="actions"><button id="startBtn" class="primary">开始自测（6 题）</button></div>
              </div>

              <div id="gameQuiz" style="display:none">
                <div class="progress" id="quizProgress">第 1 / 6 题</div>
                <div class="plate-wrap"><canvas id="plate" width="320" height="320"></canvas></div>
                <div class="options" id="quizOptions"></div>
              </div>

              <div id="gameStrength" style="display:none">
                <div class="hint" id="strengthHint"></div>
                <div class="thumbs" id="strengthThumbs"></div>
                <div class="actions"><button id="strengthSkip">都差不多 / 看不清</button></div>
              </div>

              <div id="gameResult" style="display:none">
                <div class="rec">
                  <div>推断结果：<span class="result-type" id="resultType">—</span></div>
                  <div id="resultReason"></div>
                </div>
                <label>建议强度：<span class="value" id="resultStrength">1.0</span></label>
                <input type="range" id="resultStrengthSlider" min="0" max="3" step="0.1" value="1">
                <div class="actions">
                  <button id="applyRec" class="primary">应用推荐设置</button>
                  <button id="retest">重新测试</button>
                </div>
              </div>

              <div id="log"></div>
            </div>

            <!-- ==================== 手动调节 ==================== -->
            <div class="card">
              <h2>手动调节</h2>
              <label>色障类型</label>
              <select id="type">{{TYPES}}</select>
              <label>矫正强度（0.0 ~ 3.0）：<span class="value" id="strengthValue">1.0</span></label>
              <input type="range" id="strength" min="0" max="3" step="0.1" value="1">
              <label>游戏窗口标题（留空 = 默认 Minecraft）</label>
              <input type="text" id="window" placeholder="例如 Minecraft（子串匹配，区分大小写）">
              <div class="actions">
                <button id="save">保存配置</button>
                <button id="restart" class="primary">重启矫正工具进程</button>
                <button id="stop">停止矫正工具</button>
              </div>
              <p class="hint">点「保存配置」写入配置文件（下次启动游戏自动生效）；点「重启矫正工具进程」按当前数值立即重启，游戏窗口在运行时马上能看到效果。矫正工具窗口内按 F10 可手动退出。</p>
            </div>

            <div class="card">
              <div id="status">正在读取状态…</div>
            </div>

            <script>
            const $ = id => document.getElementById(id);
            const fixed = v => Number(v).toFixed(1);
            let MATS = null;

            // ---------- 颜色 / 矩阵工具 ----------
            function mul(M, c) {
              return [0, 1, 2].map(i => M[i][0] * c[0] + M[i][1] * c[1] + M[i][2] * c[2]);
            }
            function clamp255(c) { return c.map(v => Math.max(0, Math.min(255, v))); }
            function dist(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]); }
            // 补偿：屏幕像素 = 原色 + k * E·(原色 − S·原色)（与矫正工具内部一致）
            function compensate(c, s, e, k) {
              const sc = mul(s, c);
              const d = [c[0] - sc[0], c[1] - sc[1], c[2] - sc[2]];
              const ed = mul(e, d);
              return clamp255([c[0] + k * ed[0], c[1] + k * ed[1], c[2] + k * ed[2]]);
            }
            function mulberry32(a) {
              return function () {
                a |= 0; a = a + 0x6D2B79F5 | 0;
                let t = Math.imul(a ^ a >>> 15, 1 | a);
                t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
                return ((t ^ t >>> 14) >>> 0) / 4294967296;
              };
            }
            function hslToRgb(h, s, l) {
              const c = (1 - Math.abs(2 * l - 1)) * s;
              const hp = h / 60;
              const x = c * (1 - Math.abs(hp % 2 - 1));
              let r = 0, g = 0, b = 0;
              if (hp < 1) { r = c; g = x; }
              else if (hp < 2) { r = x; g = c; }
              else if (hp < 3) { g = c; b = x; }
              else if (hp < 4) { g = x; b = c; }
              else if (hp < 5) { r = x; b = c; }
              else { r = c; b = x; }
              const m = l - c / 2;
              return [(r + m) * 255, (g + m) * 255, (b + m) * 255];
            }
            function randColor(rnd) {
              // 中低饱和、中等亮度：接近真实色觉检查图的观感
              return hslToRgb(rnd() * 360, 0.30 + rnd() * 0.45, 0.32 + rnd() * 0.26);
            }
            function rgb(c) { return 'rgb(' + Math.round(c[0]) + ',' + Math.round(c[1]) + ',' + Math.round(c[2]) + ')'; }

            // 找色对：正常色觉下差别明显，但目标类型模拟后几乎同色（tolerance 越大越容易看出 → 用于「宽松」题）
            function pickPair(m, rnd, tolerance) {
              for (let i = 0; i < 4000; i++) {
                const a = randColor(rnd), b = randColor(rnd);
                if (dist(a, b) < 70) continue;
                if (dist(mul(m.s, a), mul(m.s, b)) > tolerance) continue;
                return [a, b];
              }
              return null;
            }

            // 数字遮罩：把数字画到离屏画布，判断像素是否落在笔画上
            function makeMask(text, w, h) {
              const off = document.createElement('canvas');
              off.width = w; off.height = h;
              const c = off.getContext('2d');
              c.fillStyle = '#fff';
              c.textAlign = 'center';
              c.textBaseline = 'middle';
              c.font = 'bold ' + Math.round(h * 0.62) + 'px "Arial Black", Arial, sans-serif';
              c.fillText(text, w / 2, h / 2 + h * 0.02);
              return c.getImageData(0, 0, w, h);
            }
            function maskAt(mask, x, y) {
              const px = Math.round(x), py = Math.round(y);
              if (px < 0 || py < 0 || px >= mask.width || py >= mask.height) return false;
              return mask.data[(py * mask.width + px) * 4 + 3] > 128;
            }

            // 画一张伪等色图；strength > 0 时按补偿后的观感渲染（用于挑强度）
            function renderPlate(canvas, matrix, digit, seed, strength, loose) {
              const ctx = canvas.getContext('2d');
              const W = canvas.width, H = canvas.height;
              const rnd = mulberry32(seed);
              const mask = makeMask(String(digit), W, H);
              const pair = pickPair(matrix, rnd, loose ? 55 : 16);
              ctx.clearRect(0, 0, W, H);
              if (!pair) {
                ctx.fillStyle = rgb([150, 150, 150]);
                ctx.fillRect(0, 0, W, H);
                return false;
              }
              const k = strength || 0;
              const fg = k > 0 ? compensate(pair[0], matrix.s, matrix.e, k) : pair[0];
              const bg = k > 0 ? compensate(pair[1], matrix.s, matrix.e, k) : pair[1];
              const step = W / 26;
              const radius = step * 0.55;
              ctx.save();
              ctx.beginPath();
              ctx.arc(W / 2, H / 2, Math.min(W, H) / 2 - 1, 0, Math.PI * 2);
              ctx.clip();
              ctx.fillStyle = rgb(bg);
              ctx.fillRect(0, 0, W, H);
              for (let gy = step / 2; gy < H; gy += step) {
                for (let gx = step / 2; gx < W; gx += step) {
                  const px = gx + (rnd() - 0.5) * step * 0.6;
                  const py = gy + (rnd() - 0.5) * step * 0.6;
                  const base = maskAt(mask, px, py) ? fg : bg;
                  // 每颗点做轻微色偏，避免整齐得像色块
                  const jitter = 1 + (rnd() - 0.5) * 0.16;
                  ctx.fillStyle = rgb(base.map(v => v * jitter));
                  ctx.beginPath();
                  ctx.arc(px, py, radius * (0.82 + rnd() * 0.3), 0, Math.PI * 2);
                  ctx.fill();
                }
              }
              ctx.restore();
              return true;
            }

            // ---------- 自测题库 ----------
            // loose=false：只有该类型看不出（严格题）；loose=true：色弱者也可能勉强看出（用于区分色盲/色弱）
            const QUESTIONS = [
              { matrix: 'deuteranopia',  axis: 'rg',  loose: false },
              { matrix: 'protanopia',    axis: 'rg',  loose: false },
              { matrix: 'tritanopia',    axis: 'by',  loose: false },
              { matrix: 'tritanopia',    axis: 'by',  loose: false },
              { matrix: 'achromatopsia', axis: 'ach', loose: false },
              { matrix: 'deuteranopia',  axis: 'rg',  loose: true }
            ];
            const AXIS_LABEL = { rg: '红绿轴', by: '蓝黄轴', ach: '明暗/灰度轴' };
            let quiz = null;

            function startQuiz() {
              if (!MATS) { $('gameHint').textContent = '未加载到模拟矩阵，自测不可用。'; return; }
              quiz = { index: 0, answers: [] };
              $('gameStart').style.display = 'none';
              $('gameStrength').style.display = 'none';
              $('gameResult').style.display = 'none';
              $('gameQuiz').style.display = 'block';
              showQuestion();
            }

            function showQuestion() {
              const q = QUESTIONS[quiz.index];
              const matrix = MATS[q.matrix];
              if (!matrix) { finishQuiz(); return; }
              const digit = 1 + Math.floor(Math.random() * 9);
              const seed = Math.floor(Math.random() * 1e9);
              const ok = renderPlate($('plate'), matrix, digit, seed, 0, q.loose);
              quiz.current = { digit: digit, q: q, rendered: ok };
              $('quizProgress').textContent = '第 ' + (quiz.index + 1) + ' / ' + QUESTIONS.length + ' 题'
                + '（' + AXIS_LABEL[q.axis] + (q.loose ? ' · 较容易' : '') + '）';
              const options = [digit];
              while (options.length < 4) {
                const d = 1 + Math.floor(Math.random() * 9);
                if (options.indexOf(d) < 0) options.push(d);
              }
              options.sort(() => Math.random() - 0.5);
              const box = $('quizOptions');
              box.innerHTML = '';
              options.forEach(function (d) {
                const btn = document.createElement('button');
                btn.className = 'digit';
                btn.textContent = d;
                btn.onclick = function () { answerQuiz(d); };
                box.appendChild(btn);
              });
              const none = document.createElement('button');
              none.textContent = '看不到数字';
              none.onclick = function () { answerQuiz(null); };
              box.appendChild(none);
              quiz.current.options = options;
            }

            function answerQuiz(choice) {
              const cur = quiz.current;
              const correct = cur.rendered ? (choice === cur.digit) : false;
              quiz.answers.push({ axis: cur.q.axis, loose: cur.q.loose, correct: correct });
              quiz.index++;
              if (quiz.index >= QUESTIONS.length) { finishQuiz(); } else { showQuestion(); }
            }

            function countWrong(axis, loose) {
              return quiz.answers.filter(function (a) {
                return a.axis === axis && !!a.loose === !!loose && !a.correct;
              }).length;
            }

            function finishQuiz() {
              $('gameQuiz').style.display = 'none';
              const rg = countWrong('rg', false);
              const by = countWrong('by', false);
              const ach = countWrong('ach', false);
              const rgLoose = countWrong('rg', true);
              let code, reason;
              if (rg === 0 && by === 0 && ach === 0) {
                code = null;
                reason = '红绿轴 / 蓝黄轴 / 灰度轴的检查图你都能辨读，未检出明显的色觉异常。如果游戏中仍觉得颜色难分，可在下方手动换类型试效果。';
              } else if (rg >= 2 && (by >= 1 || ach >= 1)) {
                code = ach === 1 ? 'achromatopsia' : 'achromatomaly';
                reason = '多条颜色轴同时辨读困难（红绿错 ' + rg + ' 题、蓝黄错 ' + by + ' 题、灰度错 ' + ach + ' 题），偏向全色盲 / 全色弱一侧。';
              } else if (by >= 1 && rg <= 1) {
                code = by >= 2 ? 'tritanopia' : 'tritanomaly';
                reason = '蓝黄轴辨读困难（错 ' + by + ' 题），红绿轴基本正常，偏向蓝黄色觉障碍。';
              } else {
                code = rgLoose >= 1 ? 'deuteranopia' : 'deuteranomaly';
                reason = '红绿轴辨读困难（错 ' + rg + ' 题），蓝黄轴正常'
                  + (rgLoose >= 1 ? '；连较容易的红绿题也辨读困难，偏向色盲一侧。' : '；较容易的红绿题能辨读，偏向色弱一侧。');
              }
              quiz.verdict = code;
              if (!code) {
                $('resultType').textContent = '未检出明显异常';
                $('resultReason').textContent = reason;
                $('gameResult').style.display = 'block';
                return;
              }
              showStrengthStage(code, reason);
            }

            // ---------- 强度小游戏：按补偿后的观感挑「最清楚」的一档 ----------
            function showStrengthStage(code, reason) {
              quiz.resultReason = reason;
              $('gameStrength').style.display = 'block';
              $('strengthHint').textContent = '已推断为「' + typeName(code) + '」。下面 7 张图是按不同矫正强度渲染的同一张检查图'
                + '（0.0 / 0.5 / … / 3.0），请挑出数字最清楚的那一张；看不清就点下面的按钮。';
              const box = $('strengthThumbs');
              box.innerHTML = '';
              const digit = 2 + Math.floor(Math.random() * 7);
              const seed = Math.floor(Math.random() * 1e9);
              [0, 0.5, 1, 1.5, 2, 2.5, 3].forEach(function (k) {
                const wrap = document.createElement('div');
                wrap.className = 'thumb';
                const cv = document.createElement('canvas');
                cv.width = 120; cv.height = 120;
                renderPlate(cv, MATS[code], digit, seed, k, false);
                const cap = document.createElement('div');
                cap.textContent = fixed(k);
                wrap.appendChild(cv);
                wrap.appendChild(cap);
                wrap.onclick = function () {
                  const all = box.querySelectorAll('.thumb');
                  for (let i = 0; i < all.length; i++) all[i].classList.remove('sel');
                  wrap.classList.add('sel');
                  finishTest(code, k, reason);
                };
                box.appendChild(wrap);
              });
              $('strengthSkip').onclick = function () { finishTest(code, 1, reason); };
            }

            function finishTest(code, strength, reason) {
              $('gameStrength').style.display = 'none';
              $('gameResult').style.display = 'block';
              quiz.resultStrength = strength;
              $('resultType').textContent = typeName(code) + '（' + code + '）';
              $('resultReason').textContent = reason;
              $('resultStrength').textContent = fixed(strength);
              $('resultStrengthSlider').value = strength;
            }

            function typeName(code) {
              const opt = $('type').querySelector('option[value="' + code + '"]');
              return opt ? opt.textContent.replace(/（.*$/, '') : code;
            }

            // ---------- 网络 ----------
            function payload() {
              return { type: $('type').value, strength: parseFloat($('strength').value), window: $('window').value };
            }
            async function post(url, body) {
              const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body || payload()) });
              return await res.json();
            }
            async function refresh() {
              try {
                const res = await fetch('/api/status');
                renderStatus(await res.json());
              } catch (e) { /* 启动器已退出，忽略 */ }
            }
            function renderStatus(c) {
              $('status').innerHTML = '总开关：<b>' + (c.enabled ? '开启' : '关闭') + '</b>　｜　游戏：<b>'
                + (c.gameRunning ? '运行中' : '未运行') + '</b>　｜　矫正工具：<b>'
                + (c.running ? '运行中' : '未运行') + '</b>';
            }
            function renderConfig(c) {
              $('type').value = c.type;
              $('strength').value = c.strength;
              $('strengthValue').textContent = fixed(c.strength);
              $('window').value = c.window || '';
              renderStatus(c);
            }
            async function load() {
              try {
                const res = await fetch('/api/config');
                renderConfig(await res.json());
              } catch (e) {
                $('status').textContent = '无法连接启动器（启动器可能已关闭）';
              }
              try {
                const res = await fetch('/api/matrices');
                const data = await res.json();
                if (data.ok) {
                  MATS = data.types;
                  $('gameHint').textContent = '开始后会依次出现 ' + QUESTIONS.length
                    + ' 张检查图，每张只需回答「你看到的数字」。看不到数字就选「看不到」，不会读错就直接跳过。';
                } else {
                  $('gameHint').textContent = '未找到 ColorBlindOverlay.exe，无法读取色觉模拟矩阵 —— 自测小游戏不可用，请用下方手动调节。';
                  $('startBtn').disabled = true;
                }
              } catch (e) {
                $('gameHint').textContent = '无法读取色觉模拟矩阵，自测小游戏不可用。';
                $('startBtn').disabled = true;
              }
            }

            $('startBtn').onclick = startQuiz;
            $('retest').onclick = startQuiz;
            $('applyRec').onclick = async function () {
              const strength = parseFloat($('resultStrengthSlider').value);
              const body = { type: quiz.verdict, strength: strength };
              const r = await post('/api/restart', body);
              $('log').textContent = '已应用推荐设置：' + typeName(quiz.verdict) + ' · 强度 ' + fixed(strength) + '　' + r.message;
              await load();
              refresh();
            };
            $('resultStrengthSlider').addEventListener('input', function (e) {
              $('resultStrength').textContent = fixed(e.target.value);
            });
            $('strength').addEventListener('input', function (e) {
              $('strengthValue').textContent = fixed(e.target.value);
            });
            $('save').onclick = async function () { $('log').textContent = (await post('/api/save')).message; refresh(); };
            $('restart').onclick = async function () { $('log').textContent = (await post('/api/restart')).message; refresh(); };
            $('stop').onclick = async function () { $('log').textContent = (await post('/api/stop')).message; refresh(); };
            load();
            setInterval(refresh, 2000);
            </script>
            </body>
            </html>
            """;
}

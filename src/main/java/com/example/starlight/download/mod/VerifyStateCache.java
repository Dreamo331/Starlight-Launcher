package com.example.starlight.download.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件校验状态缓存（M2）：记住「这个文件在某个时刻校验通过」，避免每次补全都重算 SHA-1。
 *
 * <h3>为什么收益最大</h3>
 * 补全的校验阶段本来对<b>每个</b>文件算一遍 SHA-1。二次补全/修复已有游戏时，
 * 绝大多数文件其实没变，重算哈希纯属浪费 —— 对上千个 assets + 几百个库，
 * 这是二次补全里最大的一块 I/O。本缓存让"上次验过且之后没被改过"的文件直接跳过哈希。
 *
 * <h3>与既有 {@link com.example.starlight.download.VerifySettings} 的层级关系</h3>
 * <pre>
 * 大小不符        → 必重下（不看缓存）
 * FastVerify 开   → 有 size 就信（用户显式选择"不要哈希"，优先级最高）
 * 本缓存命中      → mtime + size 都没变 → 跳过哈希        ← M2 插入的一级
 * 否则            → 算 SHA-1
 * </pre>
 * 即本缓存<b>只在 FastVerify 关闭时生效</b>，不改变任何现有开关的语义。
 *
 * <h3>安全边界</h3>
 * <ul>
 *   <li>只记录「校验通过」的时刻，不记录失败；</li>
 *   <li>判据是 {@code mtime} + {@code size} 都不变 —— 文件被外部修改/替换后 mtime 会变，
 *       自动回落完整哈希；</li>
 *   <li>下载写盘后<b>必须</b>重新登记（调用 {@link #markValidated}），否则下次会误信旧条目；</li>
 *   <li>缓存文件损坏 / 缺失 → 当空缓存处理，绝不阻断补全。</li>
 * </ul>
 */
public final class VerifyStateCache {

    private static final Logger log = LoggerFactory.getLogger(VerifyStateCache.class);

    /** 缓存文件相对 gameDir 的位置 */
    private static final String CACHE_RELATIVE = ".cache/verify-state.json";

    /**
     * mtime 容差（毫秒）。
     *
     * <p><b>必须为 0</b>。缓存判据是「文件 mtime 是否仍等于登记时的 mtime」——精确相等才是
     * 可证明的"没被动过"。
     *
     * <p>曾经用过"比较当前时刻 + 容差"的写法，被实测证伪：登记时写入的
     * {@code validatedAt} 会比文件 mtime 晚几十到几百毫秒（文件创建与登记之间有时间差），
     * 于是**随后几百毫秒内发生的文件改写会落进容差窗口，被错误地信任**。
     * 现在改为记录文件自身 mtime 并精确比较，任何改动都必然被发现。
     */
    private static final long MTIME_TOLERANCE_MS = 0L;

    /**
     * 单条记录：某路径在「自身 mtime 为 mtime、大小为 size」时校验通过。
     *
     * <p>{@code validatedAt} 只作诊断用（人类可读的"何时验过"），<b>不参与判据</b>。
     */
    private static final class Entry {
        long size;
        long mtime;
        long validatedAt;

        Entry(long size, long mtime, long validatedAt) {
            this.size = size;
            this.mtime = mtime;
            this.validatedAt = validatedAt;
        }
    }

    private final Path cacheFile;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    private VerifyStateCache(Path gameDir) {
        this.cacheFile = gameDir.resolve(CACHE_RELATIVE);
    }

    /**
     * 载入（或新建）某游戏目录的校验状态缓存。文件不存在或损坏时返回空缓存，不抛异常。
     *
     * @param gameDir Minecraft 游戏根目录
     */
    public static VerifyStateCache loadFor(Path gameDir) {
        VerifyStateCache cache = new VerifyStateCache(gameDir);
        cache.read();
        return cache;
    }

    /**
     * 该文件是否「上次校验通过、且之后没被改动过」。
     *
     * @param file 待判定文件
     * @return true = 可以跳过哈希计算
     */
    public boolean isFresh(Path file) {
        try {
            if (!Files.isRegularFile(file)) return false;
            Entry e = entries.get(key(file));
            if (e == null) return false;
            if (Files.size(file) != e.size) return false;
            long mtime = fileMtime(file);
            // 精确相等才算"没被动过"。任何改写/替换都会让 mtime 变化 → 自动回落完整哈希。
            return Math.abs(mtime - e.mtime) <= MTIME_TOLERANCE_MS;
        }
        catch (IOException ex) {
            return false;
        }
    }

    /**
     * 登记「该文件此刻校验通过」。
     *
     * <p>下载写盘之后、以及完整哈希校验通过之后都必须调用，否则缓存会失效或误信。
     *
     * @param file 已确认有效的文件
     */
    public void markValidated(Path file) {
        try {
            if (!Files.isRegularFile(file)) return;
            long size = Files.size(file);
            long mtime = fileMtime(file);
            entries.put(key(file), new Entry(size, mtime, System.currentTimeMillis()));
            dirty = true;
        }
        catch (IOException ignored) {
            // 记录失败只影响加速，不影响正确性
        }
    }

    /**
     * 读取文件 mtime（毫秒）。
     *
     * <p>用 {@code toMillis()} + 纳秒归零：不同 JDK / 文件系统下 {@link FileTime} 的纳秒部分
     * 表示不一致，统一截到毫秒再比较，避免"同一个文件两次读到不同值"造成的假不命中。
     */
    private static long fileMtime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).to(java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 该路径已不再有效（文件被删/被判定损坏），移除其记录 */
    public void invalidate(Path file) {
        if (entries.remove(key(file)) != null) {
            dirty = true;
        }
    }

    /** 当前缓存的条目数（日志 / 诊断用） */
    public int size() {
        return entries.size();
    }

    /**
     * 落盘（脏才写）。原子替换，避免写一半崩溃留下坏缓存。
     *
     * <p>由补全流程在结束时调用一次；写失败只记日志，不影响补全结果。
     */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        try {
            Files.createDirectories(cacheFile.getParent());
            JsonArray array = new JsonArray();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("p", e.getKey());
                o.addProperty("s", e.getValue().size);
                o.addProperty("m", e.getValue().mtime);
                o.addProperty("v", e.getValue().validatedAt);
                array.add(o);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.add("entries", array);

            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            log.debug("校验状态缓存写入失败（不影响补全）: {}", e.toString());
        }
    }

    /** 清空并删除缓存文件（诊断 / 强制全量重校验用） */
    public void clear() {
        entries.clear();
        dirty = false;
        try {
            Files.deleteIfExists(cacheFile);
        }
        catch (IOException ignored) {
            // 删不掉最多是下次仍然读到旧条目，不影响正确性（mtime 判据兜底）
        }
    }

    // 内部 //
    /** 缓存键：相对 gameDir 的路径（避免整机路径写进缓存文件，也便于目录迁移后仍可用） */
    private String key(Path file) {
        Path base = cacheFile.getParent() == null ? null : cacheFile.getParent().getParent();
        if (base != null) {
            try {
                return base.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            }
            catch (IllegalArgumentException ignored) {
                // 不在同一根下（跨盘）：退回用文件名
            }
        }
        return file.getFileName().toString();
    }

    private void read() {
        if (!Files.isRegularFile(cacheFile)) return;
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray array = root.getAsJsonArray("entries");
            if (array == null) return;
            for (JsonElement el : array) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("p") || !o.has("s") || !o.has("v")) continue;
                entries.put(o.get("p").getAsString(), new Entry(
                        o.get("s").getAsLong(),
                        o.has("m") ? o.get("m").getAsLong() : 0L,
                        o.get("v").getAsLong()));
            }
            log.debug("校验状态缓存已载入: {} 条 ({})", entries.size(), cacheFile);
        }
        catch (Exception e) {
            // 损坏就当空缓存：绝不让缓存问题阻断补全
            entries.clear();
            log.debug("校验状态缓存解析失败，按空缓存继续: {}", e.toString());
        }
    }
}

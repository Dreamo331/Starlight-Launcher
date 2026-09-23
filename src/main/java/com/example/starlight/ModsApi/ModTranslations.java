package com.example.starlight.ModsApi;

import com.example.starlight.util.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mod 中文名字典：解析 {@code mod_data.txt} / {@code modpack_data.txt}，提供中文搜索与反查能力。
 *
 * <p>本类是对 HMCL {@code org.jackhuang.hmcl.ui.instances.ModTranslations} 的移植与增强，
 * 是整个中文搜索链路的数据基础。字典行的字段含义：
 * <pre>
 * ① curseforge slug ② mcmod 编号 ③ modId 列表（逗号分隔） ④ 中文名 ⑤ 英文名 ⑥ 缩写
 * </pre>
 *
 * <p>对外提供三类能力：
 * <ul>
 *   <li><b>中文召回</b>：{@link #searchMod(String)} 用最长公共子序列（LCS）把
 *       「机械动力」这类中文查询匹配到字典条目，再取出对应的英文名作为检索词；</li>
 *   <li><b>远程反查</b>：{@link #findForRemoteMod(String, String)} 用 slug / 项目 ID / 标题
 *       反查中文名，用于列表与详情页显示中文标题；</li>
 *   <li><b>本地反查</b>：{@link #getMod(String, String)} 用 modId 或文件名匹配已安装模组。</li>
 * </ul>
 *
 * <p>字典数据可运行时更新（见 {@link ModDictionaryManager}），{@link #reload()} 会让本枚举
 * 丢弃全部索引并在下次访问时重新读取。
 *
 * <p>线程安全：各索引采用「volatile + 双重检查」懒加载；构建过程互斥但读取无锁。
 */
public enum ModTranslations {

    /** 模组字典 */
    MOD(ModDictionaryManager.MOD_RESOURCE, ModDictionaryManager.MOD_CACHE_FILE) {
        @Override
        public String getMcmodUrl(Mod mod) {
            return String.format("https://www.mcmod.cn/class/%s.html", mod.getMcmod());
        }
    },

    /** 整合包字典 */
    MODPACK(ModDictionaryManager.MODPACK_RESOURCE, ModDictionaryManager.MODPACK_CACHE_FILE) {
        @Override
        public String getMcmodUrl(Mod mod) {
            return String.format("https://www.mcmod.cn/modpack/%s.html", mod.getMcmod());
        }
    },

    /** 空字典：资源包 / 光影包 / 世界等没有中文数据的类型使用它 */
    EMPTY("", "") {
        @Override
        public String getMcmodUrl(Mod mod) {
            return "";
        }
    };

    /** 文件名前缀匹配时允许的最短关键词长度（过短的关键词会制造大量误命中） */
    private static final int MIN_KEYWORD_LENGTH = 3;
    /** 文件名前缀匹配时尝试的最长前缀长度 */
    private static final int MAX_FILENAME_PREFIX = 40;

    /** 按资源类型选择字典；没有对应字典时返回 {@link #EMPTY} */
    public static ModTranslations getTranslationsByAddonType(RemoteModRepository.Type type) {
        if (type == null) return EMPTY;
        return switch (type) {
            case MOD -> MOD;
            case MODPACK -> MODPACK;
            default -> EMPTY;
        };
    }

    private final String resourceName;
    private final String cacheFileName;

    private volatile List<Mod> mods;
    private volatile Map<String, Mod> modIdMap;      // modId -> 条目
    private volatile Map<String, Mod> subnameMap;    // 清洗后的英文名 -> 条目
    private volatile Map<String, Mod> curseForgeMap; // curseforge slug -> 条目
    private volatile Map<String, Mod> titleMap;      // 归一化名称（中/英/缩写）-> 条目
    private volatile List<Keyword> keywords;         // 参与中文召回的关键词
    private volatile int maxKeywordLength = -1;

    ModTranslations(String resourceName, String cacheFileName) {
        this.resourceName = resourceName;
        this.cacheFileName = cacheFileName;
    }

    /** 是否为「无字典」实例 */
    public boolean isEmpty() {
        return this == EMPTY;
    }

    /** 字典条目数（会触发加载） */
    public int size() {
        return getMods().size();
    }

    /** 丢弃全部索引，下次访问时重新读取字典文件（运行时更新后调用） */
    public void reload() {
        synchronized (this) {
            mods = null;
            modIdMap = null;
            subnameMap = null;
            curseForgeMap = null;
            titleMap = null;
            keywords = null;
            maxKeywordLength = -1;
        }
    }

    // ==================== 加载与索引构建 ====================

    /** 全部字典条目（懒加载，失败时为空列表） */
    public List<Mod> getMods() {
        List<Mod> cached = this.mods;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.mods != null) return this.mods;
            if (resourceName.isEmpty()) return this.mods = List.of();

            List<Mod> parsed = new ArrayList<>();
            int illegal = 0;
            for (String line : ModDictionaryManager.readLines(resourceName, cacheFileName)) {
                if (!ModDictionaryManager.isValidEntryLine(line)) continue;
                try {
                    parsed.add(new Mod(line));
                } catch (Exception e) {
                    illegal++;
                }
            }
            if (illegal > 0) {
                System.err.println("[ModDict] " + resourceName + " 跳过 " + illegal + " 条非法记录");
            }
            return this.mods = List.copyOf(parsed);
        }
    }

    private Map<String, Mod> getModIdMap() {
        Map<String, Mod> cached = this.modIdMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.modIdMap != null) return this.modIdMap;
            Map<String, Mod> map = new HashMap<>(Math.max(16, getMods().size()));
            for (Mod mod : getMods()) {
                for (String id : mod.getModIds()) {
                    if (TextUtils.isNotBlank(id)) map.putIfAbsent(id.trim().toLowerCase(Locale.ROOT), mod);
                }
            }
            return this.modIdMap = map;
        }
    }

    private Map<String, Mod> getSubnameMap() {
        Map<String, Mod> cached = this.subnameMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.subnameMap != null) return this.subnameMap;
            Map<String, Mod> map = new HashMap<>(Math.max(16, getMods().size()));
            for (Mod mod : getMods()) {
                String subname = cleanSubname(mod.getSubname());
                if (TextUtils.isNotBlank(subname)) map.putIfAbsent(subname, mod);
            }
            return this.subnameMap = map;
        }
    }

    private Map<String, Mod> getCurseForgeMap() {
        Map<String, Mod> cached = this.curseForgeMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.curseForgeMap != null) return this.curseForgeMap;
            Map<String, Mod> map = new HashMap<>(Math.max(16, getMods().size()));
            for (Mod mod : getMods()) {
                if (TextUtils.isNotBlank(mod.getCurseforge())) {
                    map.putIfAbsent(mod.getCurseforge().trim().toLowerCase(Locale.ROOT), mod);
                }
            }
            return this.curseForgeMap = map;
        }
    }

    /**
     * 归一化名称索引：英文名优先，其次中文名，最后（长度 ≥ 3 的）缩写。
     *
     * <p>之所以要二级索引：远程接口返回的标题常带后缀、大小写与标点差异
     * （如 {@code "Just Enough Items (JEI)"}），直接比对原始字符串几乎命中不了。
     */
    private Map<String, Mod> getTitleMap() {
        Map<String, Mod> cached = this.titleMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.titleMap != null) return this.titleMap;
            Map<String, Mod> map = new HashMap<>(Math.max(16, getMods().size() * 2));
            for (Mod mod : getMods()) {
                putNormalized(map, mod.getSubname(), mod);
            }
            for (Mod mod : getMods()) {
                putNormalized(map, mod.getName(), mod);
            }
            for (Mod mod : getMods()) {
                String abbr = TextUtils.normalizeForMatch(mod.getAbbr());
                if (abbr.length() >= MIN_KEYWORD_LENGTH) {
                    putNormalized(map, mod.getAbbr(), mod);
                }
            }
            return this.titleMap = map;
        }
    }

    private static void putNormalized(Map<String, Mod> map, String raw, Mod mod) {
        String key = TextUtils.normalizeForMatch(raw);
        if (key.length() < MIN_KEYWORD_LENGTH) return;
        map.putIfAbsent(key, mod);

        // 额外登记「去掉开头冠词」的变体：字典里常见 "The Twilight Forest" 这类英文名，
        // 而模组文件名通常写作 twilightforest-*.jar，不做这一步会漏掉这类条目。
        // 仅当冠词在原名称中本身是独立单词时才剥离，避免把 "Another Mod" 误切成 "othermod"。
        List<String> tokens = TextUtils.tokenize(raw);
        if (tokens.size() > 1 && isLeadingArticle(tokens.get(0))) {
            String rest = TextUtils.normalizeForMatch(String.join(" ", tokens.subList(1, tokens.size())));
            if (rest.length() >= MIN_KEYWORD_LENGTH) map.putIfAbsent(rest, mod);
        }
    }

    /** 该单词是否为可剥离的开头冠词 */
    private static boolean isLeadingArticle(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.equals("the") || lower.equals("a") || lower.equals("an");
    }

    private List<Keyword> getKeywords() {
        List<Keyword> cached = this.keywords;
        if (cached != null) return cached;
        synchronized (this) {
            if (this.keywords != null) return this.keywords;
            List<Keyword> list = new ArrayList<>(getMods().size() * 3);
            int maxLength = 0;
            for (Mod mod : getMods()) {
                maxLength = Math.max(maxLength, addKeyword(list, mod.getName(), mod));
                maxLength = Math.max(maxLength, addKeyword(list, mod.getSubname(), mod));
                maxLength = Math.max(maxLength, addKeyword(list, mod.getAbbr(), mod));
            }
            this.maxKeywordLength = maxLength;
            return this.keywords = list;
        }
    }

    private static int addKeyword(List<Keyword> list, String text, Mod mod) {
        if (TextUtils.isBlank(text)) return 0;
        String trimmed = text.trim();
        list.add(new Keyword(trimmed, mod));
        return trimmed.length();
    }

    private int getMaxKeywordLength() {
        int cached = this.maxKeywordLength;
        if (cached >= 0) return cached;
        getKeywords(); // 触发初始化
        return Math.max(1, this.maxKeywordLength);
    }

    // ==================== 中文召回 ====================

    /**
     * 中文关键词召回：把查询串与字典中的中文名/英文名/缩写做最长公共子序列匹配，按相似度降序返回。
     *
     * <p>用 LCS 而非 {@code contains} 的原因：用户输入常与字典名不完全一致
     * （少打、多打、顺序颠倒、只用缩写），LCS 容忍这类噪声。
     * 命中门槛为 {@code LCS >= max(1, 查询长度 - 3)}，即允许最多 3 个字符不匹配。
     *
     * <p>调用方（{@link LocalizedRemoteModRepository}）只取前若干条作为英文检索词。
     *
     * @param query 用户输入的查询串（空白字符会被忽略）
     * @return 按分数降序的字典条目；字典为空或无命中时返回空列表
     */
    public List<Mod> searchMod(String query) {
        if (TextUtils.isBlank(query) || isEmpty()) return List.of();

        StringBuilder compact = new StringBuilder(query.length());
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (!Character.isSpaceChar(ch) && !Character.isWhitespace(ch)) compact.append(ch);
        }
        String normalizedQuery = compact.toString();
        if (normalizedQuery.isEmpty()) return List.of();

        int maxKeywordLength = getMaxKeywordLength();
        if (maxKeywordLength <= 0) return List.of();

        TextUtils.LongestCommonSubsequence lcs =
                new TextUtils.LongestCommonSubsequence(normalizedQuery.length(), maxKeywordLength);
        int threshold = Math.max(1, normalizedQuery.length() - 3);

        List<Scored> hits = new ArrayList<>();
        for (Keyword keyword : getKeywords()) {
            int value = lcs.calc(normalizedQuery, keyword.text());
            if (value >= threshold) hits.add(new Scored(value, keyword.mod()));
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));

        // 同一字典条目可能因多个关键词（中文名/英文名/缩写）重复命中，按条目去重并保持分数序。
        // Mod 未实现 equals，这里按「同一实例」判定，用 IdentityHashMap 保证 O(n)。
        List<Mod> result = new ArrayList<>(Math.min(hits.size(), 64));
        Set<Mod> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Scored hit : hits) {
            if (seen.add(hit.mod())) result.add(hit.mod());
        }
        return result;
    }

    // ==================== 反查 ====================

    /** 按 curseforge slug 反查（与 HMCL 的 getModByCurseForgeId 等价） */
    public Mod getModByCurseForgeId(String slug) {
        if (TextUtils.isBlank(slug)) return null;
        return getCurseForgeMap().get(slug.trim().toLowerCase(Locale.ROOT));
    }

    /** 按 modId 反查（不区分大小写） */
    public Mod getModByModId(String modId) {
        if (TextUtils.isBlank(modId)) return null;
        return getModIdMap().get(modId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 按名称反查：先按归一化名称精确匹配，未命中再尝试去掉括号后缀后的主干
     * （如 {@code "Just Enough Items (JEI)"} → {@code "Just Enough Items"}）。
     */
    public Mod getModByTitle(String title) {
        if (TextUtils.isBlank(title)) return null;
        Map<String, Mod> map = getTitleMap();

        Mod mod = map.get(TextUtils.normalizeForMatch(title));
        if (mod != null) return mod;

        String stripped = TextUtils.stripBracketSuffix(title);
        if (!stripped.equals(title)) {
            mod = map.get(TextUtils.normalizeForMatch(stripped));
            if (mod != null) return mod;
        }
        return null;
    }

    /**
     * 按文件名反查（用于已安装模组列表）：把文件名归一化后，从最长前缀开始尝试与字典关键词匹配。
     *
     * <p>例：{@code jei-1.20.1-forge-15.2.0.27.jar} 归一化为 {@code jei1201forge152027}，
     * 前缀 {@code jei} 命中缩写条目。取最长前缀可避免 {@code create} 误命中
     * {@code create-mekanism} 这类同前缀条目。
     */
    public Mod getModByFileName(String fileName) {
        if (TextUtils.isBlank(fileName)) return null;
        String normalized = TextUtils.normalizeForMatch(TextUtils.stripJarExtension(fileName));
        if (normalized.length() < MIN_KEYWORD_LENGTH) return null;

        Map<String, Mod> map = getTitleMap();
        int max = Math.min(normalized.length(), MAX_FILENAME_PREFIX);
        for (int len = max; len >= MIN_KEYWORD_LENGTH; len--) {
            Mod mod = map.get(normalized.substring(0, len));
            if (mod != null) return mod;
        }
        return null;
    }

    /**
     * 本地模组反查：优先按 modId 精确命中，失败后回退按文件名（含英文名/缩写）匹配。
     *
     * @param modId    模组元数据里的 modId（可为 null）
     * @param fileName 模组文件名（可为 null）
     */
    public Mod getMod(String modId, String fileName) {
        Mod mod = getModByModId(modId);
        if (mod != null) return mod;
        return getModByFileName(fileName);
    }

    /**
     * 远程项目反查：按「slug / 项目 ID → 标题」的顺序尝试。
     *
     * @param slugOrId 项目 slug 或平台数字 ID（CurseForge 的数字 ID 也能命中字典的 modId 索引）
     * @param title    项目标题
     */
    public Mod findForRemoteMod(String slugOrId, String title) {
        Mod mod = getModByCurseForgeId(slugOrId);
        if (mod != null) return mod;
        mod = getModByModId(slugOrId);
        if (mod != null) return mod;
        mod = getModByTitle(slugOrId);
        if (mod != null) return mod;
        return getModByTitle(title);
    }

    /** MC 百科页面地址；{@link #EMPTY} 返回空串 */
    public abstract String getMcmodUrl(Mod mod);

    // ==================== 工具 ====================

    /**
     * 清洗英文名：只保留字母数字与 {@code .+\}，剔除空白与常见标点；
     * 一旦出现其他字符（如中日韩字符、emoji）就整条丢弃，避免拿脏名字去匹配造成误命中。
     * 与 HMCL 的实现保持一致。
     */
    static String cleanSubname(String subname) {
        if (TextUtils.isBlank(subname)) return "";
        StringBuilder builder = new StringBuilder(subname.length());
        boolean dropped = false;
        for (int i = 0; i < subname.length(); ) {
            int ch = subname.codePointAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ".+\\".indexOf(ch) >= 0) {
                builder.appendCodePoint(ch);
            } else if (Character.isWhitespace(ch)
                    || "':_-/&()[]{}|,!?~•".indexOf(ch) >= 0
                    || (ch >= 0x1F300 && ch <= 0x1FAFF)) {
                // 这些字符只是噪音，跳过（不写入，也不因此丢弃整条）
            } else {
                dropped = true;
                break;
            }
            i += Character.charCount(ch);
        }
        if (dropped) return "";
        return builder.length() == subname.length() ? subname : builder.toString();
    }

    // ==================== 内部类型 ====================

    /** 参与中文召回的关键词及其归属条目 */
    private record Keyword(String text, Mod mod) {
    }

    /** LCS 分数与条目 */
    private record Scored(int score, Mod mod) {
    }

    /**
     * 一条字典记录。
     *
     * <p>注意字段命名沿用 HMCL：{@code name} 是<b>中文名</b>（第 4 列），
     * {@code subname} 是<b>英文名</b>（第 5 列）。拼接英文检索词时用的正是 subname。
     */
    public static final class Mod {
        private final String curseforge;
        private final String mcmod;
        private final List<String> modIds;
        private final String name;
        private final String subname;
        private final String abbr;

        /** 按分号拆解一行字典数据 */
        public Mod(String line) {
            String[] items = line.split(";", -1);
            if (items.length != 6) {
                throw new IllegalArgumentException("非法字典行，期望 6 个字段: " + line);
            }
            this.curseforge = items[0].trim();
            this.mcmod = items[1].trim();
            List<String> ids = new ArrayList<>(2);
            for (String id : items[2].split(",")) {
                if (TextUtils.isNotBlank(id)) ids.add(id.trim());
            }
            this.modIds = List.copyOf(ids);
            this.name = items[3].trim();
            this.subname = items[4].trim();
            this.abbr = items[5].trim();
        }

        /** 展示名，格式与 HMCL 一致：{@code [缩写] 中文名 (英文名)} */
        public String getDisplayName() {
            StringBuilder builder = new StringBuilder();
            if (TextUtils.isNotBlank(abbr)) builder.append('[').append(abbr).append("] ");
            builder.append(name);
            if (TextUtils.isNotBlank(subname)) builder.append(" (").append(subname).append(')');
            return builder.toString();
        }

        /** 中文名（可能为空） */
        public String getName() {
            return name;
        }

        /** 英文名（可能为空） */
        public String getSubname() {
            return subname;
        }

        /** 缩写（可能为空） */
        public String getAbbr() {
            return abbr;
        }

        /** CurseForge slug（可能为空，字典中并非每个 mod 都收录了 CurseForge 链接） */
        public String getCurseforge() {
            return curseforge;
        }

        /** MC 百科编号（可能为空） */
        public String getMcmod() {
            return mcmod;
        }

        /** 该模组已知的全部 modId */
        public List<String> getModIds() {
            return modIds;
        }

        /** 是否含有可展示的中文名 */
        public boolean hasChineseName() {
            return TextUtils.containsChinese(name);
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }
}

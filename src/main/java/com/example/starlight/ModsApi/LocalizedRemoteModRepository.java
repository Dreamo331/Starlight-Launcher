package com.example.starlight.ModsApi;

import com.example.starlight.download.DownloadProvider;
import com.example.starlight.util.TextUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 支持「中文搜索」的远程仓库装饰器：把中文查询翻译成英文检索词后再查后端，并用中文名重排结果。
 *
 * <p>这是 HMCL {@code LocalizedRemoteAddonRepository} 的移植版，思路是：
 * <ol>
 *   <li><b>非中文查询直接透传</b>——不含中文时零开销，行为与包装前完全一致；</li>
 *   <li><b>中文 → 英文候选词</b>：用字典的中文召回（LCS）取分数最高的前 {@value #MAX_ENGLISH_CANDIDATES}
 *       个条目，把它们的英文名分词后拼成检索词；</li>
 *   <li><b>依次尝试候选词</b>，用第一个能返回结果的检索词查询后端；</li>
 *   <li><b>结果重排</b>：能反查到中文名的项目排在前半区（区内按与查询串的相似度排序），
 *       反查不到中文名的保持后端原序排在后半区。</li>
 * </ol>
 *
 * <p><b>与 HMCL 的一处有意差异</b>：HMCL 在中文搜索时会强制把后端排序换成
 * 「Modrinth 按名称 / CurseForge 按热度」，因为翻译后的检索词会让后端相关度排序失真。
 * 本启动器的排序是用户在工具条上显式选择的（按下载量/按更新时间/按标题），属于明确意图，
 * 因此这里<b>保留用户选择的排序</b>，只对中文命中做区间内重排。
 *
 * <p>用 {@link #wrap(RemoteModRepository)} 构造，没有字典的资源类型会原样返回原仓库。
 */
public class LocalizedRemoteModRepository implements RemoteModRepository {

    /** 中文名包含查询字符时的相似度加权：每命中一个字符，编辑距离减该值 */
    private static final int CONTAIN_CHINESE_WEIGHT = 10;
    /** 最多用多少个英文候选词去查后端 */
    private static final int MAX_ENGLISH_CANDIDATES = 3;

    private final RemoteModRepository backed;

    public LocalizedRemoteModRepository(RemoteModRepository backed) {
        this.backed = backed;
    }

    /**
     * 按资源类型决定是否包装：只有存在中文名字典的类型（模组 / 整合包）才需要装饰，
     * 其余类型（资源包、光影包、世界、数据包）原样返回，避免无谓的一层转发。
     */
    public static RemoteModRepository wrap(RemoteModRepository repo) {
        if (repo == null) return null;
        return ModTranslations.getTranslationsByAddonType(repo.getType()).isEmpty()
                ? repo
                : new LocalizedRemoteModRepository(repo);
    }

    /** 该资源类型是否支持中文搜索（供界面决定提示文案） */
    public static boolean supportsChinese(RemoteModRepository.Type type) {
        return !ModTranslations.getTranslationsByAddonType(type).isEmpty();
    }

    /**
     * 反查某个远程项目对应的字典条目（供列表/详情页显示中文标题）。
     *
     * <p>查找顺序：页面 URL 末段（CurseForge 的数字 ID 不是 slug，真实 slug 只在 URL 里）
     * → {@code RemoteMod.getSlug()}（Modrinth 的 slug）→ 归一化标题。
     *
     * @param mod           远程项目
     * @param preferredType 界面已知的资源类型（可为 null）；先查该类型对应的字典，
     *                      未命中再回退查另一本字典（例如整合包页面里点了模组卡片）
     * @return 字典条目；两本字典都未收录时返回 null
     */
    public static ModTranslations.Mod findTranslation(RemoteMod mod, RemoteModRepository.Type preferredType) {
        if (mod == null) return null;

        String slugFromUrl = TextUtils.lastPathSegment(mod.getPageUrl());
        for (ModTranslations translations : dictionariesFor(preferredType)) {
            if (translations.isEmpty()) continue;

            // ① 用 slug（优先取页面 URL 末段）反查
            if (TextUtils.isNotBlank(slugFromUrl)) {
                ModTranslations.Mod found = translations.findForRemoteMod(slugFromUrl, mod.getTitle());
                if (found != null) return found;
            }
            // ② 退回用 RemoteMod.slug（Modrinth 的 slug / CurseForge 的数字 ID）
            ModTranslations.Mod found = translations.findForRemoteMod(mod.getSlug(), mod.getTitle());
            if (found != null) return found;
        }
        return null;
    }

    /** 反查字典条目（不知道资源类型时的简化入口，按「模组 → 整合包」顺序尝试） */
    public static ModTranslations.Mod findTranslation(RemoteMod mod) {
        return findTranslation(mod, null);
    }

    /** 展示用的中文标题：有中文名返回中文展示名，否则返回原始标题 */
    public static String localizedTitle(RemoteMod mod, RemoteModRepository.Type preferredType) {
        ModTranslations.Mod translation = findTranslation(mod, preferredType);
        if (translation != null && translation.hasChineseName()) {
            return translation.getDisplayName();
        }
        return mod.getTitle();
    }

    /** 展示用的中文标题（不知道资源类型时的简化入口） */
    public static String localizedTitle(RemoteMod mod) {
        return localizedTitle(mod, null);
    }

    /**
     * 候选字典顺序：优先界面已知类型对应的字典，再回退另一本。
     * 两本字典都是懒加载的，回退查找只在首选字典未命中时才付出代价。
     */
    private static List<ModTranslations> dictionariesFor(RemoteModRepository.Type preferredType) {
        if (preferredType == RemoteModRepository.Type.MODPACK) {
            return List.of(ModTranslations.MODPACK, ModTranslations.MOD);
        }
        return List.of(ModTranslations.MOD, ModTranslations.MODPACK);
    }

    @Override
    public Type getType() {
        return backed.getType();
    }

    // ==================== 搜索（中文翻译 + 结果重排） ====================

    @Override
    public SearchResult search(DownloadProvider dp, String gameVersion, Category category,
                               int pageOffset, int pageSize, String searchFilter,
                               SortType sortType, SortOrder sortOrder) throws IOException {
        return search(dp, new ModSearchQuery()
                .setGameVersion(gameVersion)
                .setPageOffset(pageOffset)
                .setPageSize(pageSize)
                .setQuery(searchFilter)
                .setSortType(sortType)
                .setSortOrder(sortOrder));
    }

    /** 详情页数据不带中文翻译语义，直接透传给被装饰的仓库 */
    @Override
    public RemoteModDetail getProjectDetail(DownloadProvider dp, String id) throws IOException {
        return backed.getProjectDetail(dp, id);
    }

    /** 分类列表同样透传（中文显示名由界面层的 {@link ModCategories} 负责） */
    @Override
    public List<Category> getCategoriesForType() throws IOException {
        return backed.getCategoriesForType();
    }

    @Override
    public SearchResult search(DownloadProvider dp, ModSearchQuery query) throws IOException {
        if (query == null) query = new ModSearchQuery();
        String filter = query.getQuery();
        ModTranslations translations = ModTranslations.getTranslationsByAddonType(getType());

        // ① 无字典 / 非中文查询：直接透传（加载器、分类等筛选条件原样带给后端）
        if (translations.isEmpty() || !TextUtils.containsChinese(filter) || query.getPageSize() <= 0) {
            return backed.search(dp, query);
        }

        // ② 中文 → 英文候选检索词（去重后最多 3 个）
        Set<String> candidates = new LinkedHashSet<>();
        int taken = 0;
        for (ModTranslations.Mod mod : translations.searchMod(filter)) {
            String english = joinTokens(TextUtils.isNotBlank(mod.getSubname()) ? mod.getSubname() : mod.getName());
            if (TextUtils.isNotBlank(english)) candidates.add(english);
            if (++taken >= MAX_ENGLISH_CANDIDATES) break;
        }
        if (candidates.isEmpty()) {
            // 字典里没有对应中文名：退回原样查询，至少不比翻译前差
            return backed.search(dp, query);
        }

        // ③ 依次尝试候选词，取第一个有结果的那次查询
        SearchResult lastResult = null;
        List<RemoteMod> raw = List.of();
        for (String candidate : candidates) {
            SearchResult result = backed.search(dp, query.copy().setQuery(candidate));
            List<RemoteMod> items = result.getResults().collect(Collectors.toList());
            lastResult = result;
            raw = items;
            if (!items.isEmpty()) break;
        }
        if (lastResult == null) {
            return backed.search(dp, query);
        }
        if (raw.isEmpty()) {
            return new SearchResult(Stream.empty(), lastResult.getTotalPages());
        }

        // ④ 分两区：能显示中文名的在前（按相似度排序），其余保持后端顺序在后
        List<Scored> chineseHits = new ArrayList<>();
        List<RemoteMod> others = new ArrayList<>();
        TextUtils.LevCalculator levCalculator = new TextUtils.LevCalculator();
        for (RemoteMod item : raw) {
            ModTranslations.Mod translation = findTranslation(item);
            if (translation != null && translation.hasChineseName()) {
                chineseHits.add(new Scored(item, similarity(filter, translation.getName(), levCalculator)));
            } else {
                others.add(item);
            }
        }
        chineseHits.sort(Comparator.comparingInt(Scored::score));

        List<RemoteMod> merged = new ArrayList<>(raw.size());
        for (Scored hit : chineseHits) merged.add(hit.mod());
        merged.addAll(others);

        return new SearchResult(merged.stream(), lastResult.getTotalPages());
    }

    /** 去掉标点后把名称拼成检索词：{@code "Just Enough Items (JEI)"} → {@code "Just Enough Items JEI"} */
    private static String joinTokens(String name) {
        if (TextUtils.isBlank(name)) return "";
        return String.join(" ", TextUtils.tokenize(name));
    }

    /**
     * 相似度打分（越小越靠前）：编辑距离为基准，查询串里每个出现在中文名中的字符再减
     * {@value #CONTAIN_CHINESE_WEIGHT} 分，让「包含用户输入字符」的条目排到前面。
     */
    private static int similarity(String query, String chineseName, TextUtils.LevCalculator calculator) {
        if (query.isEmpty() || chineseName.isEmpty()) {
            return Math.max(query.length(), chineseName.length());
        }
        int distance = calculator.calc(query, chineseName);
        for (int i = 0; i < query.length(); i++) {
            if (chineseName.indexOf(query.charAt(i)) >= 0) distance -= CONTAIN_CHINESE_WEIGHT;
        }
        return distance;
    }

    /** 内部：条目 + 相似度分数 */
    private record Scored(RemoteMod mod, int score) {
    }

    // ==================== 其余方法一律委托 ====================

    @Override
    public Optional<RemoteMod.Version> getRemoteVersionByLocalFile(LocalModFile localModFile, Path file) throws IOException {
        return backed.getRemoteVersionByLocalFile(localModFile, file);
    }

    @Override
    public RemoteMod getModById(DownloadProvider dp, String id) throws IOException {
        return backed.getModById(dp, id);
    }

    @Override
    public RemoteMod.File getModFile(String modId, String fileId) throws IOException {
        return backed.getModFile(modId, fileId);
    }

    @Override
    public Stream<RemoteMod.Version> getRemoteVersionsById(DownloadProvider dp, String id) throws IOException {
        return backed.getRemoteVersionsById(dp, id);
    }

    @Override
    public Stream<Category> getCategories() throws IOException {
        return backed.getCategories();
    }
}

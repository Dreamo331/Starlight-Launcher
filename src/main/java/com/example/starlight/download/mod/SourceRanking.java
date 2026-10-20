package com.example.starlight.download.mod;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选源评分排序（2026-09-30 下载等待池方案 §2.4）。
 *
 * <p>选择候选源时不再仅按静态顺序，而是基于已有信息动态排序（<b>不新增任何网络请求</b>）：
 * <ol>
 *   <li>{@link SourceHealth#getSlowCount}：本批中「疑似慢源」软计数越少越优（进入等待池次数越多权重越低）；</li>
 *   <li>{@link SourceHealth#getLastSuccessCostMs}：最近一次成功下载耗时越短越优，
 *       从未成功过的源排在已测速源之后（优先尝试已被证明快的源）；</li>
 *   <li>{@link SourceHealth#getLastSuccessAt}：同为未知/等耗时的情况下，最近成功过的源更优。</li>
 * </ol>
 * 被拉黑的源仍由调用方的 {@code isBlocked} 检查跳过，本类不重复处理；
 * 每轮重试进入 {@code downloadSingleAsset} 时基于最新数据重新排序，天然实现「重试波次优先尝试最优源」。
 */
public final class SourceRanking {

    private SourceRanking() {
    }

    /** 稳定排序（相同评分保持原候选顺序），返回新列表，不改动入参 */
    public static List<URI> sortCandidates(List<URI> candidates) {
        List<URI> sorted = new ArrayList<>(candidates);
        SourceHealth health = SourceHealth.getInstance();
        sorted.sort((a, b) -> compare(health, a.toString(), b.toString()));
        return sorted;
    }

    private static int compare(SourceHealth h, String a, String b) {
        // 1) slowCount 越少越优
        int sa = h.getSlowCount(a);
        int sb = h.getSlowCount(b);
        if (sa != sb) {
            return Integer.compare(sa, sb);
        }
        // 2) 最近成功耗时越短越优；从未成功（-1）的源排在已测速源之后
        long ca = h.getLastSuccessCostMs(a);
        long cb = h.getLastSuccessCostMs(b);
        boolean ua = ca < 0L;
        boolean ub = cb < 0L;
        if (ua != ub) {
            return ua ? 1 : -1;
        }
        if (!ua && ca != cb) {
            return Long.compare(ca, cb);
        }
        // 3) 最近成功时间越新越优（同未知时保持稳定顺序）
        long ta = h.getLastSuccessAt(a);
        long tb = h.getLastSuccessAt(b);
        boolean na = ta < 0L;
        boolean nb = tb < 0L;
        if (na != nb) {
            return na ? 1 : -1;
        }
        if (!na && ta != tb) {
            return Long.compare(tb, ta);
        }
        return 0;
    }
}

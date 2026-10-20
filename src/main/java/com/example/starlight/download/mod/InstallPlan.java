package com.example.starlight.download.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务链 DAG（对标 PCL LoaderTask / LoaderCombo）。
 *
 * <p>把「分析缺失 → 下载 → 校验 → 后处理」显式建模成有序阶段列表，每阶段带 ProgressWeight。
 * 对外只提供加权聚合进度，不碰 UI——UI 层订阅 {@link #overallProgress()} 自行展示。
 *
 * <p>进度聚合公式：{@code planProgress = Σ(阶段进度 × 权重) / Σ权重}。
 * 下载阶段权重占 60~80%（PCL 实例：支持库分析 1 / 下载 15；资源下载 25 量级）。
 */
public final class InstallPlan {

    private final List<PlanStage> stages = Collections.synchronizedList(new ArrayList<>());

    /** 追加一个阶段，返回该阶段句柄（上层据此更新进度） */
    public PlanStage addStage(PlanStage.Type type, double weight) {
        PlanStage stage = new PlanStage(type, weight);
        stages.add(stage);
        return stage;
    }

    /** 全部阶段（只读视图） */
    public List<PlanStage> getStages() {
        return Collections.unmodifiableList(new ArrayList<>(stages));
    }

    /**
     * 加权聚合进度：Σ(阶段进度 × 权重) / Σ权重。
     * 无阶段时返回 0。
     */
    public double overallProgress() {
        double weighted = 0.0;
        double totalWeight = 0.0;
        synchronized (stages) {
            for (PlanStage s : stages) {
                weighted += s.getProgress() * s.getWeight();
                totalWeight += s.getWeight();
            }
        }
        if (totalWeight <= 0.0) return 0.0;
        return Math.max(0.0, Math.min(1.0, weighted / totalWeight));
    }
}

package com.example.starlight.download.mod;

/**
 * 任务链 DAG 的单阶段（对标 PCL LoaderTask 的 ProgressWeight）。
 *
 * <p>一次「安装某个 Mod / 版本」被组织成若干阶段：
 * 分析缺失 → 下载 → 校验 → 后处理，每个阶段带一个权重 weight；
 * UI 进度 = 各阶段「进度 × 权重」加权平均（见 {@link InstallPlan}）。
 *
 * <p>PCL 实例：支持库分析 weight≈1 / 下载 weight≈15；资源索引分析 0.5 / 索引下载 2 /
 * 列表生成 0.01 / 资源下载 25。<b>下载阶段权重占 60~80%</b>，分析与后处理各占小头。
 */
public final class PlanStage {

    /** 阶段类型：分析缺失 / 下载 / 校验 / 后处理 */
    public enum Type {
        ANALYZE, DOWNLOAD, VERIFY, POST
    }

    /** 阶段状态 */
    public enum State {
        PENDING, RUNNING, DONE, FAILED
    }

    private final Type type;
    private final double weight;
    private volatile double progress; // 0.0 ~ 1.0
    private volatile State state = State.PENDING;

    public PlanStage(Type type, double weight) {
        this.type = type;
        this.weight = Math.max(0, weight);
    }

    public Type getType() {
        return type;
    }

    public double getWeight() {
        return weight;
    }

    /** 阶段进度 0.0~1.0 */
    public double getProgress() {
        return progress;
    }

    /** 设置阶段进度（自动夹到 [0,1]）；1.0 自动置 DONE */
    public void setProgress(double progress) {
        this.progress = Math.max(0.0, Math.min(1.0, progress));
        if (this.progress >= 1.0 && state == State.RUNNING) {
            state = State.DONE;
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
        if (state == State.DONE) {
            this.progress = 1.0;
        } else if (state == State.FAILED || state == State.PENDING) {
            this.progress = 0.0;
        }
    }
}

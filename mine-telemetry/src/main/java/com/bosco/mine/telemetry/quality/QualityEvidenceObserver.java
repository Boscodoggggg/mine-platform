package com.bosco.mine.telemetry.quality;

/**
 * 观察数据库已经提交的质量规则命中，禁止实现影响遥测持久化结果。
 */
@FunctionalInterface
public interface QualityEvidenceObserver {

    /**
     * 观察一条已经提交且幂等去重的质量证据。
     *
     * @param evidence 已提交质量证据
     */
    void observe(QualityEvidence evidence);
}

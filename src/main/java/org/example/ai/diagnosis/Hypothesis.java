package org.example.ai.diagnosis;

import java.util.List;
import java.util.Objects;

/**
 * 一个待确认的根因判断及其置信度和支撑证据。
 *
 * @param cause 根因假设
 * @param confidence 模型给出的数值置信度
 * @param evidence 支撑该假设的事实陈述列表
 */
public record Hypothesis(String cause, double confidence, List<String> evidence) {

    /**
     * 创建假设并复制证据列表，避免调用方在构造后修改报告内容。
     *
     * @param cause 根因假设
     * @param confidence 模型给出的数值置信度
     * @param evidence 支撑该假设的事实陈述列表
     */
    public Hypothesis {
        Objects.requireNonNull(cause, "根因假设不能为空");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "假设证据列表不能为空"));
    }
}

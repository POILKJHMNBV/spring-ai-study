package org.example.ai.diagnosis;

import java.util.Objects;

/**
 * 一条可追溯的诊断事实；来源用于说明该陈述来自哪个工具或知识条目。
 *
 * @param statement 已观察到的事实陈述
 * @param source 支持该事实的工具名称或当前上下文中的知识来源
 */
public record Fact(String statement, String source) {

    /**
     * 创建不可缺少陈述与来源的事实记录。
     *
     * @param statement 已观察到的事实陈述
     * @param source 支持该事实的工具名称或当前上下文中的知识来源
     */
    public Fact {
        Objects.requireNonNull(statement, "事实陈述不能为空");
        Objects.requireNonNull(source, "事实来源不能为空");
    }
}

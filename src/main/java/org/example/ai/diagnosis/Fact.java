package org.example.ai.diagnosis;

import java.util.Objects;

/**
 * 一条可追溯的诊断事实；Day15 将陈述绑定到本次成功工具快照或本次知识摘录。
 * 模型可使用本次目录中的精确 TOOL-n 编号；校验通过后，工具陈述展开为完整 JSON
 * 对象或数组文本，保留字段、实体和值；知识陈述以“知识引用：”
 * 开头并逐字摘录。结构仍为两个字符串，业务真实性由 DiagnosisReportValidator 校验。
 *
 * @param statement 模型输入中的精确工具编号、完整工具 JSON，或带“知识引用：”前缀的逐字知识摘录
 * @param source 支持该事实的工具名称或当前上下文中的知识来源
 */
public record Fact(String statement, String source) {

    /**
     * 创建不可缺少陈述与来源的事实记录。
     *
     * @param statement 精确工具编号、完整工具 JSON 文本或显式标记的逐字知识摘录
     * @param source 支持该事实的工具名称或当前上下文中的知识来源
     */
    public Fact {
        Objects.requireNonNull(statement, "事实陈述不能为空");
        Objects.requireNonNull(source, "事实来源不能为空");
    }
}

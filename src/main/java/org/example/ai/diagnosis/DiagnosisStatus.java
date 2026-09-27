package org.example.ai.diagnosis;

/**
 * 结构化诊断报告的业务状态，名称同时作为 JSON 中稳定且区分大小写的枚举值。
 */
public enum DiagnosisStatus {
    /** 现有证据足以形成一个或多个根因判断。 */
    DIAGNOSED,
    /** 当前证据不足，应向用户说明缺口并给出补充信息要求。 */
    INSUFFICIENT_EVIDENCE,
    /** 模型报告本身表示诊断失败，不能作为成功结果保存到会话记忆。 */
    FAILED
}

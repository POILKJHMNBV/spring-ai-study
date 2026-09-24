package org.example.ai.tool.dto;

/**
 * 错误日志摘要：包含日志级别和消息内容。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code level} — 日志级别，例如 "ERROR"、"WARN"</li>
 *   <li>{@code message} — 日志消息内容，可能包含异常堆栈摘要</li>
 * </ul>
 * </p>
 *
 * <p>用途：提供故障线索，但不能单独确认 DB/RPC 根因，必须结合依赖指标交叉验证。</p>
 */
public record ErrorLog(
        String level,
        String message
) {
}

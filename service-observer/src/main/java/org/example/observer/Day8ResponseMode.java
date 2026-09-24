package org.example.observer;

/**
 * Day8 HTTP 响应模式枚举：控制服务指标接口的返回格式，用于测试 HTTP Tool 的容错能力。
 *
 * <p>场景说明：
 * <ul>
 *   <li>{@link #NORMAL} — 正常 HTTP 200，返回完整 JSON</li>
 *   <li>{@link #MISSING_FIELD} — 返回 JSON，但缺少必须字段，验证 Client 能识别不完整响应</li>
 *   <li>{@link #LARGE_PAYLOAD} — 返回超大 Payload，测试截断机制</li>
 *   <li>{@link #WRONG_CONTENT_TYPE} — 返回非 application/json Content-Type，验证 Content-Type 校验</li>
 * </ul>
 * </p>
 */
public enum Day8ResponseMode {

    /** 正常 HTTP 200：返回完整 JSON 响应。 */
    NORMAL,

    /** 缺少必须字段：返回 JSON，但故意删除 memoryPercent，验证 Client 能识别不完整响应。 */
    MISSING_FIELD,

    /** 超大 Payload：日志接口返回超大内容，测试 {@link org.example.ai.harness.GuardedToolCallback} 的截断机制。 */
    LARGE_PAYLOAD,

    /** 错误 Content-Type：返回非 application/json Content-Type，验证 Content-Type 校验。 */
    WRONG_CONTENT_TYPE
}
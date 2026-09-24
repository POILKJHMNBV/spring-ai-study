package org.example.ai.harness;

/**
 * 工具失败类型枚举：稳定的错误分类，供追踪记录与模型共同使用。
 *
 * <p>每种类型对应不同的恢复策略：
 * <ul>
 *   <li>{@link #TIMEOUT}、{@link #CONNECTION_FAILURE}、{@link #RATE_LIMITED}、{@link #SERVER_ERROR}（500/503）：可重试</li>
 *   <li>{@link #AUTH_FAILURE}、{@link #INVALID_RESPONSE}、{@link #POLICY_REJECTED}、{@link #UNKNOWN}：不可重试</li>
 * </ul>
 * </p>
 */
public enum ToolErrorType {
    /** 工具调用超时。 */
    TIMEOUT,
    /** 连接失败：网络不可达、DNS 解析失败等。 */
    CONNECTION_FAILURE,
    /** 认证失败：HTTP 401/403。 */
    AUTH_FAILURE,
    /** 限流：HTTP 429，可能需要遵守 Retry-After 头。 */
    RATE_LIMITED,
    /** 服务器错误：HTTP 5xx，其中 500/503 可重试。 */
    SERVER_ERROR,
    /** 无效响应：工具返回格式错误或不符合预期契约。 */
    INVALID_RESPONSE,
    /** 策略拒绝：工具调用被 {@link ExecutionPolicy} 拒绝。 */
    POLICY_REJECTED,
    /** 未知错误：无法分类的异常。 */
    UNKNOWN
}

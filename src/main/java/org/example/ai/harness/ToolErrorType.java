package org.example.ai.harness;

/** Day9：稳定的工具失败分类，供 Trace 与模型共同使用。 */
public enum ToolErrorType {
    TIMEOUT,
    CONNECTION_FAILURE,
    AUTH_FAILURE,
    RATE_LIMITED,
    SERVER_ERROR,
    INVALID_RESPONSE,
    POLICY_REJECTED,
    UNKNOWN
}

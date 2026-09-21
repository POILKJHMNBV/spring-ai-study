package org.example.observer;

/**
 * Day8 必做 HTTP 场景。
 */
public enum Day8ResponseMode {

    /**
     * 正常 HTTP 200。
     */
    NORMAL,

    /**
     * 返回 JSON，但缺少必须字段。
     */
    MISSING_FIELD,

    /**
     * 日志接口返回超大 Payload。
     */
    LARGE_PAYLOAD,

    /**
     * 返回非 application/json Content-Type。
     */
    WRONG_CONTENT_TYPE
}
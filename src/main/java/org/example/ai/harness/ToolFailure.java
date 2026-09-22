package org.example.ai.harness;

import org.example.ai.tool.client.InvalidToolResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 错误分类与恢复决策。仅 Harness 重试，HTTP Adapter 不再叠加重试。
 */
public record ToolFailure(ToolErrorType type, boolean retryable, Duration delay) {
    /**
     * 防止远端 Retry-After 将一次请求无限挂起；超限时放弃重试而非提前请求。
     */
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(3);

    public static ToolFailure classify(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ToolFailure fallback = of(ToolErrorType.UNKNOWN, false);
        // Spring AI 反射调用与 Future 会包装异常，沿 cause 链查找实际错误。
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ExecutionPolicy.PolicyViolationException) {
                return of(ToolErrorType.POLICY_REJECTED, false);
            }

            if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return of(ToolErrorType.TIMEOUT, true);
            }

            if (cause instanceof RestClientResponseException http) {
                int status = http.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return of(ToolErrorType.AUTH_FAILURE, false);
                }
                if (status == 429) {
                    String header = http.getResponseHeaders() == null ? null
                            : http.getResponseHeaders().getFirst("Retry-After");
                    Duration wait = retryAfter(header, Instant.now());
                    return new ToolFailure(ToolErrorType.RATE_LIMITED, wait != null,
                            wait == null ? Duration.ZERO : wait);
                }
                if (status >= 500) {
                    return of(ToolErrorType.SERVER_ERROR, status == 500 || status == 503);
                }
                // 400 等请求错误不属于可恢复故障，也不伪装成返回契约异常。
                return of(ToolErrorType.UNKNOWN, false);
            }

            if (cause instanceof InvalidToolResponseException) {
                return of(ToolErrorType.INVALID_RESPONSE, false);
            }

            if (cause instanceof ResourceAccessException || cause instanceof ConnectException) {
                fallback = of(ToolErrorType.CONNECTION_FAILURE, true);
            } else if (cause instanceof RestClientException && fallback.type() == ToolErrorType.UNKNOWN) {
                fallback = of(ToolErrorType.INVALID_RESPONSE, false);
            }
        }
        return fallback;
    }

    /**
     * RFC 9110：支持非负秒数与 HTTP-date；缺失、非法或超过预算均不自动重试。
     */
    static Duration retryAfter(String value, Instant now) {
        if (value == null) {
            return null;
        }
        try {
            Duration duration = value.trim().matches("[0-9]+")
                    ? Duration.ofSeconds(Long.parseLong(value.trim()))
                    : Duration.between(now, ZonedDateTime.parse(value.trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }
            return duration.compareTo(MAX_RETRY_AFTER) <= 0 ? duration : null;
        } catch (RuntimeException invalidHeader) {
            return null;
        }
    }

    private static ToolFailure of(ToolErrorType type, boolean retryable) {
        return new ToolFailure(type, retryable, Duration.ZERO);
    }

    /**
     * 不向模型或日志泄露异常原文、响应体、URL 或凭据。
     */
    public String observation() {
        return """
                {"status":"ERROR","errorType":"%s","dataAvailable":false,
                "message":"工具数据不可用，不能视为零值、健康或无异常；请明确标记待验证。"}
                """.formatted(type);
    }
}

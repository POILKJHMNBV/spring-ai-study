package org.example.ai.tool.client.dto;

/**
 * HTTP 日志查询结果。
 */
public record ErrorLogHttpResponse(
        String level,
        String message
) {
}
package org.example.ai.security;

import java.util.regex.Pattern;

/**
 * Day5 会话安全工具。
 *
 * <p>
 * 当前学习项目暂未接入完整认证系统，因此这里负责：
 * 1. conversationId 基本格式校验；
 * 2. 常见敏感凭据脱敏；
 * 3. 防止敏感内容被直接写入 Memory 或 Trace。
 * </p>
 */
public final class ConversationSecurity {

    /**
     * conversationId 只允许简单、安全、可日志化的字符。
     */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * Bearer Token，例如：
     * Authorization: Bearer eyJxxx...
     */
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    /**
     * 常见 key=value / key:value 形式的敏感字段。
     */
    private static final Pattern SECRET_KEY_VALUE_PATTERN =
            Pattern.compile(
                    "(?i)(api[-_]?key|token|password|secret)"
                            + "(\\s*[=:]\\s*)"
                            + "[\"']?([^\\s,\"'};]+)"
            );

    private ConversationSecurity() {
    }

    /**
     * 校验 conversationId。
     *
     * @param conversationId 客户端传入的会话标识
     * @return 校验通过的原始 conversationId
     */
    public static String requireValidConversationId(String conversationId) {
        if (conversationId == null || !CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new IllegalArgumentException(
                    "conversationId 仅允许 1~64 位字母、数字、点、下划线、冒号或短横线"
            );
        }
        return conversationId;
    }

    /**
     * 对可能进入日志或 Memory 的文本做基础敏感信息脱敏。
     *
     * <p>
     * 这不是完整的企业级 DLP，仅用于 Day5 建立
     * “敏感内容不能直接沉淀到 Memory/日志”的基本安全意识。
     * </p>
     */
    public static String sanitizeSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = BEARER_PATTERN.matcher(text).replaceAll("$1***");
        return SECRET_KEY_VALUE_PATTERN
                .matcher(sanitized)
                .replaceAll("$1$2***");
    }
}

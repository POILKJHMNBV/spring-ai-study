package org.example.ai.security;

import java.util.regex.Pattern;

/**
 * 会话安全工具：负责 conversationId 格式校验和敏感信息脱敏。
 *
 * <p>设计背景：当前学习项目暂未接入完整认证系统，因此这里负责：
 * <ul>
 *   <li>conversationId 基本格式校验：防止日志注入、路径遍历等攻击</li>
 *   <li>常见敏感凭据脱敏：Bearer Token、API Key、密码等</li>
 *   <li>防止敏感内容被直接写入 Memory 或 Trace，避免持久化泄露</li>
 * </ul>
 * </p>
 *
 * <p>注意：这不是完整的企业级 DLP（数据防泄漏）方案，仅用于建立
 * “敏感内容不能直接沉淀到 Memory/日志”的基本安全意识。</p>
 */
public final class ConversationSecurity {

    /**
     * conversationId 白名单正则：只允许简单、安全、可日志化的字符。
     *
     * <p>允许的字符：字母、数字、点、下划线、冒号、短横线。
     * 长度限制 1～64 位，防止过长 ID 导致日志膨胀或缓冲区溢出。</p>
     */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * Bearer Token 正则：匹配 HTTP Authorization 头中的 Bearer 令牌。
     *
     * <p>示例：{@code Authorization: Bearer eyJxxx...} → {@code Authorization: Bearer ***}</p>
     */
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    /**
     * 敏感键值对正则：匹配常见敏感字段的 key=value 或 key:value 形式。
     *
     * <p>匹配的敏感字段：api_key、apikey、token、password、secret（不区分大小写）。
     * 示例：{@code api_key=abc123} → {@code api_key=***}</p>
     */
    private static final Pattern SECRET_KEY_VALUE_PATTERN =
            Pattern.compile(
                    "(?i)(api[-_]?key|token|password|secret)"
                            + "(\\s*[=:]\\s*)"
                            + "[\"']?([^\\s,\"'};]+)"
            );

    /** 工具类禁止实例化。 */
    private ConversationSecurity() {
    }

    /**
     * 校验 conversationId 格式，防止日志注入或路径遍历。
     *
     * <p>校验规则：只允许 1～64 位字母、数字、点、下划线、冒号或短横线。
     * 不符合规则的 ID 会直接抛出异常，阻止后续处理。</p>
     *
     * @param conversationId 客户端传入的会话标识
     * @return 校验通过的原始 conversationId，便于调用方链式使用
     * @throws IllegalArgumentException 格式不合法
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
     * <p>脱敏规则：
     * <ul>
     *   <li>Bearer Token：{@code Bearer xxx} → {@code Bearer ***}</li>
     *   <li>敏感键值对：{@code api_key=xxx} → {@code api_key=***}</li>
     * </ul>
     * </p>
     *
     * <p>注意：这不是完整的企业级 DLP，仅用于建立基本安全意识。
     * 生产环境应接入专业的数据防泄漏系统。</p>
     *
     * @param text 待脱敏文本
     * @return 脱敏后的文本，null 或空白返回空字符串
     */
    public static String sanitizeSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 先脱敏 Bearer Token，再脱敏敏感键值对。
        String sanitized = BEARER_PATTERN.matcher(text).replaceAll("$1***");
        return SECRET_KEY_VALUE_PATTERN
                .matcher(sanitized)
                .replaceAll("$1$2***");
    }
}

package org.example.ai.security;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 仅解析完整 JSON；拒绝尾随输入，避免处理一部分后静默丢失剩余原文。 */
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();

    /** JSON 字段名必须完整匹配；字段值整体替换，包含数值和嵌套凭据对象。 */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(api[-_]?key|token|password|secret|authorization|client[-_]?secret|"
                    + "access[-_]?token|refresh[-_]?token|credential)");

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

    /** JSON 双引号键值对凭据模式，支持空格、换行和反斜线转义的字符串内容。 */
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\\\"(?:api[-_]?key|token|password|secret|authorization|client[-_]?secret|"
                    + "access[-_]?token|refresh[-_]?token|credential)\\\"\\s*:\\s*\\\")"
                    + "((?:\\\\.|[^\\\"\\\\])*+)(\\\")"
    );

    /** properties/YAML 等形式中由双引号包围的凭据；拥有界定的非递归字符串匹配。 */
    private static final Pattern QUOTED_DOUBLE_SECRET_PATTERN = Pattern.compile(
            "(?i)(api[-_]?key|token|password|secret|authorization|client[-_]?secret|"
                    + "access[-_]?token|refresh[-_]?token|credential)(\\s*[=:]\\s*)(\\\")"
                    + "((?:\\\\.|[^\\\"\\\\])*+)\\3"
    );

    /** properties/YAML 等形式中由单引号包围的凭据；拥有界定的非递归字符串匹配。 */
    private static final Pattern QUOTED_SINGLE_SECRET_PATTERN = Pattern.compile(
            "(?i)(api[-_]?key|token|password|secret|authorization|client[-_]?secret|"
                    + "access[-_]?token|refresh[-_]?token|credential)(\\s*[=:]\\s*)(')"
                    + "((?:\\\\.|[^'\\\\])*+)\\3"
    );

    /**
     * 敏感键值对正则：匹配常见敏感字段的 key=value 或 key:value 形式。
     *
     * <p>匹配的敏感字段：API Key、Token、密码、Secret、Authorization、Credential（不区分大小写）。
     * 示例：{@code api_key=abc123} → {@code api_key=***}</p>
     */
    private static final Pattern SECRET_KEY_VALUE_PATTERN =
            Pattern.compile(
                    "(?i)(api[-_]?key|token|password|secret|authorization|client[-_]?secret|"
                            + "access[-_]?token|refresh[-_]?token|credential)"
                            + "(\\s*[=:]\\s*)"
                            + "([^\\s,\"'};]+)"
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
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                // 先解码字符串，再处理其中的凭据：单纯正则无法正确处理 JSON 内的转义引号。
                JsonNode root = JSON_MAPPER.readTree(text);
                return JSON_MAPPER.writeValueAsString(sanitizeJsonValue(root, 0));
            } catch (RuntimeException ignored) {
                // 畸形 JSON 仍按不可信普通文本脱敏；不记录可能含原始输入的解析异常。
            }
        }
        return sanitizePlainText(text);
    }

    /**
     * 递归生成可序列化的脱敏副本，保留非敏感字段的类型与顺序。
     *
     * @param node 已由 Jackson 解码的当前 JSON 节点
     * @param depth 当前容器深度；超过 64 层时替换整段，限制应用递归栈消耗
     * @return Map、List、普通文本或原始非文本节点组成的安全副本
     */
    private static Object sanitizeJsonValue(JsonNode node, int depth) {
        if (depth > 64) {
            return "[REDACTED_DEPTH_LIMIT]";
        }
        if (node.isObject()) {
            Map<String, Object> safe = new LinkedHashMap<>();
            node.properties().forEach(entry -> safe.put(entry.getKey(),
                    SECRET_FIELD.matcher(entry.getKey()).matches() ? "***"
                            : sanitizeJsonValue(entry.getValue(), depth + 1)));
            return safe;
        }
        if (node.isArray()) {
            List<Object> safe = new ArrayList<>();
            node.forEach(value -> safe.add(sanitizeJsonValue(value, depth + 1)));
            return safe;
        }
        return node.isString() ? sanitizePlainText(node.stringValue()) : node;
    }

    /**
     * 对已解码的普通文本处理 Bearer 和具名凭据，保留外围引号及普通指标。
     *
     * @param text 非空、可能带配置文本或 JSON 片段的字符串
     * @return 已按支持格式移除凭据值的文本
     */
    private static String sanitizePlainText(String text) {
        // 先脱敏 Bearer、JSON 与配置文本中的引号包裹凭据，再处理无引号键值。
        String sanitized = BEARER_PATTERN.matcher(text).replaceAll("$1***");
        sanitized = JSON_SECRET_PATTERN.matcher(sanitized).replaceAll("$1***$3");
        sanitized = QUOTED_DOUBLE_SECRET_PATTERN.matcher(sanitized).replaceAll("$1$2$3***$3");
        sanitized = QUOTED_SINGLE_SECRET_PATTERN.matcher(sanitized).replaceAll("$1$2$3***$3");
        return SECRET_KEY_VALUE_PATTERN.matcher(sanitized).replaceAll("$1$2***");
    }
}

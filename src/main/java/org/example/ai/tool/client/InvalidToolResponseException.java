package org.example.ai.tool.client;

/**
 * 工具响应异常：远端契约不完整或不匹配时抛出。
 *
 * <p>设计原则：不得将无效响应转换成零值或空数据，必须明确标记为不可用。
 * 这样可以防止模型将缺失数据误认为零值、健康或无异常。</p>
 *
 * <p>典型场景：
 * <ul>
 *   <li>HTTP 响应状态码非 2xx</li>
 *   <li>响应体缺少必要字段</li>
 *   <li>响应数据格式不符合预期</li>
 * </ul>
 * </p>
 */
public class InvalidToolResponseException extends IllegalStateException {
    /**
     * 创建工具响应异常。
     *
     * @param message 错误描述，说明响应哪里不符合契约
     */
    public InvalidToolResponseException(String message) {
        super(message);
    }
}

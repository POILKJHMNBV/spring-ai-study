package org.example.ai.config;

import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client 配置：定义 MCP 工具名称前缀生成器。
 *
 * <p>设计背景：Day7 引入 MCP 协议后，Spring AI MCP Client 默认会为工具名添加服务器名前缀，
 * 例如 {@code mcp-server-getKafkaStatus}。但这会破坏工具名的稳定性，影响安全策略白名单和评测公平性。</p>
 *
 * <p>配置策略：
 * <ul>
 *   <li>当前只连接一个 MCP Server，且工具名保持 {@code getKafkaStatus} 不变</li>
 *   <li>因此关闭 MCP 工具名前缀，使用 {@link McpToolNamePrefixGenerator#noPrefix()}</li>
 *   <li>后续如果接入多个可能存在同名工具的 MCP Server，应重新启用默认的唯一名称策略</li>
 * </ul>
 * </p>
 */
@Configuration
public class McpClientConfig {

    /**
     * 创建 MCP 工具名称前缀生成器：禁用前缀，保持工具名稳定。
     *
     * @return 无前缀生成器
     */
    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }
}
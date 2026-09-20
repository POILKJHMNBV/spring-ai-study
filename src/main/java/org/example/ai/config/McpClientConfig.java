package org.example.ai.config;

import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day7 MCP Client 配置。
 */
@Configuration
public class McpClientConfig {

    /**
     * Day7 只连接一个 MCP Server，
     * 并明确要求 Tool 名保持 getKafkaStatus 不变。
     *
     * <p>
     * 因此关闭 MCP Tool name prefix。
     *
     * 后续如果接入多个可能存在同名 Tool 的 MCP Server，
     * 应重新启用默认的唯一名称策略。
     * </p>
     */
    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }
}
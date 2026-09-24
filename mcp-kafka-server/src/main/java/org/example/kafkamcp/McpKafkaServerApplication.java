package org.example.kafkamcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Kafka MCP Server 启动类：独立的 MCP 服务端进程。
 *
 * <p>设计背景：Day7 引入 MCP 协议后，getKafkaStatus 工具从主工程的本地方法迁移到独立 MCP Server。
 * 该进程与主工程 spring-ai-study Agent 进程完全独立，两者之间通过 MCP Streamable HTTP 通信。</p>
 *
 * <p>启动方式：
 * <ul>
 *   <li>单独启动该进程，默认监听 8081 端口</li>
 *   <li>主工程配置 MCP Client 连接到该 Server</li>
 * </ul>
 * </p>
 */
@SpringBootApplication
public class McpKafkaServerApplication {

    /**
     * MCP Server 入口：启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(McpKafkaServerApplication.class, args);
    }

}

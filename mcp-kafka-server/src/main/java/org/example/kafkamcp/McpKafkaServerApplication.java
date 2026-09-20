package org.example.kafkamcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Day7 Kafka MCP Server。
 *
 * <p>
 * 该进程与原 spring-ai-study Agent 进程完全独立。
 * 两者之间通过 MCP Streamable HTTP 通信。
 * </p>
 */
@SpringBootApplication
public class McpKafkaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpKafkaServerApplication.class, args);
    }

}

package org.example.ai.tool;

/**
 * Kafka 工具的接入方式枚举：决定 getKafkaStatus 工具来自本地还是远程 MCP Server。
 *
 * <p>配置项：{@code app.kafka-tool-mode}，默认 LOCAL。
 * 由 {@link AgentToolProvider} 读取并根据模式组装工具集合。</p>
 */
public enum KafkaToolMode {

    /**
     * 本地 Java 工具：沿用 Day2~Day6 的本地 {@code @Tool} 实现。
     * 适合开发调试和没有 MCP Server 的环境。
     */
    LOCAL,

    /**
     * 远程 MCP 工具：通过 MCP Client 调用独立 MCP Server。
     * Day7 引入，用于验证 MCP 协议集成和跨进程工具调用。
     */
    MCP
}
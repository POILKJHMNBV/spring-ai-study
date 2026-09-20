package org.example.ai.tool;

/**
 * Kafka Tool 的接入方式。
 *
 * <p>
 * LOCAL：
 * 沿用 Day2~Day6 的本地 @Tool。
 *
 * MCP：
 * 使用 Day7 新增的远程 MCP Tool。
 * </p>
 */
public enum KafkaToolMode {

    /**
     * 本地 Java Tool。
     */
    LOCAL,

    /**
     * 通过 MCP Client 调用独立 MCP Server。
     */
    MCP
}
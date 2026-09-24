package org.example.ai.tool;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Agent 工具集合提供器：根据配置模式返回当前 Agent 应注册的全部工具。
 *
 * <p>设计背景：Day7 引入 MCP 协议后，同一个工具（如 getKafkaStatus）可以来自本地 Java 方法或远程 MCP Server。
 * 本类负责屏蔽这种差异，让 AgentRunner 不需要关心工具的具体来源。</p>
 *
 * <p>两种模式：
 * <ul>
 *   <li>LOCAL：所有工具来自本地 {@link OpsTools}，沿用 Day2~Day6 行为</li>
 *   <li>MCP：getKafkaStatus 来自远程 MCP Server，其他工具仍来自本地</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>工具名必须保持稳定，否则会影响模型 Schema、安全策略白名单和评测公平性</li>
 *   <li>MCP 模式下必须移除本地同名工具，否则会产生两个同名工具</li>
 *   <li>使用 {@link ObjectProvider} 而非强依赖，LOCAL 模式下 MCP Client 未启用时仍能正常启动</li>
 * </ul>
 * </p>
 */
@Component
public class AgentToolProvider {

    /**
     * Day7 唯一迁移到 MCP 的工具名。
     *
     * <p>工具名必须保持稳定，否则：
     * <ol>
     *   <li>模型看到的 Tool Schema 会发生变化</li>
     *   <li>安全策略白名单需要跟着改变</li>
     *   <li>评测将无法公平比较 LOCAL / MCP</li>
     * </ol>
     * </p>
     */
    private static final String KAFKA_TOOL_NAME = "getKafkaStatus";

    private final OpsTools opsTools;

    /**
     * 使用 {@link ObjectProvider} 而非强依赖。
     * 默认 LOCAL 模式下 MCP Client 被关闭，此时应用仍必须能够正常启动和运行。
     */
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider;

    /** 应用默认 Kafka 工具模式，从配置 {@code app.kafka-tool-mode} 读取。 */
    private final KafkaToolMode defaultMode;

    public AgentToolProvider(
            OpsTools opsTools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider,
            @Value("${app.kafka-tool-mode:LOCAL}") String kafkaToolMode
    ) {

        this.opsTools = opsTools;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.defaultMode = KafkaToolMode.valueOf(kafkaToolMode.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 返回应用配置的默认模式。
     */
    public KafkaToolMode defaultMode() {
        return defaultMode;
    }

    /**
     * 获取当前 Agent 应注册的全部 Tool。
     *
     * @param mode Kafka Tool 接入方式
     * @return 完整 ToolCallback 集合
     */
    public ToolCallback[] getToolCallbacks(KafkaToolMode mode) {

        ToolCallback[] localCallbacks = ToolCallbacks.from(opsTools);

        /*
         * LOCAL 模式完全保留 Day6 行为。
         */
        if (mode == KafkaToolMode.LOCAL) {
            return localCallbacks;
        }

        /*
         * MCP 模式：
         *
         * getServiceStatus / queryErrorLogs
         * 继续使用本地 Tool。
         *
         * 本地 getKafkaStatus 必须移除，
         * 否则会产生两个同名 Tool。
         */
        List<ToolCallback> callbacks =
                new ArrayList<>(
                        Arrays.stream(localCallbacks)
                                .filter(callback ->
                                        !KAFKA_TOOL_NAME.equals(callback.getToolDefinition().name()))
                                .toList());

        SyncMcpToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();

        if (provider == null) {
            throw new IllegalStateException(
                    "当前配置为 MCP 模式，"
                            + "但没有可用的 "
                            + "SyncMcpToolCallbackProvider"
            );
        }

        /*
         * Spring AI MCP Client 会把远程 MCP Tool
         * 转换成普通 ToolCallback。
         */
        ToolCallback[] mcpCallbacks = provider.getToolCallbacks();

        ToolCallback kafkaMcpTool =
                Arrays.stream(mcpCallbacks)
                        .filter(callback ->
                                KAFKA_TOOL_NAME.equals(
                                        callback
                                                .getToolDefinition()
                                                .name()
                                )
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Kafka MCP Server "
                                                + "未暴露 Tool: "
                                                + KAFKA_TOOL_NAME
                                                + "，实际发现 Tool="
                                                + discoveredToolNames(mcpCallbacks)
                                )
                        );

        callbacks.add(kafkaMcpTool);

        return callbacks.toArray(ToolCallback[]::new);
    }

    /**
     * 输出 MCP Server 实际发现的 Tool 名，
     * 方便排查工具发现问题。
     */
    private List<String> discoveredToolNames(ToolCallback[] callbacks) {

        return Arrays.stream(callbacks)
                .map(callback ->
                        callback
                                .getToolDefinition()
                                .name()
                )
                .toList();
    }
}

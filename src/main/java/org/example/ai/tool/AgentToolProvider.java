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
 * Agent Tool 集合提供器。
 *
 * <p>
 * Day7 只替换 getKafkaStatus：
 *
 * LOCAL:
 * getServiceStatus  -> 本地
 * getKafkaStatus    -> 本地
 * queryErrorLogs    -> 本地
 * getDependencyStatus -> 本地 Adapter（HTTP / MOCK）
 *
 * MCP:
 * getServiceStatus  -> 本地
 * getKafkaStatus    -> MCP
 * queryErrorLogs    -> 本地
 * getDependencyStatus -> 本地 Adapter（HTTP / MOCK）
 *
 * AgentRunner 不需要关心 Tool 来自本地方法还是 MCP Server。
 * </p>
 */
@Component
public class AgentToolProvider {

    /**
     * Day7 唯一迁移到 MCP 的 Tool 名。
     *
     * <p>
     * Tool 名必须保持不变，否则：
     * 1. 模型看到的 Tool Schema 会发生变化；
     * 2. Day5 ExecutionPolicy 白名单需要跟着改变；
     * 3. Day6 Eval 将无法公平比较 LOCAL / MCP。
     * </p>
     */
    private static final String KAFKA_TOOL_NAME = "getKafkaStatus";

    private final OpsTools opsTools;

    /**
     * 使用 ObjectProvider 而不是强依赖。
     *
     * <p>
     * 默认 LOCAL 模式下 MCP Client 被关闭，
     * 此时应用仍然必须能够正常启动和运行 Day1~Day6。
     * </p>
     */
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider;

    /**
     * 应用默认 Kafka Tool 模式。
     */
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

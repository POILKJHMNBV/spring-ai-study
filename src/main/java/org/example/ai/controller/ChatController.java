package org.example.ai.controller;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.security.ConversationSecurity;
import org.example.ai.service.ChatService;
import org.example.ai.tool.KafkaToolMode;
import org.example.ai.tool.mock.MockScenario;
import org.example.ai.tool.mock.OpsMockDataProvider;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 聊天控制器：提供 HTTP 接口供前端或测试工具调用。
 *
 * <p>接口说明：
 * <ul>
 *   <li>{@code GET /ai/chat} — 简单对话，使用 {@link ChatService} 的声明式 API</li>
 *   <li>{@code GET /ai/agent} — Agent 对话，使用 {@link AgentRunner} 的多步推理能力</li>
 *   <li>{@code GET /ai/eval} — 评测接口，支持切换 Mock 场景，用于 Day6 评测</li>
 *   <li>{@code DELETE /ai/memory/{conversationId]] — 清空指定会话的短期 Memory</li>
 *   <li>{@code GET /ai/stream/chat} — 流式对话（未实现）</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatService chatService;
    private final AgentRunner agentRunner;
    private final OpsMockDataProvider opsMockDataProvider;

    public ChatController(ChatService chatService, AgentRunner agentRunner, OpsMockDataProvider opsMockDataProvider) {
        this.chatService = chatService;
        this.agentRunner = agentRunner;
        this.opsMockDataProvider = opsMockDataProvider;
    }

    @GetMapping("/chat")
    public String chat(String userPrompt, String conversationId) {
        return chatService.chat(userPrompt, conversationId);
    }

    /**
     * 执行带工具与检索能力的 Agent，并以稳定 JSON 对象返回运行状态、轨迹和结构化报告。
     *
     * @param userPrompt 当前故障排查问题
     * @param conversationId 当前会话标识
     * @param memoryEnabled 是否读取和保存会话记忆
     * @return 包含 answer 展示文本和 report 结构化字段的完整运行结果
     */
    @GetMapping("/agent")
    public AgentRunResult agent(String userPrompt,
                                String conversationId,
                                @RequestParam(defaultValue = "true") boolean memoryEnabled) {
        return agentRunner.run(userPrompt, conversationId, memoryEnabled, KafkaToolMode.MCP);
    }

    /**
     * 切换指定 Mock 场景并执行 Agent Eval，以稳定 JSON 对象返回完整运行结果。
     *
     * @param userPrompt 本次评测问题
     * @param conversationId 本次评测使用的会话标识
     * @param mockScenario 用于模拟工具数据的场景
     * @param memoryEnabled 是否读取和保存会话记忆
     * @return 包含 answer 展示文本和 report 结构化字段的完整运行结果
     */
    @GetMapping("/eval")
    public AgentRunResult eval(String userPrompt,
                               String conversationId,
                               MockScenario mockScenario,
                               @RequestParam(defaultValue = "false") boolean memoryEnabled) {
        opsMockDataProvider.useScenario(mockScenario);
        return agentRunner.run(userPrompt, conversationId, memoryEnabled);
    }

    /**
     * 清空指定会话的短期 Memory。
     *
     * <p>
     * 主要用于 Day5 实验和调试。
     * </p>
     */
    @DeleteMapping("/memory/{conversationId}")
    public void clearMemory(@PathVariable String conversationId) {

        String safeConversationId = ConversationSecurity.requireValidConversationId(conversationId);

        agentRunner.clearMemory(safeConversationId);
    }

    @GetMapping(value = "/stream/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> streamChat(String userPrompt, String conversationId) {
        return chatService.streamChat(userPrompt, conversationId);
    }
}

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

    @GetMapping("/agent")
    public String agent(String userPrompt,
                        String conversationId,
                        @RequestParam(defaultValue = "true") boolean memoryEnabled) {
        // AgentRunResult result = agentRunner.run(userPrompt, conversationId, memoryEnabled);
        AgentRunResult result = agentRunner.run(userPrompt, conversationId, memoryEnabled, KafkaToolMode.MCP);
        return result.answer();
    }

    @GetMapping("/eval")
    public String eval(String userPrompt,
                       String conversationId,
                       MockScenario mockScenario,
                       @RequestParam(defaultValue = "false") boolean memoryEnabled) {
        opsMockDataProvider.useScenario(mockScenario);
        AgentRunResult result = agentRunner.run(userPrompt, conversationId, memoryEnabled);
        return result.answer();
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

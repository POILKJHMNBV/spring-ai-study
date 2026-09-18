package org.example.ai.controller;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.security.ConversationSecurity;
import org.example.ai.service.ChatService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatService chatService; // Constructor injection
    private final AgentRunner agentRunner;

    public ChatController(ChatService chatService, AgentRunner agentRunner) {
        this.chatService = chatService;
        this.agentRunner = agentRunner;
    }

    @GetMapping("/chat")
    public String chat(String userPrompt, String conversationId) {
        return chatService.chat(userPrompt, conversationId);
    }

    @GetMapping("/agent")
    public String agent(String userPrompt,
                        String conversationId,
                        @RequestParam(defaultValue = "true") boolean memoryEnabled) {
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

package org.example.ai.controller;

import org.example.ai.harness.AgentRunResult;
import org.example.ai.harness.AgentRunner;
import org.example.ai.service.ChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
    public String chat(String userPrompt, String chatId) {
        return chatService.chat(userPrompt, chatId);
    }

    @GetMapping("/agent")
    public String agent(String userPrompt, String chatId) {
        AgentRunResult result = agentRunner.run(userPrompt, chatId);
        return result.answer();
    }

    @GetMapping(value = "/stream/chat", produces = "text/html;charset=UTF-8")
    public Flux<String> streamChat(String userPrompt, String chatId) {
        return chatService.streamChat(userPrompt, chatId);
    }
}

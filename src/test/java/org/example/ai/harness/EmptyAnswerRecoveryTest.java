package org.example.ai.harness;

import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.tool.AgentToolProvider;
import org.example.ai.tool.KafkaToolMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 保证空正文恢复有上限，且不会将空回答保存为成功结果。 */
class EmptyAnswerRecoveryTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ChatMemory memory = mock(ChatMemory.class);

    private AgentRunner runner() {
        var provider = mock(AgentToolProvider.class);
        when(provider.getToolCallbacks(KafkaToolMode.LOCAL)).thenReturn(new ToolCallback[0]);
        when(model.getOptions()).thenReturn(OllamaChatOptions.builder().build());
        var retriever = mock(KnowledgeRetriever.class);
        when(retriever.retrieve(anyString())).thenReturn(List.of());
        return new AgentRunner(ToolCallingManager.builder().build(), provider, model,
                new ExecutionPolicy(), retriever, memory);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void retriesEmptyAnswerOnceWithinStepBudget() {
        when(model.call(any(Prompt.class))).thenReturn(response(""), response("证据不足，无法确定"));
        var result = runner().run("排查服务", "empty-recovery", false);
        assertEquals(AgentRunResult.RunStatus.COMPLETED, result.status());
        assertEquals(2, result.completedSteps());
        assertEquals("证据不足，无法确定", result.answer());
        assertTrue(result.trace().stream().anyMatch(event -> "EMPTY_ANSWER_RETRY".equals(event.output())
                || "EMPTY_ANSWER_RETRY".equals(event.status())));
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void repeatedEmptyAnswerFailsWithoutSavingMemory() {
        when(model.call(any(Prompt.class))).thenReturn(response(" "));
        when(memory.get("empty-failure")).thenReturn(List.of());
        var result = runner().run("排查服务", "empty-failure", true);
        assertEquals(AgentRunResult.RunStatus.FAILED, result.status());
        verify(model, times(2)).call(any(Prompt.class));
        verify(memory).get("empty-failure");
        verifyNoMoreInteractions(memory);
    }
}

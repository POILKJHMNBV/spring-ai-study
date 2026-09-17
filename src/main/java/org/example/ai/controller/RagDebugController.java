package org.example.ai.controller;

import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievedChunk;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import java.util.Map;

import java.util.List;

@RestController
@RequestMapping("/rag")
public class RagDebugController {
    private final KnowledgeRetriever retriever;
    public RagDebugController(KnowledgeRetriever retriever) {
        this.retriever = retriever;
    }

    @GetMapping("/search")
    public List<RetrievedChunk> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK,
            @RequestParam(required = false) Double threshold
    ) {

        return retriever.retrieve(
                query,
                topK,
                threshold
        );
    }

    // 参数错误返回明确的 400，不让非法范围触发模型请求或变成服务端 500。
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }
}

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

/**
 * RAG 调试控制器：提供 HTTP 接口用于测试和调试检索质量。
 *
 * <p>接口说明：
 * <ul>
 *   <li>{@code GET /rag/search} — 执行检索并返回结果，支持指定 topK 和 threshold</li>
 * </ul>
 * </p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>参数非法时返回 400，不让非法参数触发模型请求或变成 500</li>
 *   <li>返回完整的 {@link RetrievedChunk} 列表，便于分析检索质量</li>
 * </ul>
 * </p>
 */
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

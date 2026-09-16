package org.example.ai.controller;

import org.example.ai.rag.KnowledgeRetriever;
import org.example.ai.rag.RetrievedChunk;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(defaultValue = "3") int topK,
            @RequestParam(defaultValue = "0.0") double threshold
    ) {

        return retriever.retrieve(
                query,
                topK,
                threshold
        );
    }
}

package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class KnowledgeRetriever {

    private static final int DEFAULT_TOP_K = 3;

    private static final double DEFAULT_THRESHOLD = 0.50;

    private final VectorStore vectorStore;

    public KnowledgeRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    public List<RetrievedChunk> retrieve(String query, int topK, double threshold) {

        SearchRequest request =
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(threshold)
                        .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents.isEmpty()) {
            log.info("RAG no result: query={}", query);
            return List.of();
        }

        List<RetrievedChunk> chunks =
                documents.stream()
                        .map(this::toChunk)
                        .toList();

        for (int i = 0; i < chunks.size(); i++) {

            RetrievedChunk chunk = chunks.get(i);

            log.info(
                    """
                    RAG hit rank={}
                    source={}
                    chunkIndex={}
                    score={}
                    text={}
                    """,
                    i + 1,
                    chunk.source(),
                    chunk.chunkIndex(),
                    chunk.score(),
                    abbreviate(chunk.text())
            );
        }
        return chunks;
    }

    private RetrievedChunk toChunk(Document document) {

        Map<String, Object> metadata = document.getMetadata();

        Object index =
                metadata.getOrDefault(
                        "chunkIndex",
                        -1
                );

        return new RetrievedChunk(
                String.valueOf(
                        metadata.getOrDefault(
                                "source",
                                "unknown"
                        )
                ),
                String.valueOf(
                        metadata.getOrDefault(
                                "title",
                                "unknown"
                        )
                ),
                index instanceof Number number
                        ? number.intValue()
                        : -1,
                document.getScore(),
                Objects.requireNonNullElse(
                        document.getText(),
                        ""
                )
        );
    }

    private String abbreviate(String value) {
        int max = 300;
        return value.length() <= max
                ? value
                : value.substring(0, max)
                + "...";
    }
}

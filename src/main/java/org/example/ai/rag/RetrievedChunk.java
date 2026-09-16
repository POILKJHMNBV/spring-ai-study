package org.example.ai.rag;

public record RetrievedChunk(
        String source,
        String title,
        int chunkIndex,
        Double score,
        String text
) {
}
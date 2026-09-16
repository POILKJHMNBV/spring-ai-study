package org.example.ai.rag;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@NullMarked
public class KnowledgeLoader implements ApplicationRunner {
    private static final String KNOWLEDGE_PATTERN = "classpath*:knowledge/*.md";
    private final VectorStore vectorStore;
    private final MarkdownSectionSplitter markdownSectionSplitter;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private final TokenTextSplitter splitter =
            TokenTextSplitter.builder()
                    .withChunkSize(400)
                    .withMinChunkSizeChars(150)
                    .withMinChunkLengthToEmbed(20)
                    .withMaxNumChunks(100)
                    .withKeepSeparator(true)
                    .withPunctuationMarks(
                            List.of(
                                    '。',
                                    '！',
                                    '？',
                                    '；',
                                    '\n',
                                    '.',
                                    '!',
                                    '?'
                            )
                    )
                    .build();

    public KnowledgeLoader(VectorStore vectorStore, MarkdownSectionSplitter markdownSectionSplitter) {
        this.vectorStore = vectorStore;
        this.markdownSectionSplitter = markdownSectionSplitter;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = resolver.getResources(KNOWLEDGE_PATTERN);

        if (resources.length == 0) {
            throw new IllegalStateException("No knowledge documents found");
        }

        List<Document> allChunks = new ArrayList<>();

        for (Resource resource : resources) {
            String content;
            try (InputStream inputStream = resource.getInputStream()) {
                content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            String source =
                    resource.getFilename() != null
                            ? resource.getFilename()
                            : resource.getDescription();

            List<Document> chunks = markdownSectionSplitter.split(
                            content,
                            source);

            allChunks.addAll(chunks);

            for (Document chunk : chunks) {
                Map<String, Object> metadata = chunk.getMetadata();
                log.info(
                        """
                        
                        ===== KNOWLEDGE CHUNK =====
                        source={}
                        section={}
                        chunkIndex={}
                        chars={}
                        text={}
                        ===========================
                        """,
                        metadata.get("source"),
                        metadata.get("section"),
                        metadata.get("chunkIndex"),
                        chunk.getText() == null
                                ? 0
                                : chunk.getText().length(),
                        chunk.getText()
                );
            }
        }

        vectorStore.add(allChunks);

        log.info(
                "Knowledge base loaded: files={}, chunks={}",
                resources.length,
                allChunks.size()
        );
    }

    private List<Document> load(Resource resource) throws IOException {
        String content;
        try (InputStream inputStream = resource.getInputStream()) {
            content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        String source = resource.getFilename() != null
                        ? resource.getFilename()
                        : resource.getDescription();

        String title = extractTitle(content, source);

        Document document =
                Document.builder()
                        .text(content)
                        .metadata("source", source)
                        .metadata("title", title)
                        .build();

        List<Document> chunks = splitter.apply(List.of(document));

        List<Document> indexedChunks = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {

            Document chunk = chunks.get(i)
                            .mutate()
                            .metadata(
                                    "chunkIndex",
                                    i
                            )
                            .build();

            indexedChunks.add(chunk);

            log.info(
                    "Knowledge chunk: source={}, index={}, chars={}",
                    source,
                    i,
                    chunk.getText() == null
                            ? 0
                            : chunk.getText().length()
            );
        }

        return indexedChunks;
    }

    private String extractTitle(String content, String fallback) {
        return content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(fallback);
    }
}

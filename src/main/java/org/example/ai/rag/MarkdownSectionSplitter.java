package org.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownSectionSplitter {
    private final TokenTextSplitter oversizedSectionSplitter =
            TokenTextSplitter.builder()
                    .withChunkSize(220)
                    .withMinChunkSizeChars(80)
                    .withMinChunkLengthToEmbed(20)
                    .withMaxNumChunks(50)
                    .withKeepSeparator(true)
                    .withPunctuationMarks(
                            List.of(
                                    '。',
                                    '！',
                                    '？',
                                    '；',
                                    '\n'
                            )
                    )
                    .build();

    public List<Document> split(String content, String source) {

        String title = extractTitle(content, source);

        List<Section> sections = parseSections(content);

        List<Document> result = new ArrayList<>();

        int chunkIndex = 0;

        for (Section section : sections) {

            String sectionText = """
                    # %s

                    ## %s

                    %s
                    """.formatted(
                    title,
                    section.heading(),
                    section.body()
            ).trim();

            Document sectionDocument =
                    Document.builder()
                            .text(sectionText)
                            .metadata(
                                    Map.of(
                                            "source", source,
                                            "title", title,
                                            "section",
                                            section.heading()
                                    )
                            )
                            .build();

            /*
             * 小 section 不会被强行切开；
             * 超过 chunkSize 才进行 Token 二次切分。
             */
            List<Document> sectionChunks =
                    oversizedSectionSplitter.apply(
                            List.of(sectionDocument)
                    );

            for (Document chunk : sectionChunks) {

                result.add(
                        chunk.mutate()
                                .metadata(
                                        "chunkIndex",
                                        chunkIndex++
                                )
                                .build()
                );
            }
        }

        return result;
    }

    private List<Section> parseSections(String content) {

        List<Section> sections = new ArrayList<>();
        String currentHeading = "概述";
        StringBuilder body = new StringBuilder();

        for (String line : content.split("\\R")) {

            // H1 只作为文档标题，不单独形成 section
            if (line.startsWith("# ")) {
                continue;
            }

            if (line.startsWith("## ")) {

                addSectionIfNecessary(
                        sections,
                        currentHeading,
                        body
                );

                currentHeading = line.substring(3).trim();

                body = new StringBuilder();
                continue;
            }

            body.append(line).append('\n');
        }

        addSectionIfNecessary(
                sections,
                currentHeading,
                body
        );

        return sections;
    }

    private void addSectionIfNecessary(List<Section> sections, String heading, StringBuilder body) {
        String text = body.toString().trim();
        if (!text.isBlank()) {
            sections.add(new Section(heading, text));
        }
    }

    private String extractTitle(String content, String fallback) {
        return content.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse(fallback);
    }

    private record Section(String heading, String body) {}
}

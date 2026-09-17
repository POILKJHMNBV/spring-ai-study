package org.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 冻结修复前的切分行为，仅供因果对照；生产代码始终使用新的预算切分器。 */
final class BaselineSectionSplitter {
    static List<Document> split(String content, String source) {
        var splitter = TokenTextSplitter.builder().withChunkSize(220).withMinChunkSizeChars(80)
                .withMinChunkLengthToEmbed(20).withMaxNumChunks(50).withKeepSeparator(true)
                .withPunctuationMarks(List.of('。', '！', '？', '；', '\n')).build();
        String title = content.lines().filter(s -> s.startsWith("# "))
                .map(s -> s.substring(2).trim()).findFirst().orElse(source);
        var result = new ArrayList<Document>();
        String heading = "概述";
        var body = new StringBuilder();
        // 末尾哨兵负责刷新最后一个小节，不产生额外文档。
        for (String line : (content + "\n## END").split("\\R")) {
            if (line.startsWith("# ")) continue;
            if (line.startsWith("## ")) {
                if (!body.toString().isBlank()) {
                    String text = "# " + title + "\n\n## " + heading + "\n\n" + body.toString().trim();
                    var document = Document.builder().text(text).metadata(Map.of("source", source,
                            "title", title, "section", heading, "domain", MarkdownSectionSplitter.domain(source))).build();
                    for (Document chunk : splitter.apply(List.of(document))) {
                        result.add(chunk.mutate().metadata("chunkIndex", result.size()).build());
                    }
                }
                heading = line.substring(3).trim();
                body = new StringBuilder();
            } else body.append(line).append('\n');
        }
        return result;
    }
}

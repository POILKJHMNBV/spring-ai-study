package org.example.ai.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在有来源的向量候选内，用 BM25 排名补充术语证据；最终仍由检索器应用向量阈值。
 */
@Component
public class LexicalRrfRanker {
    private static final Pattern TERMS = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*|[\\p{IsHan}]+");
    private static final double RRF_K = 60;

    /**
     * 基于传入向量顺序与当前固定候选集的 BM25 词法顺序做 RRF。
     * 空正文候选保留空词频表以维持下标对应，且不会改写 Document.score。
     */
    public List<Document> rank(String query, List<Document> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<Map<String, Integer>> documents = candidates.stream()
                .map(d -> terms(Optional.ofNullable(d.getText()).orElse("")))
                .toList();
        Set<String> queryTerms = terms(query).keySet();
        double averageLength = documents.stream()
                .mapToInt(LexicalRrfRanker::length)
                .average()
                .orElse(1);
        Map<String, Integer> frequencies = new HashMap<>();
        documents.forEach(d -> d.keySet().forEach(t -> frequencies.merge(t, 1, Integer::sum)));
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Integer> document = documents.get(i);
            double score = 0;
            for (String term : queryTerms) {
                int tf = document.getOrDefault(term, 0);
                if (tf == 0) {
                    continue;
                }
                int df = frequencies.get(term);
                double idf = Math.log(1 + (candidates.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length(document) / averageLength));
            }
            scores.put(candidates.get(i).getId(), score);
        }
        List<Document> lexical = candidates.stream().filter(d -> scores.get(d.getId()) > 0)
                .sorted(Comparator.<Document>comparingDouble(d -> scores.get(d.getId())).reversed()
                        .thenComparing(Document::getId)).toList();
        Map<String, Double> fused = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            fused.put(candidates.get(i).getId(), 1 / (RRF_K + i + 1));
        }
        for (int i = 0; i < lexical.size(); i++) {
            fused.merge(lexical.get(i).getId(), 1 / (RRF_K + i + 1), Double::sum);
        }
        // 只融合名次，不直接相加 BM25 与余弦分数；DTO 的 score 仍表示原始向量相似度。
        return candidates.stream().sorted(Comparator.<Document>comparingDouble(d -> fused.get(d.getId())).reversed())
                .toList();
    }

    /** 计算当前候选的总词频，用于 BM25 长度归一化。 */
    private static int length(Map<String, Integer> terms) {
        return terms.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 英文保留完整词，中文以相邻双字建词频，不依赖领域关键词表。 */
    private static Map<String, Integer> terms(String text) {
        if (Objects.isNull(text) || text.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> result = new HashMap<>();
        Matcher matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                // 中文使用相邻双字，英文异常类名/指标名完整保留，避免人为维护查询关键词表。
                int[] points = token.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) {
                    result.merge(new String(points, i, 2), 1, Integer::sum);
                }
            } else {
                result.merge(token, 1, Integer::sum);
            }
        }
        return result;
    }
}

package org.example.ai.rag.rerank;

import org.example.ai.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无外部服务的词项覆盖重排器，采用字段加权与最佳正文片段匹配。
 * 标题和小节贡献 40%，正文最佳片段贡献 60%；每部分的分数均为
 * 命中的不同查询词数 / 不同查询词总数。该方法是可解释的词项匹配，
 * 并非 cross encoder，也不把向量 score 当作重排分或置信概率。
 */
@Component
public class TokenCoverageReranker implements DocumentReranker {
    private static final Pattern TERMS = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*|[\\p{IsHan}]+");
    private static final Pattern PASSAGES = Pattern.compile("[。！？!?；;\\r\\n]+");
    private static final int WINDOW_CODE_POINTS = 160;
    private static final int WINDOW_STRIDE = 80;

    /**
     * 对输入建立独立结果列表；Java 稳定排序保证覆盖率相同时沿用 RRF 名次。
     * 正文按句/行切分，长段再按 160 个 Unicode 码点分窗，避免整篇长文堆词获益。
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || topK < 1 || topK > 20) {
            throw new IllegalArgumentException("query、候选和 topK 必须有效");
        }
        Set<String> wanted = terms(query);
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        if (!wanted.isEmpty()) {
            Map<RetrievedChunk, Double> scores = new IdentityHashMap<>();
            sorted.forEach(chunk -> scores.put(chunk, relevance(chunk, wanted)));
            sorted.sort((left, right) -> Double.compare(scores.get(right), scores.get(left)));
        }
        return List.copyOf(sorted.subList(0, Math.min(topK, sorted.size())));
    }

    /** 计算固定字段权重下的覆盖率；权重在看评测结果前固定。 */
    private double relevance(RetrievedChunk chunk, Set<String> wanted) {
        double heading = coverage(chunk.title() + " " + chunk.section(), wanted);
        double bestPassage = 0;
        for (String passage : PASSAGES.split(chunk.text() == null ? "" : chunk.text())) {
            int[] points = passage.codePoints().toArray();
            for (int start = 0; start < points.length; start += WINDOW_STRIDE) {
                int length = Math.min(WINDOW_CODE_POINTS, points.length - start);
                bestPassage = Math.max(bestPassage,
                        coverage(new String(points, start, length), wanted));
            }
        }
        return 0.4 * heading + 0.6 * bestPassage;
    }

    /** 计算单字段或单正文窗口覆盖了多少个不同的查询词。 */
    private double coverage(String text, Set<String> wanted) {
        Set<String> matched = terms(text);
        matched.retainAll(wanted);
        return (double) matched.size() / wanted.size();
    }

    /** 英文保留完整词，连续中文拆为相邻双字；无词典或领域答案表。 */
    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        if (text == null || text.isBlank()) return result;
        Matcher matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = token.codePoints().toArray();
                if (points.length == 1) result.add(token);
                for (int i = 0; i + 1 < points.length; i++) {
                    result.add(new String(points, i, 2));
                }
            } else {
                result.add(token);
            }
        }
        return result;
    }
}

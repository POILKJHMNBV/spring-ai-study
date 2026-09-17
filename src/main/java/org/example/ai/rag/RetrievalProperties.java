package org.example.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * HTTP 与内部调用共用一份默认值，避免调试查得到、业务查不到。
 */
@ConfigurationProperties("rag.retrieval")
public record RetrievalProperties(@DefaultValue("3") int topK,
                                  @DefaultValue("0.47") double threshold) {
    public RetrievalProperties {
        validate("配置检查", topK, threshold);
    }

    public static void validate(String query, int topK, double threshold) {
        if (query == null || query.isBlank() || query.length() > 2000) {
            throw new IllegalArgumentException("query 必须为 1～2000 个字符的非空文本");
        }
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK 必须在 1～20 之间");
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold 必须为 0～1 之间的有限数值");
        }
    }
}

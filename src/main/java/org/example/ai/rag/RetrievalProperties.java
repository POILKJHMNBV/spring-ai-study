package org.example.ai.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * HTTP 与内部调用共用默认 topK、向量阈值和排序模式。
 * mode 默认 HYBRID；Day12 实测重排存在来源覆盖退化，因此保留原默认模式。
 */
@ConfigurationProperties("rag.retrieval")
public record RetrievalProperties(@DefaultValue("3") int topK,
                                  @DefaultValue("0.47") double threshold,
                                  @DefaultValue("HYBRID") RetrievalMode mode) {
    /** 配置加载时即验证边界，避免错误参数延迟到检索请求才暴露。 */
    @ConstructorBinding
    public RetrievalProperties {
        validate("配置检查", topK, threshold);
        if (mode == null) throw new IllegalArgumentException("mode 不能为空");
    }

    /** 兼容已有测试和手工创建检索器的两参数构造调用。 */
    public RetrievalProperties(int topK, double threshold) {
        this(topK, threshold, RetrievalMode.HYBRID);
    }

    /** 验证查询、输出条数和原始向量分阈值；业务与评测共用规则。 */
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

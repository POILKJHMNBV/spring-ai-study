package org.example.ai.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;

/** Day13 观测配置：复用 Spring Boot 自动配置的 MeterRegistry 与 ObservationRegistry。 */
@NullMarked
@Configuration
public class ObservabilityConfig {
    /** 为业务边界提供统一的指标及追踪入口。 */
    @Bean
    public AgentTelemetry agentTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        return new AgentTelemetry(meters, observations);
    }

    /**
     * PgVector 官方自动配置会读取此 Convention Bean。默认实现的高基数字段含
     * db.vector.query.content/filter，可能把原始问题与过滤器内容写入 Trace。
     * 保留默认低基数字段和向量库 Span，只清空高基数字段。
     */
    @Bean
    public VectorStoreObservationConvention safeVectorStoreObservationConvention() {
        return new DefaultVectorStoreObservationConvention() {
            /** 不导出查询正文及过滤器，低基数维度和观测名称沿用官方实现。 */
            @Override
            public KeyValues getHighCardinalityKeyValues(VectorStoreObservationContext context) {
                return KeyValues.empty();
            }
        };
    }
}

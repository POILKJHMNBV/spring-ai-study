package org.example.ai.rag;

import org.example.ai.SpringAiStudyApplication;
import org.example.ai.rag.synchronize.PgKnowledgeIndex;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式运行：mvn test -Dtest=Day11PgVectorIT；使用虚拟机与真实 BGE-M3。 */
class Day11PgVectorIT {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void changedMissingAndRemovedChunksSynchronizeAtomically() throws Exception {
        var jdbc = jdbc();
        String schema = "day11_it_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            // 数据库行为使用确定性向量，真实 BGE-M3 由下面的重启测试覆盖。
            var model = mock(EmbeddingModel.class);
            when(model.embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                    .thenReturn(List.of(new float[]{1, 0, 0}));
            var store = PgVectorStore.builder(jdbc, model).schemaName(schema).dimensions(3)
                    .idType(PgVectorStore.PgIdType.TEXT).initializeSchema(true).build();
            store.afterPropertiesSet();
            var properties = new PgVectorStoreProperties();
            properties.setSchemaName(schema);
            properties.setDimensions(3);
            var index = new PgKnowledgeIndex(store, jdbc,
                    new JdbcTransactionManager(jdbc.getDataSource()), properties);
            var original = List.of(document("a", "original"), document("b", "retained"));
            assertEquals(new PgKnowledgeIndex.Result(2, 2, 0, 0), index.synchronize(original, "model-v1"));
            assertEquals(new PgKnowledgeIndex.Result(2, 0, 2, 0), index.synchronize(original, "model-v1"));
            var changed = List.of(document("c", "updated"), original.get(1));
            assertEquals(new PgKnowledgeIndex.Result(2, 1, 1, 1), index.synchronize(changed, "model-v1"));
            List<String> snapshot = jdbc.queryForList(
                    "SELECT row_to_json(v)::text FROM " + schema + ".vector_store v ORDER BY id", String.class);
            when(model.embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class)))
                    .thenReturn(List.of(new float[]{0, 1, 0}))
                    .thenThrow(new IllegalStateException("simulated embedding failure"));
            assertThrows(IllegalStateException.class, () -> index.synchronize(changed, "model-v2"));
            assertEquals(snapshot, jdbc.queryForList(
                    "SELECT row_to_json(v)::text FROM " + schema + ".vector_store v ORDER BY id", String.class),
                    "中途失败必须回滚已写入的向量和指纹");
            doReturn(List.of(new float[]{0, 1, 0})).when(model)
                    .embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class));
            assertEquals(new PgKnowledgeIndex.Result(2, 2, 0, 0), index.synchronize(changed, "model-v2"));
            jdbc.update("DELETE FROM " + schema + ".vector_store WHERE id='b'");
            assertEquals(new PgKnowledgeIndex.Result(2, 1, 1, 0), index.synchronize(changed, "model-v2"));
            // 不属于本应用的向量不得被旧块清理删除。
            store.add(List.of(document("external", "foreign")));
            assertEquals(new PgKnowledgeIndex.Result(0, 0, 0, 2), index.synchronize(List.of(), "model-v2"));
            assertEquals(List.of("external"), jdbc.queryForList("SELECT id FROM " + schema + ".vector_store", String.class));
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private Document document(String id, String text) {
        return Document.builder().id(id).text(text).metadata(Map.of("source", "mysql-slow-query.md",
                "title", "Database", "section", "diagnosis", "category", "mysql", "version", "1",
                "service", "shared")).build();
    }

    @Test
    void postgresAndPgvectorSupportTransactionsAndCosineSearch() throws Exception {
        var jdbc = jdbc();
        System.out.println("Day11 PostgreSQL: " + jdbc.queryForObject("SHOW server_version", String.class));
        System.out.println("Day11 extensions: " + jdbc.queryForList(
                "SELECT extname, extversion FROM pg_extension WHERE extname IN ('vector','hstore','uuid-ossp')"));
        assertNotNull(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class));
        assertEquals(0.0, jdbc.queryForObject("SELECT '[1,0,0]'::vector <=> '[1,0,0]'::vector", Double.class), 1e-6);
        // 临时表仅属于本连接，关闭时清理，不依赖或更改手工 document_vector 表。
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE TEMP TABLE day11_probe(id integer, embedding vector(3)) ON COMMIT DROP");
            statement.execute("INSERT INTO day11_probe VALUES (1, '[1,0,0]'), (2, '[0,1,0]')");
            statement.execute("CREATE INDEX ON day11_probe USING hnsw (embedding vector_cosine_ops)");
            try (var result = statement.executeQuery(
                    "SELECT id FROM day11_probe ORDER BY embedding <=> '[1,0,0]'::vector LIMIT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            connection.rollback();
        }
    }

    @Test
    void applicationRestartReusesPersistedEmbeddingsAndMetadataFilters() throws Exception {
        var jdbc = jdbc();
        String schema = "day11_it_" + UUID.randomUUID().toString().replace("-", "");
        Path manifest = Path.of("target", "day11", schema + ".json");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            List<String> ids;
            List<String> metadata;
            try (var first = start(schema, manifest)) {
                assertInstanceOf(PgVectorStore.class, first.getBean(VectorStore.class));
                verify(first.getBean(EmbeddingModel.class), times(23))
                        .embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class));
                ids = jdbc.queryForList("SELECT id FROM " + schema + ".vector_store ORDER BY id", String.class);
                metadata = jdbc.queryForList("SELECT metadata::text FROM " + schema + ".vector_store ORDER BY id", String.class);
                assertEquals(23, ids.size());
                assertEquals(23, mapper.readTree(Files.readString(manifest)).path("embedded").asInt());
                assertTrue(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? "
                        + "AND indexname='spring_ai_vector_index'", String.class, schema).contains("hnsw"));
            }
            // 销毁整个 Spring 上下文和连接池，同时移除清单，排除内存与文件缓存复用。
            Files.delete(manifest);
            try (var restarted = start(schema, manifest)) {
                EmbeddingModel model = restarted.getBean(EmbeddingModel.class);
                verify(model, never()).embed(anyList(), any(EmbeddingOptions.class), any(BatchingStrategy.class));
                verify(model, never()).embed(anyString());
                var report = mapper.readTree(Files.readString(manifest));
                assertEquals(0, report.path("embedded").asInt());
                assertEquals(23, report.path("reused").asInt());
                assertEquals(ids, jdbc.queryForList("SELECT id FROM " + schema + ".vector_store ORDER BY id", String.class));
                assertEquals(metadata, jdbc.queryForList("SELECT metadata::text FROM " + schema + ".vector_store ORDER BY id", String.class));
                var store = restarted.getBean(VectorStore.class);
                List<Document> hits = store.similaritySearch(SearchRequest.builder().query("数据库连接池接近满")
                        .topK(3).similarityThreshold(0)
                        .filterExpression("category == 'mysql' && service == 'shared'").build());
                assertFalse(hits.isEmpty());
                for (var hit : hits) {
                    assertEquals("mysql-slow-query.md", hit.getMetadata().get("source"));
                    for (String key : List.of("source", "title", "category", "version", "updatedAt", "service")) {
                        assertFalse(hit.getMetadata().get(key).toString().isBlank());
                    }
                }
                assertTrue(store.similaritySearch(SearchRequest.builder().query("数据库连接池接近满")
                        .filterExpression("service == 'nonexistent-service'").build()).isEmpty());
                var retrieved = restarted.getBean(KnowledgeRetriever.class).retrieve("数据库连接池接近满");
                assertTrue(retrieved.stream().anyMatch(hit -> "mysql-slow-query.md".equals(hit.source())));
                System.out.println("Day11 restart: 23 persisted, 0 embedded, 23 reused; metadata filters and RAG passed");
            }
        } finally {
            // schema 来自本测试生成的 UUID，只清理本次创建的隔离对象。
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private ConfigurableApplicationContext start(String schema, Path manifest) {
        return new SpringApplicationBuilder(SpringAiStudyApplication.class, EmbeddingSpyConfiguration.class)
                .web(WebApplicationType.NONE).run("--spring.profiles.active=local", "--app.ops.data-source=MOCK",
                        "--spring.ai.vectorstore.pgvector.schema-name=" + schema,
                        "--rag.index.manifest-path=" + manifest);
    }

    private JdbcTemplate jdbc() throws Exception {
        var environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        var datasource = new DriverManagerDataSource();
        datasource.setUrl(environment.getRequiredProperty("spring.datasource.url"));
        datasource.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        datasource.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        return new JdbcTemplate(datasource);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EmbeddingSpyConfiguration {
        @Bean
        static BeanPostProcessor embeddingSpy() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof EmbeddingModel ? spy(bean) : bean;
                }
            };
        }
    }
}

package org.example.ai.rag;

import com.sun.net.httpserver.HttpServer;
import org.example.ai.rag.synchronize.KnowledgeLoader;
import org.example.ai.rag.synchronize.PgKnowledgeIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 模拟模型清单接口，针对生产 Loader 验证失败定位和成功清单，不依赖真实 Ollama。 */
class KnowledgeLoaderTest {
    @TempDir Path directory;

    @Test
    void rejectSilentTruncationAndRemoveStaleManifest() throws Exception {
        Path manifest = directory.resolve("manifest.json");
        Files.writeString(manifest, "old successful build");
        var store = mock(PgKnowledgeIndex.class);
        var loader = new KnowledgeLoader(store, new MarkdownSectionSplitter(),
                "bge-m3", "http://localhost:1", true, manifest.toString());
        assertThrows(IllegalStateException.class, () -> loader.run(null));
        assertFalse(Files.exists(manifest));
        verifyNoInteractions(store);
    }

    @Test
    void indexFailuresIdentifySourceSectionAndNeverPublishSuccessManifest() throws Exception {
        var server = tagsServer();
        try {
            var store = mock(PgKnowledgeIndex.class);
            doThrow(new IllegalStateException("source=mysql-slow-query.md section=诊断 禁止开启静默截断"))
                    .when(store).synchronize(anyList(), anyString());
            Path manifest = directory.resolve("manifest.json");
            var loader = loader(store, server, manifest);
            var failure = assertThrows(IllegalStateException.class, () -> loader.run(null));
            assertTrue(failure.getMessage().contains("source="));
            assertTrue(failure.getMessage().contains("section="));
            assertTrue(failure.getMessage().contains("禁止开启静默截断"));
            assertFalse(Files.exists(manifest));
        } finally { server.stop(0); }
    }

    @Test
    void successfulBuildRecordsFingerprintAndAllChunks() throws Exception {
        var server = tagsServer();
        try {
            var store = mock(PgKnowledgeIndex.class);
            when(store.synchronize(anyList(), anyString())).thenReturn(new PgKnowledgeIndex.Result(23, 23, 0, 0));
            Path manifest = directory.resolve("manifest.json");
            loader(store, server, manifest).run(null);
            var json = JsonMapper.builder().build().readTree(Files.readString(manifest));
            assertEquals("test-digest", json.path("digest").asText());
            assertEquals(1024, json.path("dimensions").asInt());
            assertEquals(3, json.path("files").asInt());
            assertEquals(23, json.path("chunks").asInt());
            assertEquals(64, json.path("corpusHash").asText().length());
            assertEquals(3, json.path("sourceHashes").size());
            assertEquals(MarkdownSectionSplitter.VERSION, json.path("splitterVersion").asText());
            verify(store).synchronize(argThat(documents -> documents.size() == 23
                    && documents.stream().allMatch(d -> d.getMetadata().keySet()
                    .containsAll(List.of("source", "title", "category", "version", "service")))), anyString());
        } finally { server.stop(0); }
    }

    private KnowledgeLoader loader(PgKnowledgeIndex store, HttpServer server, Path manifest) {
        when(store.dimensions()).thenReturn(1024);
        return new KnowledgeLoader(store, new MarkdownSectionSplitter(), "bge-m3",
                "http://localhost:" + server.getAddress().getPort(), false, manifest.toString());
    }

    private HttpServer tagsServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = "{\"models\":[{\"name\":\"bge-m3:latest\",\"digest\":\"test-digest\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().put("Content-Type", List.of("application/json"));
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}

package org.example.ai.rag.synchronize;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将本轮完整知识语料与 PostgreSQL 中的受管向量索引同步。
 *
 * <p>数据库中的正文、元数据、向量是否存在以及索引指纹共同决定一个块能否复用，
 * 本地 manifest 仅供诊断，不是跳过 Embedding 的依据。因此即使删除 manifest，
 * 只要数据库记录与本轮输入一致，重启仍然可以复用已持久化的向量。</p>
 *
 * <p>职责分工：本类通过 Spring JDBC 读取索引状态并获取 PostgreSQL 事务级咨询锁；
 * {@link PgVectorStore} 负责调用 Embedding 模型以及向量写入、删除。
 * {@link TransactionTemplate} 将读取、比较、写入和清理放在同一个事务中。
 * 这要求本类的 JDBC、向量库内部 JDBC 和事务管理器使用同一个 DataSource，
 * 且这些数据库操作在当前线程执行。</p>
 *
 * <p>当前实现面向小规模知识库：会读取全部受管记录，并在持有事务和锁期间调用模型。
 * 数据库回滚能撤销本轮数据库修改，但不能撤销已经发生的模型请求。</p>
 */
@Component
public class PgKnowledgeIndex {
    /**
     * 知识索引的逻辑归属标记，同时用于 metadata.indexOwner 和咨询锁的命名。
     * 这是应用层的稳定知识库标识，不是 PostgreSQL 用户，也不是表的 SQL OWNER。
     * application.yaml 的 spring.datasource.username 用于数据库连接认证和权限判断；
     * 同一数据库账号可以服务多个知识库，账号轮换也不应改变已有知识块的归属。
     * 因此不能直接拿数据库用户名代替此标记。
     *
     * <p>该标记只限定本类扫描和清理的范围，不提供数据库授权或完整的多租户隔离。
     * 表内主键仍是文档 ID；共享表的其他写入方还必须避免 ID 冲突。
     * 修改此常量需要考虑旧标记数据的迁移，否则旧记录不会被本类扫描和清理。</p>
     */
    static final String OWNER = "java-troubleshooting-knowledge";

    /** 官方向量库实现：负责生成向量和执行向量数据的增删。 */
    private final PgVectorStore store;
    /** Spring 提供的 JDBC 封装，用于向量库通用 API 未提供的状态查询和 PostgreSQL 专用锁。 */
    private final JdbcTemplate jdbc;
    /** 程序式事务边界；运行时异常会使回调内的数据库修改回滚。 */
    private final TransactionTemplate transaction;
    /** 经白名单校验并分别加双引号的 schema.table，避免依赖连接的默认 search_path。 */
    private final String table;
    /** 配置声明的向量维度；用于上层生成指纹，不代表此处已校验模型输出或现有表结构。 */
    private final int dimensions;
    /** 将 JDBC 读回的 JSON 与 Java 元数据统一转换成 JSON 树后比较。 */
    private final JsonMapper mapper = JsonMapper.builder().build();

    /**
     * 复用 Spring 注入的向量库、JDBC 和事务管理器，不自行创建数据库连接。
     * TransactionTemplate 默认采用 REQUIRED 传播行为：无事务时新建，有事务时加入。
     * 表名和维度取自向量库配置，保证同步操作与向量库存储指向同一配置目标。
     *
     * @param store 自动配置的 PGVector 向量库
     * @param jdbc 与向量库使用同一数据源的 JDBC 模板
     * @param transactionManager 管理该数据源的事务管理器
     * @param properties PGVector 配置，必须显式指定正数维度且禁止启动删表
     */
    public PgKnowledgeIndex(PgVectorStore store, JdbcTemplate jdbc,
                            PlatformTransactionManager transactionManager, PgVectorStoreProperties properties) {
        this.store = store;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.table = identifier(properties.getSchemaName()) + "." + identifier(properties.getTableName());
        this.dimensions = properties.getDimensions();
        // 拒绝隐式维度和启动删表配置。但 store 注入前已完成 Bean 初始化，
        // 此处不能拦截 PgVectorStore 初始化阶段已经执行的删表；配置本身仍必须保持 false。
        if (dimensions <= 0 || properties.isRemoveExistingVectorStoreTable()) {
            throw new IllegalArgumentException("持久化知识索引要求显式 dimensions 且禁止启动时删表");
        }
    }

    /** 返回配置维度，供 KnowledgeLoader 将维度变化纳入索引指纹。 */
    public int dimensions() {
        return dimensions;
    }

    /**
     * 同步当前知识库的完整快照，而非仅同步一个新增/修改文档列表。
     * 输入中不存在的受管旧块会被删除；空列表意味着清空当前 OWNER 的受管块。
     * 调用方应提供稳定且不重复的文档 ID，并避免与同表其他数据的 ID 冲突。
     *
     * <p>指纹由上层计算，包含模型身份、维度、切分配置等信息。指纹变化会令相关块
     * 重新生成向量；正文、业务元数据变化或数据库向量缺失也会触发重新生成。
     * updatedAt 由本类管理，调用方的输入元数据不应包含这个字段。</p>
     *
     * @param documents 本轮完整的知识块列表，元数据由上层准备
     * @param fingerprint 本轮索引配置的指纹，并非数据库账号或单个块的 ID
     * @return 成功事务的块数、新生成数、复用数和删除数；加入外层事务时仍需等待外层提交
     * @throws IllegalStateException 向量生成或写入失败，附带来源、小节和块 ID
     */
    public Result synchronize(List<Document> documents, String fingerprint) {
        // execute 返回回调结果；requireNonNull 明确本方法不允许返回空结果。
        return Objects.requireNonNull(transaction.execute(status -> {
            // 将“表 + 逻辑归属”散列成 PostgreSQL 的 bigint 锁键。
            // 遵循同一锁协议的同步任务会串行执行；提交/回滚时锁自动释放。
            // 咨询锁不强制阻止未获取此锁的其他 SQL 写入，不等同于行锁或权限控制。
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                    table + ":" + OWNER);
            // 只读取本知识库负责的旧块，不需要把完整向量传回 Java，只检查向量是否非空。
            // existing 同时充当待删除集合：遍历本轮文档时移除仍然存在的 ID。
            Map<String, Existing> existing = new HashMap<>();
            jdbc.query("SELECT id, content, metadata::text, embedding IS NOT NULL AS has_embedding FROM "
                            + table + " WHERE metadata->>'indexOwner' = ?",
                    rs -> {
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        // JDBC 返回 JSON 文本，保留为 JsonNode 值，稍后按 JSON 结构比较。
                        mapper.readTree(rs.getString(3)).properties()
                                .forEach(entry -> metadata.put(entry.getKey(), entry.getValue()));
                        existing.put(rs.getString(1), new Existing(rs.getString(2), metadata,
                                rs.getBoolean(4)));
                    }, OWNER);
            int embedded = 0;
            for (Document document : documents) {
                // 复制元数据，避免向调用方传入的 Document 写入管理字段。
                Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
                metadata.put("indexOwner", OWNER);
                metadata.put("indexFingerprint", fingerprint);
                Existing previous = existing.remove(document.getId());
                // 使用 JSON 树比较，避免 JDBC JSON 与 Java Map 的类型表示差异造成误判。
                // 复用必须同时满足：旧行存在、有向量、正文一致、有写入时间、其余元数据一致。
                // 指纹已放入 metadata，因此模型/切分配置变化也会使该条件不成立。
                // updatedAt 是写入结果而非变化原因：排除旧时间，否则每次启动都会重新生成。
                JsonNode desired = mapper.valueToTree(metadata);
                boolean unchanged = previous != null && previous.hasEmbedding()
                        && Objects.equals(document.getText(), previous.content())
                        && previous.metadata().containsKey("updatedAt")
                        && desired.equals(mapper.valueToTree(withoutUpdatedAt(previous.metadata())));
                if (unchanged) {
                    // 保留原向量及原 updatedAt；仅查询文本在后续检索时仍需生成查询向量。
                    continue;
                }
                // 记录本次写入尝试的 UTC 时间；只有事务提交后该时间才成为持久化状态。
                metadata.put("updatedAt", Instant.now().toString());
                Document indexed = Document.builder().id(document.getId()).text(document.getText())
                        .metadata(metadata).build();
                try {
                    // PgVectorStore.add 会调用 Embedding，并按文档 ID 执行 upsert。
                    // 当前逐块写入便于定位失败块，但也意味着模型请求没有在此处跨块合批。
                    store.add(List.of(indexed));
                } catch (RuntimeException exception) {
                    // 保留原始异常链并向外抛出，让 TransactionTemplate 回滚本轮数据库修改。
                    throw new IllegalStateException("索引构建失败 source=" + metadata.get("source")
                            + " section=" + metadata.get("section") + " chunkId=" + indexed.getId()
                            + "；请检查模型维度与切分预算，禁止开启静默截断", exception);
                }
                embedded++;
            }
            // 剩余 ID 来自前面的 OWNER 查询，且未出现在本轮快照中，因此属于待清理旧块。
            // delete 按 ID 执行；该范围保证依赖同步期间没有绕过咨询锁的并发写入方改动归属。
            if (!existing.isEmpty()) {
                store.delete(List.copyOf(existing.keySet()));
            }
            // embedded 包括新增、变化与向量缺失的补写；reused 是无需重新生成向量的数量。
            return new Result(documents.size(), embedded, documents.size() - embedded, existing.size());
        }));
    }

    /** 返回移除写入时间后的副本，保留全部其他业务字段和管理字段用于比较。 */
    private static Map<String, Object> withoutUpdatedAt(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.remove("updatedAt");
        return copy;
    }

    /**
     * 校验并引用 SQL 标识符。JDBC 的 ? 只能绑定值，不能绑定 schema/table 名称，
     * 因而标识符必须在拼接 SQL 前单独校验；本实现仅接受小写字母、数字和下划线，
     * 且首字符不能为数字。有意不支持 PostgreSQL 所允许的全部带引号标识符形式。
     */
    private static String identifier(String name) {
        if (name == null || !name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("非法知识索引 schema/table 名称: " + name);
        }
        return "\"" + name + "\"";
    }

    /** 数据库旧块的比较快照；无需读取向量的具体数值。 */
    private record Existing(String content, Map<String, Object> metadata, boolean hasEmbedding) { }

    /**
     * 本轮同步统计，不包含检索时生成的查询向量。
     *
     * @param chunks 输入快照中的块数
     * @param embedded 本轮成功调用向量库写入的块数（包含新增及重新生成）
     * @param reused 未变化且复用旧向量的块数
     * @param deleted 从受管旧数据中清理的块数
     */
    public record Result(int chunks, int embedded, int reused, int deleted) { }
}

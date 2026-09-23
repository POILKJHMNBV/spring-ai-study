# Day11：持久化 PGVector RAG

## 推进判断

Day10 的固定证据链与失败处理已具备，可继续 Day11。本次基线发现唯一实际启动阻塞：
手写 `SimpleVectorStore` 与 PGVector starter 同时注册 `vectorStore`，导致上下文启动失败。
已移除手写 Bean，统一使用官方自动配置；同时修正 `spring.ai.vectorstore` 配置键。

## 实现与数据契约

- 复用用户提供的 PostgreSQL / pgvector 依赖与数据库连接，不升级框架，保持 Java 17。
- 使用 Spring AI 2.0.1 的 `PgVectorStore`，BGE-M3 显式配置 1024 维、HNSW、余弦距离。
- `id-type=TEXT` 保留既有 SHA-256 chunk ID；默认 UUID 不能接收该 ID。
- 启用 schema 初始化和校验，关闭启动删表。手工示例 `document_vector(VECTOR(3))` 不参与应用检索，应用使用 `vector_store`。
- `PgKnowledgeIndex` 从数据库读取已有行，比较正文、metadata 和索引指纹，跳过未变化文档的 Embedding。
- 指纹包含模型名称、Ollama digest、维度、切分器版本、预算及 metadata 版本；语料版本使用源文件 SHA-256。
- 新增和变化的文档重新 Embedding；缺失行补写，删除仅限本应用管理的过期块。
- 同步使用 PostgreSQL 事务和事务级 advisory lock。导入中途失败时回滚整轮修改，启动失败，不发布成功清单。
- 本地 `target/rag-index/manifest.json` 只作诊断，删除后仍能从数据库复用向量。
- 保留原切分文本、块 ID、候选检索和 RRF 排序逻辑。

| metadata | 含义 |
|---|---|
| source / title | 知识文件名 / Markdown 一级标题 |
| category | 现有 domain：mysql、kafka、thread-pool |
| version | 源文件内容 SHA-256 |
| updatedAt | 本块向量最近成功写入的 UTC 时间；复用时保持不变 |
| service | 当前通用故障指南统一为 shared，不冒充服务的实时指标 |

`section`、`domain`、`chunkIndex`、`contentHash`、`splitterVersion` 保留。
`indexOwner` 和 `indexFingerprint` 用于管理持久化索引。

## 复现

前提：用户配置的学习数据库可连接，已安装并启用 pgvector / hstore；本机 Ollama 已安装 BGE-M3。
测试自动创建随机 `day11_it_<uuid>` schema，测试结束只清理该 schema，不依赖手工测试表或旧数据。

```powershell
# 本机 Maven / JDK 17，默认单元与装配测试不连接数据库和模型
mvn clean test

# 真实虚拟机 + 真实 BGE-M3：数据库能力、重启复用、metadata filter、事务一致性
mvn test '-Dtest=Day11PgVectorIT'

# 既有真实模型回归；按应用配置在学习数据库保留知识向量
mvn test '-Dtest=Day10DependencyIT,AgentEvaluationIT' '-Dspring.ai.mcp.client.enabled=false'

# 只启动应用；local profile 不依赖独立 Kafka MCP Server
mvn spring-boot:run '-Dspring-boot.run.profiles=local'
```

metadata filter 使用官方 `SearchRequest` 语法，例如：

```java
SearchRequest.builder()
        .query("数据库连接池接近满")
        .topK(3)
        .similarityThreshold(0)
        .filterExpression("category == 'mysql' && service == 'shared'")
        .build();
```

## 实测证据

- 本机 Apache Maven 3.8.4，Oracle JDK 17.0.11，生成 class major version 61。
- PostgreSQL 15.19；vector 0.7.2、hstore 1.8、uuid-ossp 1.1。
- 验证了数据库认证、扩展、临时向量表写入、HNSW 创建及余弦距离查询。
- 3 个真实知识文件，共 23 个块；首次启动写入 23 个向量。
- 销毁整个 Spring 应用上下文与连接池、删除本地清单后，重新启动相同应用：23 行保留，`embedded=0`、`reused=23`。
- Spy 验证重启启动阶段既没有文档 Embedding，也没有为推断维度调用字符串 Embedding；查询仍正常调用查询文本的 Embedding。
- ID 与 metadata 在重启前后完全一致。正向过滤返回 mysql 文档，非匹配 service 过滤返回空列表。
- 实际 `KnowledgeRetriever` 查询“数据库连接池接近满”命中 `mysql-slow-query.md`。

## 编译与测试结果

| 命令 / 测试 | 结果 |
|---|---|
| 初始 `mvn test` | 59 项，1 个启动错误，2 项跳过；原因是 vectorStore Bean 重名，已修复 |
| 修复后 `mvn clean test` | BUILD SUCCESS；59 项，0 失败/错误，2 项默认跳过的独立 RAG 评测 |
| `Day11PgVectorIT` | 3 项通过：数据库与向量能力、重启与过滤、增删/模型指纹变化/缺失补写/失败回滚 |
| `Day10DependencyIT` | 2 项通过，覆盖无日志 DB/RPC 证据与依赖数据缺失 |
| `AgentEvaluationIT` | 1 项失败：主要判断 10/10，综合通过 8/10；E09 实时数值审计失败 |

联合运行 `Day11PgVectorIT,Day10DependencyIT,AgentEvaluationIT` 共 6 个测试方法，
1 个失败，因此该命令结果是 **BUILD FAILURE**，不能表述为全量回归通过。

E09 当前轮仅调用 `getServiceStatus`，回答又引用历史轮的 Kafka Lag=125000、
produceRate=5000、consumeRate=2800，违反当前轮实时数值必须可追溯的原有门槛。
这不是数值正则误报，保留失败证据与测试断言，不降低标准。
E05 主要判断正确，但漏调用案例期望的日志工具，工具选择评分未通过。
本次未修改 Prompt、Memory、Harness 或旧 Eval 评分规则，也未重复抽样挑选通过结果。
Day11 的持久化验收通过，完整 Agent 的多轮证据新鲜度问题仍未解决。

详细日志位于 `target/day11-build.log`、`target/day11-integration.log`；
模型回答与评分位于 `target/eval-results/day6-eval.json`、
`day10-without-logs.json`、`day10-missing.json`。`target` 在 `mvn clean` 后会删除。

## 边界与当天结论

重启验证在同一测试 JVM 中关闭并重建整个应用上下文和数据库连接池，没有重启 PostgreSQL 服务。
数据库持久化消除了应用重启时重复生成全部文档向量的问题；查询文本依然需要 Embedding。
启动仍查询 Ollama `/api/tags` 核对模型 digest，因此 Ollama 停机时不会声称索引已完成校验。
数据库不可达、维度不匹配、Embedding 失败会明确阻止启动，不静默退回空内存库。

此实现适合当前小规模知识库，启动同步会读取全部受管行并在事务内调用 Embedding。
大规模导入、在线无停机切换、外部写入保护与细粒度权限不属于本次验收范围。
更换模型的向量维度需要规划表迁移，不能通过启动删表自动抹除旧数据。
本次 metadata filter 验证数据库过滤能力，不将其包装成已实现多租户授权系统。

SimpleVectorStore 适合学习和小型测试。它可以显式保存/加载文件，但不提供数据库级事务、
并发持久化、共享查询及 HNSW 索引能力；原项目也没有加载历史向量，每次启动都重算。
PGVector 属于 Data / Infrastructure 层，持久化、事务和可重复检索实验是本次收益证据。
Recall@K、MRR 与 Reranker 的完整评估仍属于 Day12。

## 官方依据

实现前核对官方参考文档，并对照本机实际使用的 Spring AI 2.0.1 官方源码与 JAR 签名：

- [Spring AI PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：starter、schema、dimensions、HNSW、metadata filter。
- [Spring AI 2.0.1 PgVectorStore 官方源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java)：`PgIdType.TEXT`、Builder、事务中使用的 JdbcTemplate 写入与删除。
- [pgvector 官方文档](https://github.com/pgvector/pgvector)：vector 类型、余弦运算符 `<=>`、`vector_cosine_ops` 与 HNSW。
- [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)：JdbcTemplate 查询与参数绑定。
- [Spring 程序化事务](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)：TransactionTemplate。
- [PostgreSQL 15 advisory locks](https://www.postgresql.org/docs/15/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS)：事务级锁随提交/回滚释放。

本次不执行 commit 或 push；保留原有未提交改动。

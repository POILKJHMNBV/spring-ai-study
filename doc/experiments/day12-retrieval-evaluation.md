# Day12：独立检索评测与重排

## 推进判断与任务清单

Day11 无严重阻塞，可以推进 Day12。修改前使用本机 Maven 3.8.4 / Oracle JDK 17.0.11
重跑 `Day11PgVectorIT`，3 项通过，连接虚拟机 PostgreSQL 15.19 / pgvector 0.7.2。
验证覆盖持久化重启、metadata filter 与事务一致性。

本次由两位 GPT-6 Sol、medium 推理子 Agent 分别负责生产检索链路和评测代码，主 Agent 负责审查、
真实环境编译测试与结果评估。任务清单：

- 复用现有向量 Top20 候选及 BM25/RRF，分离召回、排序与 Top3 上下文截取。
- 增加独立 `DocumentReranker` 和可配置的三种检索模式。
- 冻结独立查询集，覆盖计划四例、同义表达、技术词与跨组件多来源问题。
- 实现 source 级 Recall@1、Recall@3、MRR@20，不以生成模型的最终回答评分。
- 同一真实 PGVector 候选对照 Vector、Vector + RRF、Vector + RRF + Rerank。
- 记录逐例排序、指标和延迟，依据数据决定默认模式，保留无收益和退化结果。
- 补充排序及指标契约测试，在虚拟机隔离 schema 运行真实集成测试。
- 以 JDK 17 编译、补充中文注释与官方依据，不执行 commit 或 push。

## 官方依据与实现边界

2026-09-24 实际读取以下官方文档，并用本机 Spring AI 2.0.1 官方 JAR 的 `javap` 输出
核对 `SearchRequest.Builder` 的 `query`、`topK`、`similarityThreshold` 与过滤 API：

- [Spring AI RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)：
  文档后处理阶段可以根据查询相关性重排、移除无关或冗余文档。这里遵循学习计划定义项目自己的
  `DocumentReranker`，继续使用手写检索链路，并未声称调用框架内置的重排模型。
- [Spring AI PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：
  使用官方 `VectorStore.similaritySearch(SearchRequest)` 及 PGVector 配置。
- [JDK 17 List.sort](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html#sort(java.util.Comparator))：
  稳定排序保留重排同分候选的原相对顺序。
- [PostgreSQL schema](https://www.postgresql.org/docs/15/ddl-schemas.html)：
  集成测试使用本次生成的独立 schema，测试结束清理自己的对象。
- [Spring Boot 配置绑定](https://docs.spring.io/spring-boot/reference/features/external-config.html)：
  record 存在多个构造器时，用 `@ConstructorBinding` 指定配置绑定构造器。

词项覆盖重排是本项目定义的确定性启发式，不是 Spring AI 内置算法或神经 Cross Encoder。
它不具备独立语义推理能力，不能找回向量 Top20 之外的文档，也不能替代答案正确性评测。
返回 DTO 的 `score` 始终是原始向量相似度，不是重排分或诊断置信度。

## 检索与指标契约

`KnowledgeRetriever.searchCandidates` 每次查询只执行一次真实向量搜索，固定候选数 20、
召回阈值 0；`rankCandidates` 复用该列表分别执行三种模式，不修改输入。
HYBRID 先在完整候选内执行原 BM25/RRF，再应用原始向量阈值，避免改变阈值时影响
BM25 文档频率。HYBRID_RERANK 在此基础上重排，最后按业务 topK 截取。

自定义重排器的查询词为中文相邻双字及英文完整词。评分为标题/小节查询词覆盖率的 40%，
加正文最佳片段查询词覆盖率的 60%；正文按句/行切分，最长窗口 160 个 Unicode 码点，
步长 80。每个候选只计算一次分数，同分沿用 RRF 顺序。权重和窗口在评测前固定，
没有按验证集改写算法或添加 source 答案表。

18 条固定查询分为 9 条 dev 与 9 条 validation，其中包含学习计划的四个指定查询。
validation 是预先固定的回归集，部分查询与已有语料/早期实验相近，不宣称为全新盲测。
相关性标签在 source 级；一个查询可以有多个相关来源。

- Recall@K：先截取前 K 个 **chunk**，再统计其中不同的相关 source 数，除以该题全部标注来源数。
  不先对 source 去重扩充可用位置，多个同来源块会占用上下文预算。
- MRR@20：前 20 个 chunk 中首个相关 source 的原始名次倒数，未命中记 0，再对查询取平均。
- 延迟：每题预热后采样三次，分别记录共享候选检索与各模式排序开销。
  A/B/C 共用候选耗时；顺序执行的小样本结果不等于独立生产压测。

同时记录阈值 0 的排序诊断和阈值 0.47 的生产配置对照。无强制 C 胜出的断言，
也不根据验证集调权重、topK 或阈值。source 级指标不能证明所返回小节包含回答所需的全部事实。

## 复现

前提与 Day11 一致：已配置的虚拟机 PostgreSQL + pgvector 可达，本机 Ollama 已有 BGE-M3。
测试创建随机 `day12_eval_<uuid>` schema，导入真实知识语料，结束后只清理本次 schema。

```powershell
# 本机 Maven 编译和默认单元/装配回归
mvn test

# Day11 持久化回归 + Day12 真实检索对照，不调用生成模型
mvn test '-Dtest=Day11PgVectorIT,RetrievalEvaluationIT'
```

当前运行产物在 `target/day12/`，逐例 TSV 可以独立复核 Recall 与 MRR。
如需试用重排，使用 `--rag.retrieval.mode=HYBRID_RERANK`；三种模式为
`VECTOR`、`HYBRID`、`HYBRID_RERANK`，原调用方与调试接口保留兼容。

## 实测结果

2026-09-24，由主 Agent 使用本机 Maven 3.8.4 / Oracle JDK 17.0.11 执行：

| 验证 | 结果 |
|---|---|
| `mvn -DskipTests compile` | BUILD SUCCESS，javac release 17；新增重排类 class major version 61 |
| `mvn test` | BUILD SUCCESS：70 项，68 通过、0 失败、0 错误、2 跳过 |
| `mvn test '-Dtest=Day11PgVectorIT,RetrievalEvaluationIT'` | BUILD SUCCESS：4 项通过，0 失败/错误/跳过 |
| 主 Agent 独立重算 TSV | 108 行（18 查询 × 3 模式 × 2 阈值）Recall@1/@3、MRR 全部一致 |
| `git diff --check` | 通过 |

默认测试跳过的是既有 `RagEmbeddingEvaluationTest` 与 `RagQualityEvaluationTest`，它们要求
显式 `-Drag.eval=true`。本次 Day12 的真实 PGVector 检索评测单独运行并通过，未用跳过项代替验收。
未运行生成模型的全量 Agent Eval；本次验收限于检索、配置装配与既有默认回归。

数据库为 PostgreSQL 15.19 / pgvector 0.7.2，真实 BGE-M3 1024 维，3 篇知识文档共 23 个块。
数据集、语料和模型 digest 见[完整实测汇总](day12-retrieval-summary.md)，
[逐例证据](day12-retrieval-results.tsv)保留完整 chunk 顺序。
这些是当前运行结果的持久副本，重复运行会更新 `target/day12/`，不会自动覆盖文档内的历史证据。

生产阈值 0.47 的结果如下；本次阈值 0 的质量指标相同，全部对照均保留在完整汇总中。
延迟是每题三次中位数再对组内查询取平均，共享召回耗时加各模式排序耗时。

| 分组 | 模式 | Recall@1 | Recall@3 | MRR@20 | 总耗时 ms |
|---|---|---:|---:|---:|---:|
| dev | A：纯向量 | 0.8333 | 0.8889 | 1.0000 | 25.7371 |
| dev | B：向量 + RRF | 0.8333 | 0.8889 | 1.0000 | 26.1143 |
| dev | C：向量 + RRF + 重排 | 0.8333 | 0.9444 | 1.0000 | 26.4955 |
| validation | A：纯向量 | 0.8333 | 0.9444 | 0.9444 | 24.9809 |
| validation | B：向量 + RRF | 0.8333 | 0.9444 | 0.9444 | 25.3056 |
| validation | C：向量 + RRF + 重排 | 0.8889 | 0.8889 | 1.0000 | 25.5987 |

四条计划查询在 A/B/C 中均首位命中预期来源。关键取舍：

- D09「慢 SQL 长时间占用连接导致业务线程阻塞」：C 相对 B 的 Recall@3 从 0.5 升为 1.0。
- V09「数据库响应变慢后 Kafka 消费能力下降」：C 将首个相关来源从第 2 位提升到第 1 位，
  但前三块只覆盖两个标注来源中的一个，Recall@3 从 1.0 降为 0.5。
- 因此重排存在可量化的首位相关性收益，同时存在跨组件来源覆盖退化；不能宣称整体改善。
  保留默认 `HYBRID`、Top3、阈值 0.47，`HYBRID_RERANK` 仅作为可显式选择的实验模式。
  本次也未证明 A/B 在此小数据集上有质量差异，不因小样本等分移除既有 RRF。

## 子 Agent 完成情况评估

主 Agent 评估：**两项子任务经修正后通过，Day12 学习验收完成**。

| 子任务（均 GPT-6 Sol / medium） | 审查与验证 | 结论 |
|---|---|---|
| 生产检索与重排 | 保留同一候选、原始向量分、来源校验与稳定排序；补充模式配置、阈值与 topK 契约测试；JDK 17 编译和 Spring 装配通过 | 通过；重排收益有取舍，默认关闭 |
| 检索数据集与 Eval | 18 例、两阈值、真实 PGVector、source 去重口径正确；隔离 schema 清理；主 Agent 独立复核 108 行指标 | 通过；结果只代表当前小样本 source 级评测 |

审查过程中修复了 RRF 空正文候选下标错位，以及新增指标校验对不可变 Set 调用
`contains(null)` 的问题。首次完整 Maven 回归发现多构造器 record 配置绑定错误，
依据官方文档增加 `@ConstructorBinding` 并补绑定测试后，完整回归和真实数据库测试均通过。
本次新增与修改的类、接口、枚举、方法和关键逻辑已补中文注释；没有新增依赖或升级框架。
未执行 commit 或 push。

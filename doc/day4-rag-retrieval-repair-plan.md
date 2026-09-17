# Day 4：Embedding 检索修复方案与实测报告

验证日期：2026-09-16。本轮仅新增验证测试与本文，不修改业务代码、配置和知识文档，不执行 commit / push。

## 1. 结论与最小修复

**优先将 Embedding 从 `mxbai-embed-large` 切换为本地 `bge-m3`，并完整重建索引。保持当前三篇 Markdown、按小节切分和 SimpleVectorStore 不变，即已在真实本机实验中满足六条 Rank1 领域要求。**

不需要先引入新的向量数据库、LLM 查询改写、关键词强制路由或 reranker。实验没有依据查询内容过滤 domain，也没有把六条期望结果写入检索逻辑。

本轮结果证明指定验收集可通过，不代表任意中文问题都能正确检索。另测的十二条表达中 BGE-M3 命中十一条；纯异常类名的正确匹配分数偏低，现有 0.50 阈值还需单独评估。

## 2. 已核实的当前链路

```text
KnowledgeLoader.run
  → MarkdownSectionSplitter.split
  → 每个 H2 小节添加文档标题和小节标题
  → TokenTextSplitter（chunkSize=220）
  → SimpleVectorStore.add
  → OllamaEmbeddingModel

RagDebugController /rag/search
  → KnowledgeRetriever.retrieve(query, topK, threshold)
  → SimpleVectorStore.similaritySearch
  → RetrievedChunk
```

关键事实：

- 当前 `application.yaml` 配置的是 `spring.ai.ollama.embedding.model: mxbai-embed-large`。
- `KnowledgeLoader` 中另一套 400-token 切分器和 `load(Resource)` 没有被 `run` 调用，修改这些参数不会影响当前索引。
- 当前实际为 **3 篇文档、21 个 chunk，每篇 7 个**。长度 157～334 个 Java 字符，每个 chunk 都保留了 `# 文档标题`，当前没有观察到二次切分后标题丢失。
- `chunkSize=220` 是切分器的 token 数，并非 220 个中文字符，也不是 Ollama 模型自身 tokenizer 的计数。
- 元数据只有 source/title/section/chunkIndex，没有 domain；返回 DTO 也没有 domain。本文和测试只为统计将 source 映射到 domain。
- 调试接口默认 `threshold=0.0`；`KnowledgeRetriever.retrieve(query)` 默认阈值为 `0.50`。调用路径不同，可能表现为“调试查得到，业务查不到”。
- 每次启动创建新的内存 SimpleVectorStore，并由 Loader 导入文档；当前没有加载持久化向量文件的代码。

## 3. 原因判断：哪些已经证实

### 3.1 主要因素：当前模型在这组中文材料上的区分效果不足

固定知识库、切块、向量库和查询，只更换模型即可把指定集从 2/6 提升到 6/6。这是本轮最直接的因果对照证据。

原有六个 JSON 的首位都属于线程池文档；真实重跑 mxbai 的首位来源、chunk 和分数与这些 JSON 一致（按报告精度对比）。因此已复现用户现象，不只是离线阅读推测。

另做了 mxbai 查询前缀对照：仅 query 添加 `Represent this sentence for searching relevant passages: `，文档不加前缀。指定集仍为 2/6，说明仅补这个前缀不能解决当前问题。BGE-M3 本次使用原始中文 query，没有添加该前缀。

这并不能证明 mxbai 对所有中文语料都不可用，也没有进一步证明 tokenizer 内部机制；结论限定为本地模型版本和本项目语料。

### 3.2 内容存在领域交叉，会影响 chunk 排序

线程池第 5 节讨论 Kafka 消费，因此与“Kafka 消费积压”语义相关。旧模型将这段排在 Kafka 主文档之前，旧分数为 0.768891。向量相似不等于“该文档是问题的主领域”，更不等于已经确定根因。

BGE-M3 下“连接池快满了”首位为 MySQL，但第 2、3 位仍是线程池文档。这些关联知识未必错误，不能为了纯净的 domain 排名全部删除。

### 3.3 本轮未发现截断是当前故障原因

两种模型均用 `truncate=false` 完整嵌入 21 个 chunk 成功，且 mxbai 重现原结果。说明当前材料不需要依靠静默截断才能完成本次请求。

本机模型信息：mxbai 的 context_length 为 512，BGE-M3 为 8192，两者 embedding_length 均为 1024。上下文长度仍需针对实际模型 tokenizer 管理；不能把切分器 token 数直接当作模型输入预算。

### 3.4 调大 topK 或修改阈值无法修复错误的 Rank1

topK 增大只是返回更多候选。相似度阈值只会剔除低分候选，无法让排在前面的错误领域结果自动降到正确结果之后。旧模型甚至把无关问题打到 0.53～0.56，单纯提高阈值也难以解决其相关性问题。

## 4. 真实实验结果

环境：本机 Maven 3.8.4、Oracle JDK 17.0.11、Spring Boot 4.1.1、Spring AI 2.0.1、Ollama `localhost:11434`。

模型标识：

- `bge-m3:latest`，digest `7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`。
- `mxbai-embed-large:latest`，digest `468836162de7f81e041c43663fedbbba921dcea9b9fefea135685a39b2d83dd8`。

所有实验使用原文、当前业务切分器和 Spring AI SimpleVectorStore；每个模型单独建立内存索引；topK=3、threshold=0.0、truncate=false。

| 实验 | 指定 6 条 Rank1 | 补充 12 条 Rank1 |
|---|---:|---:|
| mxbai，原始 query | 2/6 | 8/12 |
| mxbai，仅 query 加检索前缀 | 2/6 | 7/12 |
| BGE-M3，原始 query | **6/6** | **11/12** |

| 查询 | 旧模型 Rank1 / 分数 | BGE-M3 Rank1 / 分数 | BGE chunkIndex |
|---|---|---|---:|
| 线程池打满 | thread-pool / 0.466725 | **thread-pool / 0.729388** | 6 |
| 执行器没有空闲线程 | thread-pool / 0.657015 | **thread-pool / 0.643767** | 0 |
| Kafka 消费积压 | thread-pool / 0.768891 | **kafka / 0.739122** | 2 |
| 生产速度高于消费速度 | thread-pool / 0.615859 | **kafka / 0.615965** | 5 |
| 数据库查询越来越慢 | thread-pool / 0.440012 | **mysql / 0.767528** | 0 |
| 连接池快满了 | thread-pool / 0.453737 | **mysql / 0.654592** | 1 |

补充集包含：工作线程排队、执行器拒绝任务、线程池队列增长、RejectedExecutionException、消息收支失衡、消费者追不上生产者、lag 上涨、分区并行度、SQL 耗时、HikariCP 等待、连接用尽、SQL 索引与锁。

唯一的 BGE 补充集错误为“消息进来的比处理掉的多”：期望 kafka，实际首位 mysql，0.554698，前三位中没有 kafka 主文档。这句话没有显式组件背景，属于应在后续扩充上下文与语料时处理的泛化案例，不能声称已经修复。

### 阈值的具体证据

| BGE-M3 查询 | Rank1 分数 | 解释 |
|---|---:|---|
| RejectedExecutionException | 0.477645 | 正确线程池文档，0.50 会漏掉 |
| Kubernetes 证书过期如何更新 | 0.464681 | 知识库没有对应手册 |
| 今天天气怎么样 | 0.375402 | 无关 |
| 如何烤一块巧克力蛋糕 | 0.337354 | 无关 |

指定六条的分数均大于 0.61，因此 0.50 可以通过这六条，但不能据此宣称该阈值适用于全部查询。0.477645 与 0.464681 间隔很窄，不建议仅凭四个样例把阈值设成 0.47 并宣布解决拒答问题。

## 5. 下一轮落地步骤与文件清单

### P0：完成六条最低验收，先做最小变更

1. 修改 `src/main/resources/application.yaml` 的 embedding 配置。按本项目 Spring AI 2.0.1 的配置属性，model/truncate 位于 embedding 直属层级：

   ```yaml
   spring:
     ai:
       ollama:
         embedding:
           model: bge-m3
           truncate: false
   ```

   聊天模型维持现有配置。保留原始中文 query，不添加 mxbai 的英文前缀。

2. 重启应用完成全量重建。虽然两个模型均输出 1024 维，向量空间并不相同，必须同时更换文档和查询的模型，禁止复用旧向量。如果以后启用持久化，校验模型 digest、语料 SHA-256 和切分版本，不匹配就重建。

3. 首次验证保持现有 21 个 chunk，不同时调整切块或改写知识内容，以便归因。启动后核对文件数、chunk 数和实际模型名称。

4. 使用 `/rag/search?query=<URL编码查询>&topK=3&threshold=0.0` 跑六条排序验收，再用 threshold=0.50 验证这六条仍可返回。保存新结果，记录模型和配置；旧 JSON 作为基线另存，不覆盖后失去比较依据。

5. 为 `MarkdownSectionSplitter` 的每个最终 chunk 添加稳定的 `domain` 元数据；暂时用显式 source 映射，未知 source 应报错或显式标为 unknown，不能默认为某个领域：

   | source | domain |
   |---|---|
   | thread-pool-exhaustion.md | thread-pool |
   | kafka-consumer-lag.md | kafka |
   | mysql-slow-query.md | mysql |

6. 在 `RetrievedChunk` 和 `KnowledgeRetriever.toChunk` 中暴露 domain，建议同时暴露 section。元数据用于展示、评测、来源追溯；**仅添加 domain 不会自动改善向量排序**。本轮验证无需它参与检索。

7. 为调试接口补 query 非空、topK 范围和 threshold 范围校验，使用同一配置来源管理默认值。诊断用 threshold=0.0 必须明确传入；面向回答的路径采用经校准阈值。

P0 验收：六条在未加 domain filter 的全库检索中 Rank1 全对；source 和 chunk 正确；应用启动无截断错误；没有混用旧模型向量。接入 HTTP 和 DTO 的变更需下一轮做端到端验证，本轮已验证的是相同核心切分与向量检索链路。

### P1：提高泛化能力、片段质量和拒答可靠性

1. 扩充评测集到至少 30～50 条，分别覆盖中文口语、异常类名、监控指标、跨领域因果问题、无关问题和知识库外故障。将本轮补充集视为已观察样本，另外保留未用于调参的测试集。
2. 指标除领域 Hit@1 外增加正确片段 Recall@3、MRR、无结果比例和无关问题误召回率。领域正确不意味着片段就足以支持回答，例如“线程池打满”目前首位是处理原则，并非症状定义。
3. 在开发集扫描阈值，记录相关召回与无关误召回的权衡，再用保留集验收。当前 0.50 可作为六条最低验收的临时值；不能作为通用相关性保证。
4. 为 Kafka 的速率关系小节补充自然的中文解释和常见表述，覆盖“消息进入速度超过处理速度”等概念；为指标增加中文含义。独立比较变更前后，避免机械堆砌测试查询。
5. 保留跨领域因果关系段落，必要时标记主领域与关联领域。不要把“Kafka 消费积压”硬编码为过滤 kafka，因为“线程池打满导致 Kafka 积压”的问题仍需线程池知识。
6. 若异常类名、指标名召回仍不稳定，再增设关键词或 BM25 分支。候选与向量结果用 RRF 等方法融合，不直接相加不同量纲分数；关键词命中也不能绕过来源验证。使用冻结测试集验证收益后再引入，不是本轮六条达标的前置条件。
7. 当知识库扩大且候选排序仍有问题时，再考虑候选 topK=10～20 + reranker + 最终 topK=3。本轮没有验证混合检索或 reranker 的效果，不将其计为已完成修复。

### P2：维护性与索引诊断

- 清理 `KnowledgeLoader` 未使用的旧切分器和读取方法，避免以后调整错位置。
- 对将来超过切分预算的小节，在二次切分后补回标题/小节路径，并把新增前缀算入输入长度；当前 21 块均有标题，不需要为这个潜在问题立即重构。
- 为索引记录模型名和 digest、向量维度、语料 hash、切分参数及版本、chunk 数、构建时间。来源 + 小节 + 内容 hash 可作为稳定 chunk 标识，用于后续去重与更新。
- `truncate=false` 失败时报告具体 source/section，针对该段重新切分；不要自动恢复静默截断后继续宣称完整索引。
- 记录 query、topK、threshold、命中来源、section、domain、分数和耗时。分数是相似度，不是根因概率。

## 6. 已交付的可重复测试

测试文件：`src/test/java/org/example/ai/rag/RagEmbeddingEvaluationTest.java`。

```powershell
mvn test "-Dtest=RagEmbeddingEvaluationTest" "-Drag.eval=true"
```

说明：

- 使用本机 Maven/JDK，无需调整 pom 或安装其他 embedding 模型。
- 普通测试运行时该实验默认跳过；显式 `rag.eval=true` 时访问本机 Ollama。
- 不启动 Spring Boot 上下文，不调用聊天模型；复用真实 MarkdownSectionSplitter、OllamaEmbeddingModel 和 SimpleVectorStore。
- 所有知识文档只读，每个模型使用独立内存索引，中文注释解释实验边界。
- 断言 BGE-M3 六条验收必须全部通过；其他模型作为对照，十二条补充与三条负样本用于诊断，不伪装为全部通过的断言。
- 输出 `target/rag-eval/results.tsv`：每个实验、每条查询的前三名来源、chunk、分数；`manifest.tsv`：语料 SHA-256；`chunks.tsv`：切分统计。共 3 个实验 × 21 条查询 × 3 个结果 = 189 行结果。
- 测试成功：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，Maven `BUILD SUCCESS`。这里的 1 指一个 JUnit 实验方法，内部执行上述查询矩阵。
- 首次运行缺少 Surefire JUnit provider，已用本机 Maven 下载缓存后成功运行；未修改 Maven 配置、pom 或 Java 版本。

本轮未启动新应用实例验证 HTTP 端点，未测试最终聊天回答，也未修改任何已有应用文件。Day 4 的“将检索片段注入上下文并正确引用”仍需与检索排名验收分开完成。

## 7. 后续修复落地记录（2026-09-16）

以上第 1～6 节保留为修复前的方案与实验记录。后续实现、最终配置和编译验收见 [修复交付记录](day4-rag-repair-summary.md)，72 条评测及局限见 [修复评测报告](day4-rag-evaluation-report.md)。

P0 最低排序验收、P1 评测/阈值校准/语料补充/候选融合、P2 索引维护与诊断已落地。最终 `mvn clean verify -Drag.eval=true` 通过，14 个 JUnit 方法无失败/错误/跳过，98 次真实 HTTP 断言通过。库外问题仍存在误召回，可靠拒答不能据此宣称完成；报告保留全部失败样本与指标退化。未执行 commit/push。

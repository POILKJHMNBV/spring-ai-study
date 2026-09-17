# Day 4 RAG 修复交付与验证记录

验证日期：2026-09-16。继续工作区已有修改，未执行 commit、push，也未覆盖原始六份 JSON。

## 完成的实现

|阶段|落地内容|证据与边界|
|---|---|---|
|P0|BGE-M3、truncate=false；启动全量重建；source/domain/section/chunkId 追溯；统一检索默认值；参数校验|原始语料 21 块对照保持不变，六条 Rank1 全对；实际 HTTP 在 threshold=0 和 0.50 下通过|
|P1|72 条分组评测；Hit@1、相关小节 Recall@3、MRR@20、无结果率及库外误召回率；开发集阈值扫描；自然中文语料补充；BM25/RRF 候选排序|默认阈值 0.47；保留集领域 18/18、追加集 12/12；库外拒答仍不可靠，详见下文|
|P2|清理旧 Loader 切分逻辑；二次切分保留标题并计入 220 token 预算；稳定内容标识；索引清单；失败定位；查询及命中日志|最终 3 篇、23 块、1024 维；超长/嵌入失败中止启动并报告 source/section；失败启动删除旧成功清单|

核心类与关键步骤均有中文注释。聊天模型保持原配置，查询未添加英文前缀，没有根据问题硬编码 domain 过滤。

最终 23 块是补充语料与严格预算切分后的结果。回归测试验证原始三篇文档在新旧切分器下均为 21 块且逐块文本完全相同；没有把 P1 的语料改动混入 P0 模型对照。

BM25 只在向量前 20 个候选内计算中文双字及英文完整术语的词法排名，使用 RRF 融合名次，再应用向量阈值和 topK。它不是独立的全库 BM25 索引，也不是神经 reranker。所有候选必须通过来源与领域一致性校验。DTO 的 score 仍是原始向量相似度，不是融合分数或根因概率，因此排序不要求 score 递减。

## 编译和测试

本机 Maven 3.8.4、JDK 17、Spring Boot 4.1.1、Spring AI 2.0.1、真实 Ollama。

```powershell
mvn clean verify "-Drag.eval=true"
```

2026-09-16 23:17:33 +08:00 完成：**BUILD SUCCESS**，耗时 20.080 秒。

|测试类|JUnit 方法数|结果|
|---|---:|---|
|KnowledgeLoaderTest|3|通过：截断禁止、失败来源定位、清单及计数|
|RagRegressionTest|8|通过：长文本完整性、标题预算、稳定 ID、原始 21 块一致性、围栏、参数及来源校验|
|RagEmbeddingEvaluationTest|1|通过：真实模型对照矩阵|
|RagQualityEvaluationTest|1|通过：72 条质量评测及真实随机端口 HTTP|
|SpringAiStudyApplicationTests|1|通过：完整上下文启动与索引重建|
|合计|14|Failures=0、Errors=0、Skipped=0|

**98 次真实 HTTP 请求断言通过**：72 条默认检索、11 个非法参数用例、六条问题分别在 0/0.50 阈值下验收，以及 topK=1、topK=20、threshold=1 三个合法边界用例。逐条检查了状态码、命中数量、chunkId、domain、section 和分数。非法输入返回 400。

产物：`target/spring-ai-study-0.0.1-SNAPSHOT.jar`。
本地构建日志：`target/build-verification.log`；JUnit 明细：`target/surefire-reports/`；索引清单：`target/rag-index/manifest.json`。这些 target 文件可能被下一次 clean 删除，关键结论和原始候选证据已保存到 doc。

## 评测结果与限制

完整报告：[day4-rag-evaluation-report.md](day4-rag-evaluation-report.md)。
可重算指标的零阈值候选：[day4-rag-evaluation-results.tsv](day4-rag-evaluation-results.tsv)。

|组别|相关问题领域 Hit@1|库外误召回|
|---|---:|---:|
|指定验收|6/6|无负样本|
|开发集|18/18|0/6|
|原保留集|18/18|4/6|
|追加冻结集|12/12|3/6|

最初纯向量保留集为 16/18，原有至少 17/18 的断言失败；未降低门槛。加入通用 BM25/RRF 候选融合后通过。原保留集已经用于发现问题，因此后续结果只能算回归；算法确定后另冻结 18 条追加样本，未按追加结果调整参数、语料或标签。

领域正确不代表所有相关片段都进入前三名：融合后开发集 MRR 略降、追加集 Recall@3 略降，详细对照保留在报告中。0.47 仅是开发集预设扫描规则的结果；原保留集 4/6、追加集 3/6 库外问题仍误召回。**本次完成检索排序修复与校准评测，不将可靠库外拒答宣称为已解决。** 检索接口通过也不等于最终聊天回答及引用已经验收；本轮没有调用聊天回答接口。

索引始终为新建内存索引，manifest 仅用于诊断，不读取旧向量。未来若启用持久化，必须校验模型 digest、语料 hash 和切分版本，不匹配时重建。

## 复现

需要本机 Ollama 可用并已安装 `bge-m3` 和 `mxbai-embed-large`；应用运行本身仅需要 BGE-M3。

```powershell
# 完整编译、单元回归、真实模型及 HTTP 验收
mvn clean verify "-Drag.eval=true"

# 仅运行不依赖 Ollama 的切分、校验和 Loader 回归
mvn test "-Dtest=RagRegressionTest,KnowledgeLoaderTest"

# 重新生成 doc 下的质量报告与候选明细
mvn test "-Dtest=RagQualityEvaluationTest" "-Drag.eval=true"
```

普通 `mvn test` 会跳过两个显式模型评测类，但现有 `SpringAiStudyApplicationTests` 会启动完整应用，仍需要本机 Ollama。

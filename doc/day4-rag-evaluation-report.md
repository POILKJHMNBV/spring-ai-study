# Day 4 RAG 修复评测报告

生成时间：2026-09-16T15:17:27.598341400Z。模式：完整回归及追加冻结集验收。

本机 Maven 3.8.4 / JDK 17；真实 Ollama + SimpleVectorStore + 随机端口 Spring Boot HTTP。

评测集 SHA-256：`eed653054d49e45891036c6a62bd72ec8c4516d60265e97876ef78872a1ffc4a`。

6 条指定验收 + 24 条开发集 + 24 条冻结保留集；开发/保留各含 18 条相关和 6 条无关问题。本次继续使用工作区已有评测集；自本次开发集运行起未修改语料、问题或标签，保留集未用于选择阈值。

首次纯向量保留集只有 16/18，触发原有 17/18 门槛失败；保留集已被观察，后续融合结果属于回归验证，不能声称为全新盲测。融合算法确定后另冻结 18 条 validation（12 正、6 负），本轮不再据其结果调参。总计 72 条。

追加验证集 SHA-256：`37d62807c3f785ec07f0e73e2912559aeeccf587679fd02ae4280145674a8ee0`。

原始语料对照使用冻结的旧切分器（21 块），补充语料使用生产预算切分器。因此旧模型到 BGE 原始语料隔离模型收益；BGE 原始到补充语料同时包含语料和预算切分变化，不将其全部归因于语料。

原始语料快照：

- kafka-consumer-lag.md：7 块，SHA-256 `b92966bc100bc32f7b14fcf13199014afc7dc8f51289296f498285c4261b304e`。
- mysql-slow-query.md：7 块，SHA-256 `7a4d90dd4d0c96e82840f7746254e636d2e4998707be534e7619f6b38e112af8`。
- thread-pool-exhaustion.md：7 块，SHA-256 `da06d6d502c567f7394cfd0a4c628acc042bc3285f955c7a68b63caef0e12d32`。

## 指标口径

领域 Hit@1：相关查询首位领域正确率（无结果计错）。Recall@3：前三位覆盖的标注相关小节数 / 该题全部标注相关小节数，再对相关查询取平均。MRR@20：首个相关小节的排名倒数均值，候选最多 20 个；未召回计 0。无结果率分母为该组全部问题；误召回率分母仅为负样本。小节以 domain + 标题编号标识，不能仅凭领域算相关。

## 对照结果

|实验|分组|阈值|领域 Hit@1|小节 Recall@3|小节 MRR@20|无结果率|负样本误召回率|
|---|---|---:|---:|---:|---:|---:|---:|
| mxbai-embed-large/原始语料 | acceptance | 0.0000 | 0.3333 | 0.2778 | 0.4833 | 0.0000 | 不适用 |
| mxbai-embed-large/原始语料 | acceptance | 0.4700 | 0.1667 | 0.1667 | 0.2194 | 0.5000 | 不适用 |
| mxbai-embed-large/原始语料 | dev | 0.0000 | 0.7222 | 0.5093 | 0.6047 | 0.0000 | 1.0000 |
| mxbai-embed-large/原始语料 | dev | 0.4700 | 0.6667 | 0.4907 | 0.5458 | 0.0833 | 0.8333 |
| mxbai-embed-large/原始语料 | holdout | 0.0000 | 0.5000 | 0.4259 | 0.4955 | 0.0000 | 1.0000 |
| mxbai-embed-large/原始语料 | holdout | 0.4700 | 0.5000 | 0.4259 | 0.4918 | 0.0000 | 1.0000 |
| mxbai-embed-large/原始语料 | validation | 0.0000 | 0.7500 | 0.6667 | 0.7664 | 0.0000 | 1.0000 |
| mxbai-embed-large/原始语料 | validation | 0.4700 | 0.7500 | 0.6667 | 0.7664 | 0.0556 | 0.8333 |
| bge-m3/原始语料 | acceptance | 0.0000 | 1.0000 | 0.6389 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/原始语料 | acceptance | 0.4700 | 1.0000 | 0.6389 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/原始语料 | dev | 0.0000 | 0.9444 | 0.7778 | 0.9209 | 0.0000 | 1.0000 |
| bge-m3/原始语料 | dev | 0.4700 | 0.9444 | 0.7500 | 0.9209 | 0.2500 | 0.0000 |
| bge-m3/原始语料 | holdout | 0.0000 | 0.8889 | 0.7593 | 0.7565 | 0.0000 | 1.0000 |
| bge-m3/原始语料 | holdout | 0.4700 | 0.8889 | 0.7593 | 0.7565 | 0.0833 | 0.6667 |
| bge-m3/原始语料 | validation | 0.0000 | 1.0000 | 0.8333 | 0.9583 | 0.0000 | 1.0000 |
| bge-m3/原始语料 | validation | 0.4700 | 1.0000 | 0.8333 | 0.9583 | 0.2778 | 0.1667 |
| bge-m3/补充语料纯向量 | acceptance | 0.0000 | 1.0000 | 0.7500 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/补充语料纯向量 | acceptance | 0.4700 | 1.0000 | 0.7500 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/补充语料纯向量 | dev | 0.0000 | 1.0000 | 0.8241 | 1.0000 | 0.0000 | 1.0000 |
| bge-m3/补充语料纯向量 | dev | 0.4700 | 1.0000 | 0.7963 | 1.0000 | 0.2500 | 0.0000 |
| bge-m3/补充语料纯向量 | holdout | 0.0000 | 0.8889 | 0.8148 | 0.7935 | 0.0000 | 1.0000 |
| bge-m3/补充语料纯向量 | holdout | 0.4700 | 0.8889 | 0.8148 | 0.7935 | 0.0833 | 0.6667 |
| bge-m3/补充语料纯向量 | validation | 0.0000 | 1.0000 | 0.8333 | 0.9583 | 0.0000 | 1.0000 |
| bge-m3/补充语料纯向量 | validation | 0.4700 | 1.0000 | 0.8333 | 0.9583 | 0.1667 | 0.5000 |
| bge-m3/BM25-RRF候选融合 | acceptance | 0.0000 | 1.0000 | 0.8889 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/BM25-RRF候选融合 | acceptance | 0.4700 | 1.0000 | 0.8889 | 1.0000 | 0.0000 | 不适用 |
| bge-m3/BM25-RRF候选融合 | dev | 0.0000 | 1.0000 | 0.8519 | 0.9722 | 0.0000 | 1.0000 |
| bge-m3/BM25-RRF候选融合 | dev | 0.4700 | 1.0000 | 0.8519 | 0.9722 | 0.2500 | 0.0000 |
| bge-m3/BM25-RRF候选融合 | holdout | 0.0000 | 1.0000 | 0.8611 | 0.8796 | 0.0000 | 1.0000 |
| bge-m3/BM25-RRF候选融合 | holdout | 0.4700 | 1.0000 | 0.8611 | 0.8796 | 0.0833 | 0.6667 |
| bge-m3/BM25-RRF候选融合 | validation | 0.0000 | 1.0000 | 0.7917 | 1.0000 | 0.0000 | 1.0000 |
| bge-m3/BM25-RRF候选融合 | validation | 0.4700 | 1.0000 | 0.7917 | 1.0000 | 0.1667 | 0.5000 |

## 开发集阈值扫描

选择规则：最大化（相关查询非空召回率 + 负样本拒绝率）/2；平分取较低阈值。只对开发集扫描，不用保留集选择阈值；领域和小节排序另行检查。

|阈值|相关非空召回率|负样本拒绝率|平衡分数|
|---:|---:|---:|---:|
|0.4000|1.0000|0.3333|0.6667|
|0.4100|1.0000|0.3333|0.6667|
|0.4200|1.0000|0.5000|0.7500|
|0.4300|1.0000|0.5000|0.7500|
|0.4400|1.0000|0.6667|0.8333|
|0.4500|1.0000|0.8333|0.9167|
|0.4600|1.0000|0.8333|0.9167|
|0.4700|1.0000|1.0000|1.0000|
|0.4800|1.0000|1.0000|1.0000|
|0.4900|1.0000|1.0000|1.0000|
|0.5000|1.0000|1.0000|1.0000|
|0.5100|1.0000|1.0000|1.0000|
|0.5200|1.0000|1.0000|1.0000|
|0.5300|1.0000|1.0000|1.0000|
|0.5400|1.0000|1.0000|1.0000|
|0.5500|1.0000|1.0000|1.0000|
|0.5600|1.0000|1.0000|1.0000|
|0.5700|0.9444|1.0000|0.9722|
|0.5800|0.8333|1.0000|0.9167|
|0.5900|0.7778|1.0000|0.8889|
|0.6000|0.7778|1.0000|0.8889|
|0.6100|0.7778|1.0000|0.8889|
|0.6200|0.7222|1.0000|0.8611|
|0.6300|0.7222|1.0000|0.8611|
|0.6400|0.6111|1.0000|0.8056|
|0.6500|0.5556|1.0000|0.7778|
|0.6600|0.5556|1.0000|0.7778|
|0.6700|0.4444|1.0000|0.7222|
|0.6800|0.4444|1.0000|0.7222|
|0.6900|0.3889|1.0000|0.6944|
|0.7000|0.2778|1.0000|0.6389|

开发集建议阈值：**0.4700**；本次配置：**0.4700**。

## 修复后逐条结果

|ID|组别|查询|期望领域|实际首位领域 / 小节|分数|首位领域正确|
|---|---|---|---|---|---:|---|
|A01|acceptance|线程池打满|thread-pool|thread-pool:7|0.7294|是|
|A02|acceptance|执行器没有空闲线程|thread-pool|thread-pool:1|0.6438|是|
|A03|acceptance|Kafka 消费积压|kafka|kafka:3|0.7391|是|
|A04|acceptance|生产速度高于消费速度|kafka|kafka:6|0.6160|是|
|A05|acceptance|数据库查询越来越慢|mysql|mysql:1|0.7675|是|
|A06|acceptance|连接池快满了|mysql|mysql:2|0.6606|是|
|D01|dev|工作线程都忙着，新任务只能排队|thread-pool|thread-pool:2|0.5743|是|
|D02|dev|执行器拒绝提交的任务|thread-pool|thread-pool:2|0.6372|是|
|D03|dev|线程池队列持续变长|thread-pool|thread-pool:1|0.5974|是|
|D04|dev|RejectedExecutionException|thread-pool|thread-pool:2|0.6115|是|
|D05|dev|消息进来的比处理掉的多|kafka|kafka:2|0.5698|是|
|D06|dev|消费者一直追不上生产者|kafka|kafka:2|0.5593|是|
|D07|dev|消费组的 lag 不断上涨|kafka|kafka:1|0.6674|是|
|D08|dev|分区数量会限制消费者并行度吗|kafka|kafka:4|0.7251|是|
|D09|dev|SQL 响应耗时持续上升|mysql|mysql:4|0.6936|是|
|D10|dev|HikariCP 获取连接一直等待|mysql|mysql:2|0.5733|是|
|D11|dev|数据库连接用尽，业务拿不到连接|mysql|mysql:2|0.6604|是|
|D12|dev|慢 SQL 应该如何检查索引和锁|mysql|mysql:3|0.6822|是|
|D13|dev|TaskRejectedException 是什么异常|thread-pool|thread-pool:2|0.7021|是|
|D14|dev|业务线程池饱和为什么会使 Kafka 消费变慢|thread-pool|thread-pool:5|0.8215|是|
|D15|dev|Consumer 比 Partition 多还有用吗|kafka|kafka:4|0.7109|是|
|D16|dev|consumeRate 大于 produceRate，积压正在下降|kafka|kafka:2|0.6985|是|
|D17|dev|activeConnections 接近 maximumPoolSize 且 pendingThreads 增加|mysql|mysql:2|0.6385|是|
|D18|dev|数据库响应慢为什么会让 Java 业务线程等待|mysql|mysql:4|0.7585|是|
|D19|dev|今天天气怎么样|none|无结果|—|是|
|D20|dev|如何烤一块巧克力蛋糕|none|无结果|—|是|
|D21|dev|Kubernetes 证书过期如何更新|none|无结果|—|是|
|D22|dev|Redis 主从复制断开怎么修复|none|无结果|—|是|
|D23|dev|如何申请报销差旅费用|none|无结果|—|是|
|D24|dev|Nginx HTTPS 证书续期步骤|none|无结果|—|是|
|H01|holdout|池里的工作线程一个也腾不出来|thread-pool|thread-pool:1|0.5878|是|
|H02|holdout|提交任务时抛出 RejectedExecutionException 怎么排查|thread-pool|thread-pool:2|0.7175|是|
|H03|holdout|threadPoolActive 和 threadPoolMax 一样大|thread-pool|thread-pool:6|0.5609|是|
|H04|holdout|线程全部阻塞在下游 RPC，应该直接扩线程吗|thread-pool|thread-pool:7|0.6755|是|
|H05|holdout|消费业务的执行器满载后 lag 升高|thread-pool|thread-pool:5|0.6836|是|
|H06|holdout|任务等待时间拉长，执行器队列不断堆高|thread-pool|thread-pool:2|0.6261|是|
|H07|holdout|写入消息的速率一直超过消费者处理速率|kafka|kafka:2|0.6058|是|
|H08|holdout|消费位点落后最新 offset 很多代表什么|kafka|kafka:1|0.6303|是|
|H09|holdout|八个分区只安排一个消费者会影响吞吐吗|kafka|kafka:4|0.6306|是|
|H10|holdout|峰值结束后消费比生产快，lag 在回落|kafka|kafka:2|0.6382|是|
|H11|holdout|消费组频繁 Rebalance 会造成积压吗|kafka|kafka:2|0.6003|是|
|H12|holdout|Kafka 处理不过来，扩消费者前先查什么|kafka|kafka:2|0.6271|是|
|H13|holdout|SQL 的 P99 最近持续恶化|mysql|mysql:1|0.6236|是|
|H14|holdout|HikariCP 没有闲置连接，线程排队取连接|mysql|mysql:2|0.5906|是|
|H15|holdout|查询扫描行数太多该检查执行计划吗|mysql|mysql:7|0.5424|是|
|H16|holdout|长事务和锁等待会使 SQL 变慢吗|mysql|mysql:1|0.6627|是|
|H17|holdout|SQL 返回很慢导致连接长时间不释放|mysql|mysql:4|0.6616|是|
|H18|holdout|MySQL 已经很忙，调大连接数能解决吗|mysql|mysql:7|0.6990|是|
|H19|holdout|明天适合去爬山吗|none|无结果|—|是|
|H20|holdout|帮我翻译一首英文诗|none|无结果|—|是|
|H21|holdout|Elasticsearch 分片无法分配怎么办|none|kafka:5|0.5124|否|
|H22|holdout|如何排查 DNS 解析失败|none|mysql:5|0.5943|否|
|H23|holdout|Linux 磁盘只读如何修复|none|kafka:7|0.4855|否|
|H24|holdout|Redis 缓存雪崩如何预防|none|thread-pool:7|0.4973|否|
|V01|validation|业务提交任务收到 TaskRejectedException，需要检查什么|thread-pool|thread-pool:2|0.6860|是|
|V02|validation|执行器活动线程已到上限且队列没有剩余容量|thread-pool|thread-pool:1|0.6915|是|
|V03|validation|每个任务执行越来越久，增加工作线程之前该查什么|thread-pool|thread-pool:7|0.6336|是|
|V04|validation|Kafka 消息交给线程池执行，线程池阻塞会影响消费吞吐吗|thread-pool|thread-pool:5|0.7840|是|
|V05|validation|Consumer Group 的已消费位点和最新位点差距一直拉大|kafka|kafka:1|0.6072|是|
|V06|validation|同一消费组的消费者实例比消息分区还多是否有意义|kafka|kafka:4|0.6405|是|
|V07|validation|produceRate 为每秒九千条，consumeRate 只有每秒三千条|kafka|kafka:2|0.6548|是|
|V08|validation|消费者扩容可能加重下游数据库的压力吗|kafka|kafka:7|0.6414|是|
|V09|validation|pendingThreads 持续大于零且 idleConnections 为零意味着什么|mysql|mysql:2|0.6227|是|
|V10|validation|平均 SQL 耗时没变，P99 却升高了能排除数据库问题吗|mysql|mysql:1|0.7010|是|
|V11|validation|慢查询伴随大量扫描行和锁等待，应查看哪些信息|mysql|mysql:3|0.7105|是|
|V12|validation|数据库瓶颈时为什么不应该盲目增大连接池|mysql|mysql:7|0.7468|是|
|V13|validation|帮我规划周末两日自驾游|none|无结果|—|是|
|V14|validation|怎么做番茄炒鸡蛋|none|无结果|—|是|
|V15|validation|RabbitMQ 队列镜像同步失败怎么恢复|none|无结果|—|是|
|V16|validation|MongoDB 副本集选举频繁失败如何解决|none|thread-pool:2|0.4800|否|
|V17|validation|JVM Metaspace 内存溢出如何排查|none|thread-pool:4|0.5918|否|
|V18|validation|Docker 镜像拉取鉴权失败怎么办|none|thread-pool:2|0.4882|否|

## HTTP 与构建验证

真实 HTTP 请求断言通过：**98**（逐条默认检索、11 个非法参数用例、六条在 0/0.50 两种显式阈值下验证、3 个合法边界用例）。没有调用聊天模型，检索正确不等于最终诊断回答已验收。

## 索引清单

```json
{
  "model" : "bge-m3",
  "digest" : "7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab",
  "dimensions" : 1024,
  "sourceHashes" : {
    "kafka-consumer-lag.md" : "4d65c29eeb3e3733c251ce88309e433833e2e874eafd1d5ac1b0631173e047bb",
    "mysql-slow-query.md" : "b52b68fce65445a7a5ca84127a4ee721d5e9937f5eccfa59ae6707b5d74203fd",
    "thread-pool-exhaustion.md" : "78da18a79131d6d677cc71b60cf33aedcb98f2319fd3e52ab4f501c3faf9a302"
  },
  "corpusHash" : "31959fe5eb6d4d8d0bc008cbdd92b4da1d6650b43a17aa0419ebb779c4c009af",
  "splitterVersion" : "markdown-heading-budget-v2",
  "tokenBudget" : 220,
  "tokenEstimator" : "JTokkitTokenCountEstimator; not model tokenizer",
  "truncate" : false,
  "files" : 3,
  "chunks" : 23,
  "builtAt" : "2026-09-16T15:17:22.180302400Z",
  "elapsedMs" : 624
}
```

## 复现

```powershell
mvn test "-Dtest=RagQualityEvaluationTest" "-Drag.eval=true"
```

分数不是概率；有限负样本通过不保证任意知识库外问题都能拒绝。原始 JSON 和基线文档已保留。

## 结论与未解决限制

BM25 在固定 20 个向量候选中使用中文双字与完整英文术语，k1=1.2、b=0.75；RRF 常数为 60，两个排名等权。这属于候选内融合，不能找回向量前 20 名之外的文档，也没有引入全库 BM25 或神经 reranker。先校验来源，融合后仍应用原始相似度阈值，词法命中不能绕过阈值。返回 score 保留向量分数，因此列表不一定按 score 递减。

- acceptance：领域首位 6/6；库外误召回 0/0。
- dev：领域首位 18/18；库外误召回 0/6。
- holdout：领域首位 18/18；库外误召回 4/6。
- validation：领域首位 12/12；库外误召回 3/6。

默认阈值 0.47 是开发集 0.40～0.70 扫描后按预先规则选择，不能作为通用拒答保证。保留集和追加集的库外错误明确计入指标，未通过删除问题、修改标签或调整阈值隐藏。当前只返回检索候选，尚不能将非空候选当作可回答的证明；可靠库外拒答仍需后续独立工作。

融合也不是所有指标都提升：开发集 MRR 从 1.0000 降到 0.9722，追加集 Recall@3 从 0.8333 降到 0.7917；收益是原保留集领域首位从 16/18 到 18/18，追加集首个相关片段 MRR 从 0.9583 到 1.0000。

原始候选证据：[day4-rag-evaluation-results.tsv](day4-rag-evaluation-results.tsv)。

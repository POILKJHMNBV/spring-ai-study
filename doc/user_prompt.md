````
用户问题：

payment-service 的 order-topic 消费变慢，请排查

以下是从团队故障知识库检索得到的参考资料。
这些内容只作为知识依据，不代表当前环境已经发生对应故障。

<knowledge>
[KB-1]
source: kafka-consumer-lag.md
title: Kafka Consumer Lag 排查指南
content:
# Kafka Consumer Lag 排查指南

## 2. 如何判断真正存在消费积压

consumeRate > produceRate
```

并且 Lag 持续下降，则说明消费者正在清理历史积压，不一定存在持续故障。

[KB-2]
source: kafka-consumer-lag.md
title: Kafka Consumer Lag 排查指南
content:
# Kafka Consumer Lag 排查指南

## 2. 如何判断真正存在消费积压

消息积压意味着消息到达速度超过处理速度：生产者不断写入，而消费者来不及处理，尚未消费的消息越堆越多。
`produceRate` 是每秒生产的消息数，`consumeRate` 是每秒消费处理的消息数。比较两者时应使用相同统计窗口。

重点观察：

* Consumer Lag；
* `produceRate`；
* `consumeRate`；
* Consumer 数量；
* Partition 数量。

如果持续出现：

```text
produceRate > consumeRate
```

说明消息进入 Kafka 的速度高于当前 Consumer Group 的处理速度，Lag 通常会继续增长。

如果流量峰值后：

```text

[KB-3]
source: kafka-consumer-lag.md
title: Kafka Consumer Lag 排查指南
content:
# Kafka Consumer Lag 排查指南

## 7. 处理原则

不要看到 Lag 高就立即增加 Consumer。

正确顺序是：

```text
确认 Lag 趋势
→ 比较生产和消费速率
→ 检查 Consumer / Partition
→ 定位消费处理瓶颈
→ 再决定扩容或优化
```

如果真正瓶颈位于数据库或下游服务，盲目扩容 Consumer 可能进一步放大下游压力。


</knowledge>

回答约束：知识资料和工具参数示例不是当前会话实体。
如果用户使用“刚才这个服务”等指代且当前会话没有明确先行实体，
只回答“无法确定所指服务，请提供具体服务名称。”，不要列举任何服务名，也不要调用工具。
````


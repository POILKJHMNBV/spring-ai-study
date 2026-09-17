# Kafka Consumer Lag 排查指南

## 1. Consumer Lag 是什么

Kafka Consumer Lag 表示 Consumer Group 当前消费位置与 Partition 最新消息位置之间的差距。

可以简单理解为：

```text
Consumer Lag = 最新 Offset - 已消费 Offset
```

Lag 表示还有多少消息等待消费。

Lag 高本身不一定代表故障，更重要的是观察 Lag 的变化趋势以及生产速率和消费速率。

## 2. 如何判断真正存在消费积压

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
consumeRate > produceRate
```

并且 Lag 持续下降，则说明消费者正在清理历史积压，不一定存在持续故障。

## 3. 常见原因

Kafka 消费积压常见原因包括：

* Consumer 数量不足；
* Partition 并行度不足；
* 单条消息处理时间增加；
* Consumer 实例异常或频繁 Rebalance；
* Partition 流量分布不均；
* Consumer 所在服务处理能力下降。

Kafka Lag 通常只是“消费速度不足”的表现，不能仅凭 Lag 判断具体根因。

## 4. Consumer 与 Partition

同一个 Consumer Group 中，一个 Partition 同一时间只会分配给一个 Consumer。

如果：

```text
Partition = 8
Consumer = 1
```

Consumer 数量可能限制并行消费能力。

但 Consumer 数量超过 Partition 数量以后，继续增加实例通常不会继续提高 Partition 级并行度。

因此需要同时观察 Consumer 数量和 Partition 数量。

## 5. 推荐排查顺序

首先确认：

```text
Lag 是否持续增长
produceRate 是否持续大于 consumeRate
```

然后检查 Consumer 数量、Partition 数量以及 Partition 分配情况。

如果 Kafka 侧没有明显问题，再继续检查 Consumer 服务的 CPU、线程池、数据库和下游 RPC。

## 6. 判断原则

如果已知：

```text
lag = 125000
produceRate = 5000
consumeRate = 2800
```

可以确认当前生产速度明显高于消费速度，Consumer Group 存在持续积压风险。

但不能仅凭这些指标确定是线程池、数据库、RPC 或 Kafka Broker 导致。

具体根因需要继续通过服务指标和日志验证。

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

# Java 线程池饱和排查指南

## 1. 线程池饱和是什么

Java 线程池也常称为执行器（Executor）。

“线程池打满”“执行器没有空闲线程”“工作线程全部占用”“activeCount 达到 maximumPoolSize”通常都表示线程池的可用执行能力已经接近或达到上限。

重点观察：

* `threadPoolActive`
* `threadPoolMax`
* `queueSize`
* `queueRemainingCapacity`
* 单任务执行时间

如果长时间出现：

```text
threadPoolActive = threadPoolMax
```

同时任务队列持续增长，说明没有足够的空闲工作线程处理新增任务。

## 2. 典型异常现象

线程池真正发生饱和时，常见现象包括：

* active 长时间接近或等于 max；
* 任务队列持续增加；
* 任务等待时间和 P95/P99 增长；
* 服务吞吐下降；
* 出现 `TaskRejectedException` 或 `RejectedExecutionException`。

如果已经出现任务拒绝，可以确认线程池处理能力已经受到影响。

## 3. 常见原因

线程池饱和通常来自两类问题。

第一类是任务进入速度过快，例如请求 QPS 或消息处理任务突然增加。

第二类是单任务执行时间变长，例如下游 RPC、数据库访问或本地计算变慢，导致工作线程长期无法释放。

因此不能仅根据线程池打满就判断是“线程数配置太小”。

## 4. 推荐排查顺序

首先检查：

```text
threadPoolActive
threadPoolMax
queueSize
```

确认饱和是否持续存在。

其次检查是否出现：

```text
TaskRejectedException
RejectedExecutionException
```

然后检查单任务平均耗时和 P95/P99，判断任务处理时间是否明显增长。

最后再检查 RPC、数据库、CPU 或业务流量，寻找导致任务变慢或变多的真正原因。

## 5. 与 Kafka 消费的关系

如果 Kafka Consumer 将消息提交给业务线程池处理，线程池饱和会降低单机消费吞吐。

可能表现为：

```text
线程池饱和
→ 消息处理速度下降
→ consumeRate 下降
→ Kafka Lag 增长
```

Kafka Lag 是可能的上游表现，但线程池问题本身仍需通过线程池指标和异常日志确认。

## 6. 判断原则

如果同时观察到：

```text
threadPoolActive = threadPoolMax
TaskRejectedException
queueSize 持续增长
```

可以判断线程池已经处于饱和状态。

但不能仅凭这些信息判断根因一定是数据库、RPC、CPU 或线程池配置不足。

应继续通过对应组件的指标进行验证。

## 7. 处理原则

不要看到线程池打满就直接扩大 `maximumPoolSize` 或 `queueCapacity`。

正确顺序是：

```text
确认线程池饱和
→ 确认任务为什么变慢或变多
→ 定位真正瓶颈
→ 必要时再调整线程池容量
```

如果真正瓶颈位于数据库或下游 RPC，扩大线程池反而可能进一步增加下游压力。

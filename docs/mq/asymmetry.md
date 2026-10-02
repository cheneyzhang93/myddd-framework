# MQ 消费侧的厂商不对称清单

本表与 `MessageConsumptionContractTestBase`（九条 `MC-C`）互斥且完备：**基类断言的事实在本表一条都不出现，本表列出的事实基类一条都不断**。每行的"依据"列给的是本仓库自己的实测产物与用例编号，不是厂商文档段落。

| # | 事实 | RocketMQ | Kafka | 迁移须知 |
|---|---|---|---|---|
| 1 | 业务失败之后发生什么 | broker 重投，框架显式钉 `maxReconsumeTimes=16`（厂商缺省 `-1`=交给 broker 配置），超限进 `%DLQ%<消费组>`（实测 `MixAll.getDLQTopic("g1")=="%DLQ%g1"`）；消息**留存可查** | 缺省 `DefaultErrorHandler` + `FixedBackOff(0ms, 9)` ⇒ 本地重试 9 次；耗尽后**`commitSync` 越过该 offset 并静默跳过**（K19 实测：`listenerHits=10`、`seekCount=9`、`committed offset 7→8`、`isRunning=true`） | 从 RocketMQ 换到 Kafka：**失败消息从"可查"变成"消失"**。必须自建 `DeadLetterPublishingRecoverer`（且只能在 `DefaultErrorHandler` 构造期传入——`deh_api.txt` 实测无 `setRecoverer`），或接受丢失。Task 57 的死信管理端点只有 RocketMQ 侧有真数据可读 |
| 2 | `InboundMessage.messageId` 的拼法 | 厂商 `msgId` 原样（实测 `wire` 造的是 `msg-<topic>`） | `<topic>-<partition>-<offset>`（Task 37 `WF9` 钉逐字形式） | 两家都**不许**拿 `messageId` 做幂等键。幂等一律用 `MessageHeaderNames.EVENT_ID`（`MC-C6` 钉它跨两次投递等值） |
| 3 | 厂商内部键会不会漏进消息头 | 会：系统属性与用户头同表存放（`KEYS`/`TAGS`/`WAIT`…，实测名单见 Task 36 `WF6`），读方向逐个摘除 | 不会：入站线消息没有内部头；`kafka_dlt-*` 只在恢复器里加，而恢复不在消费路径上 | `MC-C5` 在 RocketMQ 侧是真判据，在 Kafka 侧是"无事可做"。这条差异本身就是该用例必须在两家都跑的理由 |
| 4 | 通道名规则 | 带点号（`order.events`）在订阅期**不报错**，但启动取路由时失败（Task 36 取证表）；框架在装配期用厂商自己的 `Validators.checkTopic` 拒绝（`DC3`/`DC4`） | 点号合法（topic 名允许 `.`），全程无问题 | 同一个 `@MessageListener(channel="order.events")` 在 RocketMQ 侧**起不来**、在 Kafka 侧跑得好。命名一律用 `-` 分隔（壳的装配期校验只保证"合法字符"，不保证两家都收） |
| 5 | 失败时传输侧看得到异常对象吗 | 看不到：seam 是状态码 `RECONSUME_LATER`，异常不外抛（`LM2`） | 看得到：`onMessage` 现场抛出，异常抵达错误处理器 | 基类的 `if (surfaced != null)` 分支只有 Kafka 与假实现覆盖；不要在基类里改成硬断言非空 |
| 6 | 消费组名的来源 | 消费者组名即 `group`，缺省走 `myddd.mq.rocketmq.consumer.group`（实测 `DEFAULT_CONSUMER_GROUP=="DEFAULT_CONSUMER"`，框架不用它而用自己的缺省） | 优先 `@MessageListener` 的 `group`，未写则回落 `spring.kafka.consumer.group-id`（K14 实测优先级），两者都无 ⇒ 容器 `start()` 抛 `IllegalStateException`（`k16out.txt` 第 3 段） | 只写注解组名两家都能跑；**依赖消费者配置里的缺省组**这一半是 Kafka 独有的，换到 RocketMQ 会静默变成框架缺省组名 |
| 7 | 一个通道一个消费者还是一个组一个消费者 | 一组一个 `DefaultMQPushConsumer`，同组多通道共享它（`CM4` 钉订阅顺序来自 `TreeMap` 升序） | 一组一个容器，容器内 `concurrency` 决定消费者线程数（Task 38 `CM3`） | 队列/分区级的并行度两家算法不同，不要按"我配了 concurrency=3"去推算 RocketMQ 侧的吞吐 |
| 8 | `Error`（非 `Exception`）怎么办 | 翻译成 `RECONSUME_LATER` 等于"再试一次同一个已经撑不住的 JVM"，所以框架**不接**：原样上抛（`LM3`） | 同样原样上抛：`Error` 不进 `DefaultErrorHandler` 的 `handleOne`（它只收 `Exception`） | 唯一一致的一条，所以它写在这里而不是基类里——基类只断 `RuntimeException` 的方向 |

## 这份清单怎么被用例覆盖

上表每一行都不许出现在 `MessageConsumptionContractTestBase` 的断言里（§0.3-11：基类不出现厂商类，也不假设厂商规则）。它们由各 provider 自己的用例组钉住：

- 第 1 行：RocketMQ 侧 `DC6`（`setMaxReconsumeTimes` 被显式调用）与 `LM2`（失败回 `RECONSUME_LATER`）；Kafka 侧 `LM2`（异常原样外抛给错误处理器）+ K19 探针（`D:/tmp/p4probe` 的 `k19out.txt`）。真投递侧的落点核验在 Task 40。
- 第 2 行：`RocketMqWireFormatTest` 与 Task 37 的 `WF9` 各钉各的拼法，两家互不比较。
- 第 3 行：`MC-C5` 在 `RocketMqConsumptionContractTest` 与 `KafkaConsumptionContractTest` 上各跑一遍——同一条断言、两种判定强度，这正是它必须两家都跑的原因。
- 第 4 行：`DC3`/`DC4`（装配期用厂商 `Validators.checkTopic` 拒绝）。
- 第 5 行：基类 `MC-C7` 的 `if (surfaced != null)` 分支只在 Kafka 与进程内假实现（`MessageConsumptionFakeContractTest`）上被走到；变异扫描 FX12 证明该分支不是死码。
- 第 6 行：`CM5`/`CM6`（Kafka 的回落链与两源都缺时的启动期失败）、`CM5`（RocketMQ 侧未写 group 走 `myddd.mq.rocketmq.consumer.group`）。
- 第 7 行：`CM3`/`CM4`（组→容器/消费者的一对一关系与订阅顺序）。
- 第 8 行：两侧 `LM3`（`Error` 原样上抛、不翻译成重投）。

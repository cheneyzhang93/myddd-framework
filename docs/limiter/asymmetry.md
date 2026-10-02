# Limiter 的厂商不对称清单

本表与 `myddd-test-starter` 的两份契约基类（`RateLimitPortContractTestBase` 六条 `RL-C1`–`RL-C6`、
`CircuitBreakerPortContractTestBase` 七条 `CB-C1`–`CB-C7`）互斥且完备：<b>基类断言的事实（额度内放行、
超额拒绝、一次申请按申请的许可数扣、资源之间互相独立、窗口翻篇后额度恢复、非法入参拒绝、开闸后不再执行动作、
拒绝报 `SERVICE_DEGRADED`）在本表一条都不出现；本表列出的事实基类一条都不断</b>。
"依据"列给的是本仓库的实测产物、用例编号或一手探针读数，不是厂商文档段落。

| # | 事实 | sentinel（1.8.9） | resilience4j（2.4.0） | 迁移须知 |
|---|---|---|---|---|
| 1 | "零配额"能不能写 | <b>能，而且语义正好是运维想要的那个</b>：`count=0` 时 `FlowRuleUtil.isValidRule=true`、`getRules()` 仍是 1 条、第一次 `entry=false`（QPS 与线程数两个 grade 同形）。依据：探针 `ProbeRS6` 的 B/E 格 + 用例 `RS6` 末三句 + 装配级 `AC5b` | <b>不能</b>：厂商在 `build()` 当场 `IllegalArgumentException: LimitForPeriod should be greater than 0`，`0` 与负数同一句。依据：探针 `ProbeR4jQuota` 的 A/B/C 格 + 用例 `RS9` 的第三、四格 | 同一份规则文件在两家<b>语义相反</b>：sentinel 侧写 0 是"把这个资源关死"，r4j 侧写 0 是"这个应用启动不起来"。临时关死一个资源在 r4j 侧只能靠 provider 开关（`myddd.limiter.resilience4j.enabled=false`）或删规则 + 业务侧兜底。<br><b>公开撤回</b>：Plan 4 的 P45-F 那句"`count=0` 会被厂商静默丢弃、于是全放行"读错了这一格（丢弃的是<b>负数与 NaN</b>，见第 2 行），两侧的校验器与文案都已按实测改掉 |
| 2 | 非法值去哪了 | <b>静默丢弃</b>：`count=-1` 与 `count=NaN` 让 `isValidRule=false`，`loadRules` 之后 `getRules()` 少一条，全程不抛异常、不写日志——配错的人以为自己关死了接口，实际是全放行。依据：`ProbeRS6` 的 C/D 格 | <b>从不静默</b>：越界值 `build()` 当场抛，异常消息就是判据（`failureRateThreshold` / `LimitForPeriod` 两句）。依据：`ProbeR4jQuota` | 两侧的兜法不同但错误面相同：sentinel 侧靠校验器拦在第 2 行之前 + 下发后按条数与资源名<b>回读比对</b>（`RS11`，这是唯一能看见"这条没进去"的手段）；r4j 侧不回读条数（没有可丢的东西），回读比的是每个资源的<b>最终配置值</b>（`RS11`/`RS12` 同编号在两侧各钉自己那一格）。框架把厂商的 `IllegalArgumentException` 翻译成 `FrameworkException` 并补上 `flow[i]（资源 …）`（`Resilience4jRulePublisher.buildInto`），但<b>不</b>复制一套阈值区间——判据真源留在厂商 |
| 3 | 统计窗口的单位 | `statIntervalMs` 是<b>毫秒</b>；`timeWindow`（开闸期）是<b>秒</b> | `slidingWindowSize` 在 `TIME_BASED` 下按<b>秒</b>解释：`windowMs=1200` 会被向下取整成 1 秒，规则还在但窗口比运维写的短 | 同一份文档里 `windowMs` 两侧单位一致（毫秒），换算只发生在 r4j 侧，因此亚秒窗口在 r4j 侧<b>拒收</b>并给出取整结果（`RS6`），sentinel 侧允许 `>=1000ms` 任意值。别指望"把窗口调到 500ms 就更灵敏"：那一侧根本不接受，另一侧接受但样本数由调用速率决定（`RS9` 的第二条理由） |
| 4 | 额度怎么恢复 | QPS 一秒翻篇；<b>清空重发不恢复计数</b>（`2_clear_then_republish_restores_budget=false`）。依据：探针 `P45-J` | 按 `limitRefreshPeriod` 周期性补令牌；重建实例即满额度（`registry.remove` 对不存在的名字不抛，`Probe46f F3`；`rateLimiter(name, cfg)` 必按新配置建，`Probe46g G1/G2`） | 契约基类的 `awaitFreshWindow` 在两侧走不同路线，代价直接体现在测试墙钟：本轮同一份构建日志里 sentinel 侧 `SentinelRateLimitPortContractTest` 用时 <b>10.92 s</b>（9 次 × 1200ms 真等），r4j 侧 `Resilience4jRateLimitPortContractTest` <b>0.081 s</b>（删了重建）。这不是"我们比它快"：换实例的代价是热更新会重置熔断状态（第 9 行），两笔账一起交 |
| 5 | 熔断判据的可表达集 | `exception-ratio` 与"平均 RT"两档框架接受（`threshold=rt` 在厂商侧是 `count=慢调用 ms 阈值` + `slowRatioThreshold=占比`）；`exception-count` <b>框架拒收</b>：实测 `minCalls=3` 且连续 3 次失败后第 4 次仍放行，开闸时刻不可预测（`RS7`） | 只有 `error-ratio` 与 `slow-ratio`（`slowCallRateThreshold` + `slowCallDurationThreshold`）。<b>没有</b>“异常次数”与“平均 RT”这两档，硬映射会变成“写了却没生效”，所以校验器直接拒（`RS8` 的第二格） | `threshold=rt` 在 sentinel 侧是"平均耗时"，在 r4j 侧对应的写法是 `slow-ratio`（"耗时超阈值的<b>占比</b>"）——同一个 `maxRtMs` 配同一个 `ratio`，两家的判据<b>不是</b>同一个量。迁移必须重读规则文件，不能只换 `myddd.limiter.provider` |
| 6 | 失败往哪儿记（`Error` 这一格） | 不调厂商的 `Tracer.trace` 就<b>根本不打开</b>：端口在 `entry.close()` 之前记账（`AB7` 钉这个次序：两次失败后第三次开闸且动作不执行） | 装饰器那一族（`decorateSupplier`）对 `AssertionError`（`Error` 而非 `Exception`）一次都不计数：同一 `Error` 走两次之后 `state=CLOSED`、`getNumberOfFailedCalls()=0`，`recordExceptions(Throwable.class)` 与 `recordException(pred)` 的 predicate 实测 `invocations=0`。依据：探针 `Probe46b Q1/Q2/Q4` | 两侧的结论是同一件事的两种厂商表达：<b>必须显式记账</b>。r4j 侧端口因此手工三段（`tryAcquirePermission` → `onSuccess(纳秒差, NANOSECONDS)` / `onError(纳秒差, NANOSECONDS, err)`），不用装饰器；`AB9` 在两侧各钉一遍"`Error` 也算失败" |
| 7 | 拒绝有几条出口 | `SphU.entry` 抛 `BlockException`（`DegradeException` 是它的子类），`SphO.entry` 返回 `false`。端口只用后者表达"限流拒绝"（布尔），用前者翻译"熔断开闸"（`BizException(SERVICE_DEGRADED)`，cause 是厂商异常，`AB8`） | `tryAcquirePermission()` 返回布尔，`acquirePermission()`（`void`，开闸时抛 `CallNotPermittedException`）与 `circuitBreaker.acquirePermission()` 一条都不出现——实测 `Probe46d_9` 的 `THREW` 那一格 | 端口契约只有一条拒绝出口，所以两家各自的"第二种拒绝写法"在框架代码里一次都不出现。业务侧看到的类型因此与 provider 无关：`RateLimitPort.tryAcquire → boolean`、`CircuitBreakerPort.execute → BizException` |
| 8 | 资源名怎么落到厂商表里 | <b>一张进程级表、两个段</b>：`myddd:rate:` 与 `myddd:circuit:` 前缀把同一个裸名分开（`RS12` 钉"同裸名两段下发成两条"，`AB10`/`AB11` 钉"两个端口的规则互不串门"） | <b>两个独立注册表</b>：`RateLimiterRegistry` 与 `CircuitBreakerRegistry` 本身就是命名空间 ⇒ 不加前缀，裸名直接进表（`RS12` 的 r4j 侧同编号） | 前缀只在 sentinel 侧存在，因此"资源名"这一维在两侧不等价：拿 `myddd:rate:order:create` 去 r4j 侧查会当成一个裸名。框架保证前缀永不出现在对外消息里（`AB13` 钉 `hasMessageNotContaining("myddd:")`），排查时用厂商控制台的名字、写规则文件时用裸名 |
| 9 | 热更新的真接缝 | 整表替换；<b>degrade 段必须先 `loadRules(空)` 再发新表</b>：同一份 degrade 重发是 no-op，已开闸的资源保持开闸（`2_clear_then_republish_first_call`：清空重发读到 `ran`，不清空读到 `DegradeException`）。flow 段不空清（多清一次只开出"这一瞬不限流"的窗口，没有收益）。依据：`P45-J` | `registry.rateLimiter(name, cfg)` 对<b>已存在</b>的名字返回同一实例并忽略新配置 ⇒ 不 `remove` 就永远改不动；`remove` 之后重建会把该资源的熔断历史一起丢掉（等价于给一次干净窗口） | 两侧的"改了不生效"故障长得一模一样、根因相反：sentinel 侧是 degrade 表覆盖语义，r4j 侧是 registry 的忽略语义。`reload()` 在两侧都是唯一热更新接缝（`RS11`/`RS6` 各钉一次生效），但 r4j 侧的运维语义要额外知道一句：<b>热更新熔断规则等于重置该资源统计</b> |
| 10 | 半开探测怎么来 | `timeWindow` 到期后由<b>下一次调用</b>自然探测，没有"放几发"的配置项；`AB12` 因此真睡 1300ms 一次（全模块唯一等待点） | 半开放 `permittedNumberOfCallsInHalfOpenState` 发（缺省 10）；测试用公开 API `transitionToHalfOpenState()` 直接进半开（`Probe46d_10`），所以 r4j 侧的 `AB11`/`AB12` 零等待 | 同一句"恢复期只放一发"在两侧不是一个开关：sentinel 侧做不到（放几发由到达的流量决定），r4j 侧可以配。契约基类因此<b>不</b>断半开发数，只在两侧各钉"开闸后拒绝、恢复后放行"这两头（`CB-C4`/`CB-C5`） |

## 本表不覆盖的部分

- 装配与配置面（`myddd.limiter.provider` 的三态判定、`rule-source=memory|file`、路径存在性）由两侧
  `PS`/`AC`/`IQ` 组各自钉，跨 provider 只有一条共同要求：<b>坏规则文件在启动期响的都是
  `FrameworkException`</b>（sentinel 侧 `AC5`，r4j 侧 `AC5`+`RS9`）。
- 慢调用与 RT 判据的<b>数值</b>等价性不做承诺：第 5 行已经说明两家算的不是同一个量，任何"调同一个阈值
  两边给同一个开闸结论"的期待都不成立，框架不假装能消掉它。

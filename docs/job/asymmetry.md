# Job 的厂商不对称清单

本表与 `JobProviderEquivalenceTestBase`（八条 `EQ`）、`JobPortContractTestBase`（六条 `JOB-C`）互斥且完备：
<b>基类断言的事实（选谁装配、marker 名字、同一份 `@MydddJob` 声明的注册与派发、判重骨架、参数逐字与 null 规范化）
在本表一条都不出现；本表列出的事实基类一条都不断</b>。"依据"列给的是本仓库的实测产物、用例编号或一手字节码读数，
不是厂商文档段落。

| # | 事实 | spring（本地 cron） | xxljob（调度中心） | 迁移须知 |
|---|---|---|---|---|
| 1 | 谁解释 cron | 本 JVM：`register` 先用厂商 `CronExpression.isValidExpression` 拒绝非法串（41d，`RS2`），再交给 Spring 的 `CronTask` 在<b>本进程</b>计时 | 调度中心：我们递给厂商的只有 `adminAddresses/appname/accessToken/port` 四项配置，<b>注册动作里根本没有 cron</b>（`XS8` 钉这条接缝），任务与它的 cron 是 admin 侧的一条数据库记录 | 从 spring 换到 xxljob：注解里的 `cron` 降级为"注册表里的诊断字段"，改它不会改任何调度时刻——真正要改的是 admin 的任务配置。`EQ8` 就是钉这个差别的（`VALIDATES_CRON_AT_REGISTRATION`：spring 真、xxljob 假），它<b>不</b>试图把两家抹平 |
| 2 | 谁发起一次执行 | 本 JVM 的调度线程（`DefaultCronTaskScheduler` 自持线程池，池大小由 `myddd.job.spring.pool-size` 给） | 调度中心按 cron 计时，用 HTTP 回调执行器的内嵌 server（Task 32 取证：`RegistryRequest` 上报的 `address` 就是回调目的地，形状 `http://{ip}:{port}/`） | 换到 xxljob 后"到点没跑"的第一现场在 admin 的调度日志，不在应用日志；应用侧只能看见回调进来之后的一段。两侧的手动派发（`triggerNow`）都在<b>调用线程</b>上同步执行，这条一致（`EQ6`/`EQ7`） |
| 3 | "名字已被占用"有几个来源 | 一个：本进程注册表（`definitions.putIfAbsent`，`RS3`） | 两个：本地注册表判重（`RS3` 同形），外加"该 appname 下这个 JobHandler 已被厂商注册表占用"这一维度（42d 的第二条消息分支）| 同一个 `@MydddJob(name=...)` 在 spring 侧只会和自己的重复撞；在 xxljob 侧还可能和<b>另一个 JVM</b> 的同名 handler 撞。名字必须全局唯一这条纪律，只有在 xxljob 侧才是运行期可观察的 |
| 4 | 停机之后"启动过没有"这个读数 | <b>没有这个读口</b>：`SpringJobPort` 只暴露 `lastFailureOf`/`failures`，`destroy()` 之后无状态可查（41g 的 Step 0 第 3 条） | `isStarted()` 语义裁定为"<b>曾经把启动交给过厂商</b>"，停机后仍为真（Task 43 裁定，三条根据见本节末）；钉子是 `XS6` 的那句 `assertTrue(runtime.isStarted(), ...)`，变异是 `XM39` | 运维排障时两侧的问法不同：spring 侧只能问"最后一次失败是什么"，xxljob 侧问"这台 JVM 交出过启动没有"。不要把 xxljob 的 `isStarted()` 读成"现在还活着"——厂商 `start()` 的第一条字节码就把 `this` 写进静态字段 `XxlJobExecutor.xxlJobExecutor`（`exe.txt`/`S5-2`、`S5-21`），停机收不回这个身份 |
| 5 | 一次失败往哪儿记 | 本地：`runGuarded` 把失败写进 `failures` 表并<b>不外抛</b>（`FA4`），读口是 `lastFailureOf`/`failures`，Task 57 的诊断端点直接读它 | 回传：执行体用厂商的 handle 回调上报成功/失败与日志，落在 admin 的调度日志表里；本地 `failures` 那一份是同一件事的<b>副本</b>，不是真源（`HD` 组钉回调的形状与时机） | 排障入口不同：spring 侧在应用（进程内读口），xxljob 侧在调度中心（网页 + `xxl_job_log` 表）。Task 57 的统一失败视图只能在 spring 侧给出"完整"读数，xxljob 侧给的是"本地看到的那一段" |
| 6 | 到点执行时 handler 收到的 `param` | 恒为空串：`register` 递的是 `JobContext.of(name)`（41d 的 S2 段），没有任何本地配置能给它填值 | 来自 admin 任务配置里的"任务参数"，逐字透传（`EQ6` 的 `DISPATCH_PARAMS` 三条在两侧都逐字比过，含首尾空格） | 想在 spring 侧给任务传参，只能改代码用 `triggerNow`；在 xxljob 侧是改 admin 配置。<b>两侧都不许把 `param` 当配置载体</b>——它是调度中心的一次下发 |
| 7 | 一个 JVM 能有几个执行器 | 无所谓：调度器是普通 bean，多实例各自独立（池大小独立、注册表独立） | 厂商是<b>静态单例</b>语义：`start()` 把 `this` 写进 `XxlJobExecutor.xxlJobExecutor`，第二次 `start()` 会覆盖前一个的身份（`exe.txt` 一手读数） | 一个 Spring 上下文里装两份 xxljob 装配是<b>不合法</b>形态（§0.3-6 的自动装配互斥在这里有第二条独立理由）。`XS6` 的 `destroy` 只调一次，也是同一条前提的另一面 |
| 8 | 有没有对外身份 | 没有：不监听端口，不出现在任何注册表里，进程之间互不可见 | 有：向 admin 注册 `address`（`http://{ip}:{port}/`），端口缺省由厂商 `getAvailablePort(9999)` 从 9999 起找空闲（`RegistryRequest` 与 `getAddress()` 的一手字节码，见 `42a` 取证表） | 换到 xxljob 就多了一个必须放行的入站端口（admin → 执行器）。框架<b>刻意不碰</b> `ip`/`address` 两项（`XS8`），所以这件事完全交给部署方：容器网络、多网卡、`NET_ADDRESS` 一律由厂商自己合成 |
| 9 | 停机做了什么 | `scheduler.shutdown()` 关自己的线程池，之后拒绝新注册（`RS10`） | 除了关自己的接缝，还向 admin 发 `registryRemove`（<b>无条件</b>，`cyclic.txt`/`S5-21`）——admin 那边当场看不到这台执行器 | xxljob 侧停机是<b>对外可见事件</b>，spring 侧不是。滚动发布时 xxljob 会有一小段"任务无处可调"的窗口，需要 admin 侧的重试/告警配置来兜（框架不假装能消掉它） |

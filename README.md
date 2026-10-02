# myddd-framework

轻量级 DDD 后端脚手架框架仓：内核契约层 + 领域装配 + 适配器组件（14 个 jar 模块 + 1 个 BOM + 1 套本地开发环境正本），BOM 统一版本，产物计划发布私服供业务工程使用（私服地址尚未接入，见「发布」）。

## 模块矩阵

| 模块 | 说明 | 状态 |
|---|---|---|
| `myddd-dependencies` | 版本唯一真源 BOM（devbox 镜像 tag 属性定义在父 POM，供 Plan 3 的 test-starter 资源过滤消费） | ✅ |
| `myddd-kernel-common` | 统一响应 / 错误码与异常 / 分页模型 / traceId | ✅ |
| `myddd-kernel-ddd` | DDD 战术基类（值对象 / 实体 / 聚合根 / 领域事件 / 仓储标记） | ✅ |
| `myddd-kernel-port` | 能力端口与注解：消息 / 任务 / 韧性 / 鉴权 / 一致性；零三方依赖（只有接口、注解与不可变消息模型） | ✅ |
| `myddd-kernel-extension` | 扩展点注册表：`@ExtensionPoint` / `@Extension` 的场景匹配、优先级与启动期诊断 | ✅ |
| `myddd-kernel-statemachine` | 零依赖状态机：定义期校验（可达性 / 终态）+ 运行时驱动（`fire` / `canFire` / 持久化恢复） | ✅ |
| `myddd-kernel-rule` | 轻量规则引擎：`Rule` / `RulePackage` 与 `RuleRegistry` 的匹配与执行语义 | ✅ |
| `myddd-domain-starter` | 领域装配：`@Domain` 模块扫描、进程内领域事件总线（事务阶段协作）、Outbox 可靠投递（SPI + 投递器；持久化实现由 Plan 3 的 `persistence-starter` 提供） | ✅ |
| `myddd-adapters/myddd-archunit` | 架构守卫规则库：设计文档 §5.2 的 7 条红线拆成 11 条可执行规则，业务工程一行接入 | ✅ |
| `myddd-adapters/myddd-web-starter` | Web 适配：异常 → `Response` 全局翻译、traceId 过滤器、Jackson 3 序列化约定、i18n 消息源、springdoc 装配（5 个自动配置类，各带独立开关） | ✅ |
| `myddd-adapters/myddd-persistence-starter` | 持久化：MyBatis-Plus 拦截器（分页 / 乐观锁 / 防全表更新）、审计字段填充、命名数据源与 `@TargetDataSource` 路由、Outbox 的 JDBC 存储与死信读侧、代理聚合根解包（R1）。框架自己发出的 SQL 一律**方言无关**：分页子句按连接的数据库产品名在 MySQL 形态与 ANSI 形态之间选，行上限走 JDBC 标准 `setMaxRows`，认领用单条 CAS `update` 而不是 `for update skip locked` | ✅ |
| `myddd-adapters/myddd-cache-starter` | 缓存：Caffeine L1 + Redis L2 两级缓存接入 Spring Cache，空值缓存、按缓存名 TTL、启动期配置校验 | ✅ |
| `myddd-adapters/myddd-consistency-starter` | 一致性：Redisson 分布式锁（`@DistributedLock` 切面，租约 / watchdog 语义见 R3）、幂等（`@Idempotent` 切面 + Redis / DB 双存储，缺省键摘要见 R2） | ✅ |
| `myddd-adapters/myddd-feign-starter` | 出站调用：traceId 透传拦截器 + `ErrorDecoder` 把远端 `Response` 失败还原成 `BizException` | ✅ |
| `myddd-test-starter` | 测试基座：`@MydddIntegrationTest` 前置、Testcontainers 容器与镜像常量（`DevboxImages`，值来自父 POM 资源过滤）、测试后数据复位 | ✅ |
| `myddd-devbox`（**非 Maven 模块**） | 本地开发环境正本：`compose.yaml`（8 服务 / 5 profile）+ 单一入口 `devbox.sh` + 两支零依赖守卫（`verify-images.sh` 管镜像 tag 漂移，`check-compose.sh` 管编排文本卫生） | ✅ |

> 后续计划追加：MQ / Job / 限流 / 鉴权的双适配实现（Plan 4）、业务模板仓 `myddd-template`（Plan 5）。本仓的适配器面已经闭合。

## 架构守卫

业务工程以 `test` 作用域引入 `myddd-archunit`，在测试里一行接入（规则集对应设计文档 §5.2）：

```java
@Test
void architecture() {
    MydddArchitectureSpec.verify("com.deep361.order");
}
```

- 覆盖：领域纯净红线（领域层禁 Spring/MyBatis/Jackson/JPA/事务注解等）、分层依赖方向、契约接口必须被 Controller 实现、仓储接口与传输对象命名后缀、模块级与包级循环依赖；
- 只分析生产代码（默认排除测试类）；某一层尚不存在时对应规则不判失败，因此分层未建全的项目也能提前接入，层一出现即生效；
- 断言失败信息含违规类全名与设计文档条款号，照提示改代码即可；确有正当例外时用规则自带的 `.ignoreDependency(...)` 在业务工程内显式豁免（豁免即留痕）。

## 环境要求

- **JDK 21**：本机路径记录在 `.jdk21-home`（gitignored；该文件是机器本地路径记录，由每台开发机自行生成、不入库，CI 通过 `setup-java` 获取 JDK 21，不依赖它）。构建命令：
  `JAVA_HOME="$(cat .jdk21-home)" ./mvnw clean verify`
- **Docker**：只有集成测试（`*IT`）与 `myddd-devbox` 需要。**没有 Docker 也要全绿**：`@MydddIntegrationTest` 类走 maven-failsafe，容器缺失时整类干净跳过，`./mvnw clean install` 仍然 BUILD SUCCESS；devbox 的两支守卫脚本（`verify-images.sh` / `check-compose.sh`）一律零 Docker 依赖。

## 构建与测试

```bash
JAVA_HOME="$(cat .jdk21-home)" ./mvnw -B -ntp clean verify
```

JaCoCo 覆盖率门槛在 verify 阶段强制：内核模块行覆盖与分支覆盖均 ≥80%，其余模块两项均 ≥60%（`myddd.jacoco.minimum` 属性，内核模块在自身 POM 覆写为 `0.80`）。注意：未产生测试执行数据的模块 JaCoCo 会跳过检查，CI 另做四项守卫（见本仓 CI 工作流）：① 覆盖率产物存在性；② 门槛数值（XML 级校验）——内核模块的阈值声明（含 profile 覆写）不得低于 `0.80`，父 POM 的 check 规则必须同时含 LINE 与 BRANCH 两档 `COVEREDRATIO` 且阈值走 `myddd.jacoco.minimum` 属性；③ 反绕过——任何 POM 不得硬编码门槛，或用 `<skip>true</skip>`／全吞 `<exclude>`／模块级 `<rules>` 关掉门槛；④ **IT 真实执行断言**——从 failsafe 报告里逐个 `<testsuite>` 读 `tests` / `skipped`，要求"有 IT 文件、有执行总数、`skipped` 必须为 0"。第 ④ 项专为堵住最阴的失败模式：CI runner 上 Docker 不可用时 IT 被静默跳过，本地与 CI 双双"全绿"而集成测试从未跑过。

构建之前还有两支零依赖守卫先判红（`devbox 镜像 tag ↔ 父 POM 属性一致性`、`devbox compose 卫生检查`，见「本地开发环境」）；构建之后是三条 BOM 契约：版本锁步校验（BOM 与父 POM 同版本）、BOM 消费端契约回归（`tools/bom-smoke` 逐坐标 `check`）、BOM 消费端坐标可解析性（`dependency:resolve`，因为"版本插值正确"≠"构件真的存在"）。

## 配置参考

下表逐条从各 `*Properties.java` 源码抽取（前缀 / 键 / 类型 / 代码里的初始值）。**开关语义有两条不同的缺省**：模块级 `enabled` 一律是"默认开、写 `false` 才关"（`matchIfMissing = true`）；而 Outbox 相关的两处（`myddd.domain.outbox.enabled`、`myddd.persistence.outbox.enabled`）是"默认关、必须显式写 `true`"——可靠投递会起后台线程与轮询，不该在业务方没意识到时悄悄启动。

### `myddd-domain-starter`

| 键 | 类型 | 缺省 | 说明 |
|---|---|---|---|
| `myddd.domain.scan-packages` | `List<String>` | 空 | 扫描 `@Domain` 领域模块的基础包（含子包）；为空时回退到 Spring Boot 自动配置包 |
| `myddd.domain.event.async.core-pool-size` | `int` | 4 | `@DomainEventListener(async = true)` 事件池的核心线程数 |
| `myddd.domain.event.async.max-pool-size` | `int` | 16 | 事件池最大线程数 |
| `myddd.domain.event.async.queue-capacity` | `int` | 1000 | 事件池队列容量 |
| `myddd.domain.event.async.keep-alive` | `Duration` | 60s | 事件池空闲存活 |
| `myddd.domain.event.async.shutdown-timeout` | `Duration` | 30s | 应用关闭时等待在途异步监听器收尾的上限 |
| `myddd.domain.outbox.enabled` | `boolean` | **无（须显式 `true`）** | 装配 Outbox 投递器（后台轮询 + 退避重试） |
| `myddd.domain.outbox.batch-size` | `int` | 50 | 每轮取走的待发条数 |
| `myddd.domain.outbox.max-attempts` | `int` | 5 | 投递尝试上限，超过即转死信 |
| `myddd.domain.outbox.lease-duration` | `Duration` | 30s | 单条租约：多实例并发投递的互斥窗口 |
| `myddd.domain.outbox.poll-interval` | `Duration` | 5s | 轮询节拍 |
| `myddd.domain.outbox.shutdown-timeout` | `Duration` | 10s | 停止时等待在途投递收尾的上限 |
| `myddd.domain.outbox.backoff.initial` | `Duration` | 1s | 首次退避 |
| `myddd.domain.outbox.backoff.multiplier` | `double` | 2.0 | 退避倍数 |
| `myddd.domain.outbox.backoff.max` | `Duration` | 1m | 退避封顶 |
| `myddd.domain.outbox.backoff.jitter-ratio` | `double` | 0.2 | 抖动比例（避免多实例同拍重试） |

### `myddd-web-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.web.enabled` | `boolean` | true（关掉即不装配本 starter 任何 bean） |
| `myddd.web.trace.enabled` | `boolean` | true |
| `myddd.web.json.enabled` | `boolean` | true |
| `myddd.web.i18n.enabled` | `boolean` | true |
| `myddd.web.i18n.default-locale` | `String` | `zh-CN` |
| `myddd.web.openapi.enabled` | `boolean` | true |
| `myddd.web.openapi.title` | `String` | `myddd 应用` |
| `myddd.web.openapi.description` | `String` | 空串 |
| `myddd.web.openapi.version` | `String` | `1.0.0` |

### `myddd-persistence-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.persistence.enabled` | `boolean` | true |
| `myddd.persistence.interceptor.pagination.enabled` | `boolean` | true |
| `myddd.persistence.interceptor.pagination.db-type` | `DbType` | 空＝**按连接的数据库自动探测**（写 `MYSQL`／`POSTGRE_SQL` 等枚举名可钉死；配成不存在的枚举名会启动失败） |
| `myddd.persistence.interceptor.pagination.max-limit` | `long` | 1000 |
| `myddd.persistence.interceptor.optimistic-lock.enabled` | `boolean` | true |
| `myddd.persistence.interceptor.block-attack.enabled` | `boolean` | true（禁止无条件 update/delete） |
| `myddd.persistence.audit.enabled` | `boolean` | true |
| `myddd.persistence.audit.unknown-principal` | `String` | `system`（取不到操作人时的占位） |
| `myddd.persistence.default-datasource` | `String` | `primary` |
| `myddd.persistence.datasources.<name>.url` | `String` | 无（每个命名数据源必填） |
| `myddd.persistence.datasources.<name>.username` | `String` | 无 |
| `myddd.persistence.datasources.<name>.password` | `String` | 无 |
| `myddd.persistence.datasources.<name>.driver-class-name` | `String` | 无 |
| `myddd.persistence.outbox.enabled` | `boolean` | **无（须显式 `true`）** |
| `myddd.persistence.outbox.table` | `String` | `myddd_outbox` |
| `myddd.persistence.outbox.dead-letter.query-limit` | `int` | 200 |

### `myddd-cache-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.cache.enabled` | `boolean` | true |
| `myddd.cache.type` | `Type` | `MULTI`（可选 `CAFFEINE`＝仅进程内 / `MULTI`＝L1+L2 两级） |
| `myddd.cache.key-prefix` | `String` | `myddd:cache`（L2 键前缀段） |
| `myddd.cache.cache-null-values` | `boolean` | false |
| `myddd.cache.cache-names` | `List<String>` | 空＝按名字动态创建（与 Spring Cache 缺省一致） |
| `myddd.cache.caffeine.max-size` | `long` | 1000 |
| `myddd.cache.caffeine.expire-after-write` | `Duration` | 10m（L1 写后过期，同时是跨节点陈旧的上界） |
| `myddd.cache.redis.ttl` | `Duration` | 30m |
| `myddd.cache.redis.ttl-per-cache.<cacheName>` | `Map<String, Duration>` | 空（按缓存名覆盖 `ttl`，值必须为正时长） |

### `myddd-consistency-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.consistency.enabled` | `boolean` | true |
| `myddd.consistency.lock.enabled` | `boolean` | true |
| `myddd.consistency.lock.key-prefix` | `String` | `myddd:lock` |
| `myddd.consistency.lock.watchdog-timeout` | `Duration` | 30s（Redisson 续租节拍，与 Redisson 自身缺省一致） |
| `myddd.consistency.idempotent.enabled` | `boolean` | true（关掉则不装配 `IdempotentPort`，配置校验同样跳过这一段） |
| `myddd.consistency.idempotent.key-prefix` | `String` | `myddd:idempotent` |
| `myddd.consistency.idempotent.store` | `Store` | `REDIS`（可选 `DB`＝无 Redis 或幂等记录需与业务同库可查） |
| `myddd.consistency.idempotent.db.table` | `String` | `myddd_idempotent_record` |

### `myddd-feign-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.feign.enabled` | `boolean` | true |
| `myddd.feign.trace.enabled` | `boolean` | true |
| `myddd.feign.error-decoder.enabled` | `boolean` | true |

### `myddd-test-starter`

| 键 | 类型 | 缺省 |
|---|---|---|
| `myddd.test.reset.enabled` | `Boolean` | true（每个测试开始前复位数据） |
| `myddd.test.reset.exclude` | `List<String>` | 空（全局不参与清空的表名，与 `@DataReset` 的 `exclude` 合并） |
| `myddd.test.reset.datasource` | `String` | 空（缺省时要求容器里只有唯一 `DataSource` 或 `@Primary`） |

## 本地开发环境（devbox）

`myddd-devbox/` 不是 Maven 模块，而是一套编排正本 + 守卫：镜像 tag 的唯一真源仍是根 `pom.xml` 的 `myddd.devbox.image.*`（7 个属性），`compose.yaml` 与 `myddd-test-starter` 的镜像模板各消费它一次。业务工程起本地依赖只有一个入口：

```bash
bash myddd-devbox/devbox.sh up mysql redis      # 按需再加 kafka / rocketmq / job / limiter profile
```

细节（服务清单、端口变量、seed 脚本、故障排查）见 `myddd-devbox/README.md`。改镜像 tag 必须两处同改，否则 CI 在构建前就红：

```bash
bash myddd-devbox/verify-images.sh          # 镜像 tag ↔ 父 POM 真源（8 服务逐一对齐，可加 --docker 核验注册表）
bash myddd-devbox/check-compose.sh          # 编排文本卫生：明文凭据 / 野变量 / 写死宿主端口 / 悬空 depends_on
```

两支守卫都是纯 bash + awk、零 Docker、零 Maven，且遵循同一条裁定：**解析式一条都没命中不算通过**（空集一律判红），否则守卫退化成了沉默放行。

## 版本真源

- 全部三方依赖版本与 devbox 镜像 tag 定义在根 `pom.xml`（`myddd-parent`）的 properties；
- 对外 BOM 构件为 `myddd-dependencies`（继承父 POM，业务工程 `import` 即获得全套版本）；
- BOM 仅用于 `import`（dependencyManagement）方式引用：若把父 POM 当作 `<parent>` 继承，会连同 enforcer 规则（以及 pluginManagement 中的插件与覆盖率配置）一起带进业务构建，不要这样用。
- 父 POM 耦合：对外 BOM 的 POM 声明了 `<parent>myddd-parent</parent>`，业务工程从仓库解析 BOM 时必须同时能解析到 `myddd-parent`；发布务必包含根聚合 POM（不要只 `-pl myddd-dependencies deploy`），否则业务侧会报 `Non-resolvable parent POM for org.deep361.myddd:myddd-dependencies`。
- 业务工程不能用重定义属性（如 `<resilience4j.version>`）的方式覆盖受管版本：属性只在本仓内生效，必须在自身 `dependencyManagement` 中重新声明该坐标的版本——工程自身的 DM 条目优先于 `import` 进来的 BOM。

## 发布

发布私服时使用 `-Prelease` profile（默认 opt-in，不启用）：额外产出 sources / javadoc 构件随主构件发布。

私服地址（`distributionManagement`）尚未接入：待接入方提供 Nexus 坐标后配置，在此之前 `deploy` 不可用（本地 `install` 不受影响）。

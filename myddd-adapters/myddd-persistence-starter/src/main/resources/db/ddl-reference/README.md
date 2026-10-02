# 框架表参考 DDL

这里的两份 `V*.sql` 是**参考 DDL**，不是可执行迁移。框架刻意不在应用启动时替你建表：

- 迁移序列属于业务工程，框架插入一个 `V1__` 必然与你自己那个 `V1__` 撞版本号（Flyway 视重复版本为硬失败）；
- 启动期改别人的 schema 出问题时，故障信息只有一句迁移报错，排查方向却是"框架动了我的库"；
- 配了多数据源（`myddd.persistence.datasources.*`）之后，"框架表该建在哪个库"没有唯一答案，只有业务知道。

## 怎么用（两步）

1. 把需要的 `.sql` 复制进你自己的迁移目录（Flyway 缺省 `classpath:db/migration`）。
2. 按你工程的迁移序列改名，保留 `myddd_` 这一段模块名，例如 `V1__myddd_outbox.sql` 改名为 `V7__myddd_outbox.sql`。

**不要**把 `db/ddl-reference` 加进 `spring.flyway.locations`：那个目录里的文件名也是 `V{序号}__` 形态，一旦进了扫描位置就会和你的迁移抢版本号，而它本该只是给人复制用的样板。

## 两份脚本对应的配置

| 表 | 谁在用 | 相关配置键 |
|---|---|---|
| `myddd_outbox` | `myddd.persistence.outbox.enabled=true` 时的 `JdbcOutboxStore` | `myddd.persistence.outbox.table`（默认 `myddd_outbox`） |
| `myddd_idempotent_record` | `myddd.consistency.idempotent.store=db` 时的 DB 幂等实现 | `myddd.consistency.idempotent.db.table`（默认 `myddd_idempotent_record`） |

改表名只改配置键即可（框架的 SQL 按配置拼表名）；**列名不可改**，它们是 `OutboxRecord` 与 `IdempotentPort` 的投影。

## 改动这份 schema 的纪律（spec §5.1 的迁移约定）

- 只允许向后兼容变更：禁删列、禁改窄列宽或类型；加列必须可空或有默认值。
- 因此 `status` 用 `varchar` 而不是 MySQL 的 `enum`：新增一个投递状态不该要求一次改列。
- 时间列一律 `datetime(3)`：`datetime` 的默认精度是 0 秒，写 `Instant` 会被静默四舍五入到秒，直接破坏"同毫秒内先进先出"和租约到期判定。
- 表必须显式写 `engine`、`charset`、`collate`：不依赖服务器缺省，避免同一份 DDL 在两套环境上建出两套索引行为。
- `CHECK` 约束需要 MySQL 8.0.16 及以上（更早版本解析后忽略），与 `utf8mb4_0900_ai_ci` 同为 MySQL 8 特性；框架的 devbox 与 CI 用 `mysql:8.4`。

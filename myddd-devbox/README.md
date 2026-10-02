# myddd-devbox：本地中间件环境正本

框架仓的 canonical 本地环境。**非 Maven 模块**，不参与 reactor 构建。

## 唯一入口

```bash
cp myddd-devbox/.env.example myddd-devbox/.env   # 填 MYSQL_ROOT_PASSWORD（仅本机使用，不入库）
bash myddd-devbox/devbox.sh up                   # 只起基座：MySQL + Redis
```

其余动作一律走同一个脚本，不要直接敲 `docker compose`：

| 命令 | 作用 |
| --- | --- |
| `devbox.sh up [档位...]` | 启动并等待健康检查通过（`up -d --wait`）后打印端点表 |
| `devbox.sh ps` | 查看状态与健康度 |
| `devbox.sh logs [服务名]` | 跟随日志 |
| `devbox.sh down` | 停止并移除容器，**保留数据卷** |
| `devbox.sh reset --yes` | 停止并删除数据卷（不可撤销，必须显式 `--yes`） |
| `devbox.sh verify` | 校验 compose 的镜像 tag 与父 POM 属性一致 |

档位（对应 `compose.yaml` 的 profile，可一次给多个，或给 `all`）：

| 档位 | 起的服务 | 谁需要它 |
| --- | --- | --- |
| `kafka` | kafka（KRaft 单节点） | MQ 适配器之一 |
| `rocketmq` | namesrv + broker + 控制台 | MQ 适配器之二 |
| `job` | xxl-job-admin | 分布式任务适配器 |
| `limiter` | sentinel-dashboard | 限流适配器 |

MySQL/Redis 是基座，任何 `up` 都会带起来。集成测试不用 compose：`myddd-test-starter` 用 Testcontainers 单独编排，两边只共用**镜像 tag 的真源**。

## 镜像版本的唯一真源

真源是仓根 `pom.xml` 的 `myddd.devbox.image.*` 属性（当前 7 个），消费侧有两处：

- `myddd-test-starter` 的 `myddd-devbox-images.properties`（Maven 资源过滤填占位符，供 Testcontainers 用）；
- 本目录 `compose.yaml`（字面量 + 每个服务的 `labels.myddd.devbox.image-key` 声明它消费哪个属性）。

两侧都可能有手写漂移，所以有一道守卫：`bash myddd-devbox/verify-images.sh`（不需要 Docker，CI 每次都跑）。它会拒绝：单侧改 tag、服务漏声明 `image-key`、`image-key` 指向不存在的属性、POM 属性无服务消费、模板里写死值、解析式失效导致"空集当通过"。

## 前置：`job` 档的表结构

XXL-Job 的建表脚本归其发行包所有，本仓不复制一份（避免两处 DDL 漂移）。要用 `job` 档：

1. 取与父 POM `myddd.devbox.image.xxljob-admin` **同版本**的 XXL-Job 发行包；
2. 把其中 `doc/db/tables_xxl_job.sql` 复制为 `myddd-devbox/seed/mysql/03-xxl-job.sql`（该文件不入库）；
3. `bash myddd-devbox/devbox.sh down && bash myddd-devbox/devbox.sh up job`——MySQL 首次初始化数据卷时会自动执行它。

`devbox.sh up job` 在文件缺失时会直接拒绝并给出上述步骤；表结构已用别的方式备好时用 `up job --skip-schema-check`。

## 端口与连接

端点表在每次 `up` 后打印；宿主端口全部可在 `.env` 改。应用侧本地连接串形如
`jdbc:mysql://127.0.0.1:3306/myddd_app?...`，账号用 root 或 `MYDDD_DB_PASSWORD` 建出的 `myddd`。
业务表由应用自己的 Flyway 迁移创建，`seed/mysql/` 只负责建库与字符集。

## 已知边界（诚实清单）

1. **镜像 tag 的存在性尚未在注册表核验**：`verify-images.sh --docker` 已实现逐 tag `docker manifest inspect`，但本机无 Docker，尚未跑过。装了 Docker 后执行 `bash myddd-devbox/verify-images.sh --docker` 即可定稿。
2. **compose 的运行时行为未实测**：本目录的结构性校验（服务/profile/端口/依赖/无明文口令）与守卫、脚本入口行为都已在无 Docker 环境实测；`docker compose config` 与各服务真正拉起需 Docker。
3. **RocketMQ 的 `brokerIP1 = 127.0.0.1`** 假设客户端在宿主机（Docker Desktop 场景）。Linux 上要容器内客户端直连时，改 `config/rocketmq/broker.conf` 为 `rocketmq-broker` 并直连 10911。
4. 控制台类服务（RocketMQ dashboard / XXL-Job / Sentinel）自带的登录口令属镜像自身行为，本目录不给它们设口令，因此**只可在本机回环端口上用，不要暴露到公网**。

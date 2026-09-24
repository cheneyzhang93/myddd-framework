# myddd-framework

轻量级 DDD 后端脚手架框架仓：内核契约层 + 领域装配 + 适配器组件，BOM 统一版本，产物计划发布私服供业务工程使用（私服地址尚未接入，见「发布」）。

## 模块矩阵

| 模块 | 说明 | 状态 |
|---|---|---|
| `myddd-dependencies` | 版本唯一真源 BOM（devbox 镜像 tag 属性定义在父 POM，供 Plan 3 的 test-starter 资源过滤消费） | ✅ |
| `myddd-kernel-common` | 统一响应 / 错误码与异常 / 分页模型 / traceId | ✅ |
| `myddd-kernel-ddd` | DDD 战术基类（值对象 / 实体 / 聚合根 / 领域事件 / 仓储标记） | ✅ |

> 后续计划持续追加：kernel 端口与扩展机制、领域装配（Outbox）、各能力适配器、测试基座等。

## 环境要求

- **JDK 21**：本机路径记录在 `.jdk21-home`（gitignored；该文件是机器本地路径记录，由每台开发机自行生成、不入库，CI 通过 `setup-java` 获取 JDK 21，不依赖它）。构建命令：
  `JAVA_HOME="$(cat .jdk21-home)" ./mvnw clean verify`
- **Docker**：仅集成测试与 devbox 需要（Plan 3 起，见 `myddd-devbox/`）。

## 构建与测试

```bash
JAVA_HOME="$(cat .jdk21-home)" ./mvnw -B -ntp clean verify
```

JaCoCo 覆盖率门槛在 verify 阶段强制：内核/领域模块行覆盖 ≥80%，其余 ≥60%。注意：未产生测试执行数据的模块 JaCoCo 会跳过检查，CI 会额外断言覆盖率产物存在（见本仓 CI 工作流）。

## 版本真源

- 全部三方依赖版本与 devbox 镜像 tag 定义在根 `pom.xml`（`myddd-parent`）的 properties；
- 对外 BOM 构件为 `myddd-dependencies`（继承父 POM，业务工程 `import` 即获得全套版本）；
- BOM 仅用于 `import`（dependencyManagement）方式引用：若把父 POM 当作 `<parent>` 继承，会连同 enforcer 规则（以及 pluginManagement 中的插件与覆盖率配置）一起带进业务构建，不要这样用。
- 父 POM 耦合：对外 BOM 的 POM 声明了 `<parent>myddd-parent</parent>`，业务工程从仓库解析 BOM 时必须同时能解析到 `myddd-parent`；发布务必包含根聚合 POM（不要只 `-pl myddd-dependencies deploy`），否则业务侧会报 `Non-resolvable parent POM for org.deep361.myddd:myddd-dependencies`。
- 业务工程不能用重定义属性（如 `<resilience4j.version>`）的方式覆盖受管版本：属性只在本仓内生效，必须在自身 `dependencyManagement` 中重新声明该坐标的版本——工程自身的 DM 条目优先于 `import` 进来的 BOM。

## 发布

发布私服时使用 `-Prelease` profile（默认 opt-in，不启用）：额外产出 sources / javadoc 构件随主构件发布。

私服地址（`distributionManagement`）尚未接入：待接入方提供 Nexus 坐标后配置，在此之前 `deploy` 不可用（本地 `install` 不受影响）。

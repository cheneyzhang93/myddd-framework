# myddd-framework

轻量级 DDD 后端脚手架框架仓：内核契约层 + 领域装配 + 适配器组件，BOM 统一版本，产物发布私服供业务工程使用。

## 模块矩阵

| 模块 | 说明 | 状态 |
|---|---|---|
| `myddd-dependencies` | 版本唯一真源 BOM（含 devbox 镜像 tag） | 任务 2 交付 |
| `myddd-kernel-common` | 统一响应 / 错误码与异常 / 分页模型 / traceId | 任务 3–9 交付 |
| `myddd-kernel-ddd` | DDD 战术基类（值对象 / 实体 / 聚合根 / 领域事件 / 仓储标记） | 任务 10–14 交付 |

> 后续计划持续追加：kernel 端口与扩展机制、领域装配（Outbox）、各能力适配器、测试基座等。

## 环境要求

- **JDK 21**：本机路径记录在 `.jdk21-home`（gitignored；该文件是机器本地路径记录，由每台开发机自行生成、不入库，CI 通过 `setup-java` 获取 JDK 21，不依赖它）。构建命令：
  `JAVA_HOME="$(cat .jdk21-home)" ./mvnw clean verify`
- **Docker**：仅集成测试与 devbox 需要（Plan 3 起，见 `myddd-devbox/`）。

## 构建与测试

```bash
JAVA_HOME="$(cat .jdk21-home)" ./mvnw -B -ntp clean verify
```

JaCoCo 覆盖率门槛在 verify 阶段强制：内核/领域模块行覆盖 ≥80%，其余 ≥60%。注意：未产生测试执行数据的模块 JaCoCo 会跳过检查，CI 会额外断言覆盖率产物存在（见工作流）。
发布私服时使用 `-Prelease` profile（默认 opt-in，不启用）：额外产出 sources / javadoc 构件随主构件发布。

## 版本真源

- 全部三方依赖版本与 devbox 镜像 tag 定义在根 `pom.xml`（`myddd-parent`）的 properties；
- 对外 BOM 构件为 `myddd-dependencies`（继承父 POM，业务工程 `import` 即获得全套版本）；
- BOM 仅用于 `import`（dependencyManagement）方式引用：若把父 POM 当作 `<parent>` 继承，会连同 enforcer 规则与覆盖率门槛一起带进业务构建，不要这样用。

# myddd-framework

轻量级 DDD 后端脚手架框架仓：内核契约层 + 领域装配 + 适配器组件，BOM 统一版本，产物计划发布私服供业务工程使用（私服地址尚未接入，见「发布」）。

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

> 后续计划持续追加：测试基座与 devbox、web / persistence / cache / consistency / feign 适配器、MQ / Job / 限流 / 鉴权双适配、模板仓。

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
- **Docker**：仅集成测试与 devbox 需要（Plan 3 起，见 `myddd-devbox/`）。

## 构建与测试

```bash
JAVA_HOME="$(cat .jdk21-home)" ./mvnw -B -ntp clean verify
```

JaCoCo 覆盖率门槛在 verify 阶段强制：内核模块行覆盖与分支覆盖均 ≥80%，其余模块两项均 ≥60%（`myddd.jacoco.minimum` 属性，内核模块在自身 POM 覆写为 `0.80`）。注意：未产生测试执行数据的模块 JaCoCo 会跳过检查，CI 另做三项守卫（见本仓 CI 工作流）：① 覆盖率产物存在性；② 门槛数值（XML 级校验）——内核模块的阈值声明（含 profile 覆写）不得低于 `0.80`，父 POM 的 check 规则必须同时含 LINE 与 BRANCH 两档 `COVEREDRATIO` 且阈值走 `myddd.jacoco.minimum` 属性；③ 反绕过——任何 POM 不得硬编码门槛，或用 `<skip>true</skip>`／全吞 `<exclude>`／模块级 `<rules>` 关掉门槛。

## 版本真源

- 全部三方依赖版本与 devbox 镜像 tag 定义在根 `pom.xml`（`myddd-parent`）的 properties；
- 对外 BOM 构件为 `myddd-dependencies`（继承父 POM，业务工程 `import` 即获得全套版本）；
- BOM 仅用于 `import`（dependencyManagement）方式引用：若把父 POM 当作 `<parent>` 继承，会连同 enforcer 规则（以及 pluginManagement 中的插件与覆盖率配置）一起带进业务构建，不要这样用。
- 父 POM 耦合：对外 BOM 的 POM 声明了 `<parent>myddd-parent</parent>`，业务工程从仓库解析 BOM 时必须同时能解析到 `myddd-parent`；发布务必包含根聚合 POM（不要只 `-pl myddd-dependencies deploy`），否则业务侧会报 `Non-resolvable parent POM for org.deep361.myddd:myddd-dependencies`。
- 业务工程不能用重定义属性（如 `<resilience4j.version>`）的方式覆盖受管版本：属性只在本仓内生效，必须在自身 `dependencyManagement` 中重新声明该坐标的版本——工程自身的 DM 条目优先于 `import` 进来的 BOM。

## 发布

发布私服时使用 `-Prelease` profile（默认 opt-in，不启用）：额外产出 sources / javadoc 构件随主构件发布。

私服地址（`distributionManagement`）尚未接入：待接入方提供 Nexus 坐标后配置，在此之前 `deploy` 不可用（本地 `install` 不受影响）。

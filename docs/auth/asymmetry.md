# auth 两家的不对称清单（sa-token / spring-security）

本清单与 `myddd-test-starter` 的 `AuthExpectationTable` 双向闭合：表里每行的 `asymmetryRef`
必须能在下面找到同名的 `## A-n`，每条 `## A-n` 也必须被表里某行引用（`EQ-T3`/`EQ-T5` 各钉一个方向）。
**等价性的定义只有三条**（端口四方法同输入同答案 / 身份字符串允许不同源 / `logout` 只断端口读数），
凡不在那三条之内的差异，一律登记在这里而不是被写成断言。

## A1 — 口令失败经过的是两条不同的厂商分支，端口面只有一条

- **sa-token 侧**：`CredentialVerifier` 返回 `empty` ⇒ 端口自己抛 `BizException(UNAUTHORIZED)`。
  框架不区分"用户不存在"与"口令错误"，因为**根本没有厂商分支**（口令校验完全在业务给的 verifier 里）。
- **spring-security 侧**：`DaoAuthenticationProvider` 抛 `BadCredentialsException` ⇒ 端口翻成
  同一个 `BizException(UNAUTHORIZED)`。⚠️ 这一家的缺省 `hideUserNotFoundExceptions=true` 会把
  `UsernameNotFoundException` **并进** `BadCredentialsException`，所以"用户名不存在"与"口令错"
  在厂商层是两条路径、在端口层是一条。
- **业务该按哪条写**：只认 `UNAUTHORIZED` 这一个对外语义，不要试图从消息里区分两种失败——
  那是厂商内部状态，且换一家就没有了。
- 表里兑现处：`EQ2`（其 `brokenCredentials()` 的差别**只有口令**）。

## A2 — 身份字符串的来源数量不同

- **sa-token 侧**：`AuthUser.userId` 是 `loginId`，`username` 是我们自己写进 account session 的
  那一份（键名 `myddd.auth.satoken.username`）——**两个来源**。
- **spring-security 侧**：`userId == username == Authentication.getName()`——**一个来源**。
  `getName()` 的四条解析路径本轮逐条量过：`String` 主体→值本身、`UserDetails`→`getUsername()`、
  普通对象→`toString()`、主体为 `null`→**空串**而不是字面量 `"null"`。
- **业务该按哪条写**：需要"跨两家稳定的用户主键"时读 `userId`，并且**在 satoken 侧让 verifier 返回的
  `AuthUser` 里 `userId` 就是业务主键**；不要指望两家把 `username` 填成同一个东西，也不要在业务里
  写 `userId.equals(username)` 这种"看起来无害"的判断（satoken 侧一旦用户名与 loginId 不同就静默失效）。
- 表里兑现处：`EQ1`（那句"这一家自己声明的 knownUser()"就是为了拦住把它统一掉的顺手改法）。

## A3 — 「角色蕴含」只有一家有这个概念

- **sa-token 侧**：没有"角色层级"，权限就是一串精确名字（`StpInterface` 给什么就是什么）⇒
  `EQ7` 在本侧**表达不了**，表里写 `NOT_EXPRESSIBLE` 并指向本条。
- **spring-security 侧**：厂商有 `RoleHierarchy`（`RoleHierarchyImpl.fromHierarchy("ROLE_admin > ROLE_user")`），
  并且**它真的生效**——但只在厂商地盘上（过滤链、`AuthorizationManager` 那条线）生效。
  **本框架的端口不跟随它**：端口读的是 token 上物化出来的 authorities 全集（授予 `ROLE_admin` 之后
  `hasPermission("user:read")` 仍是 `false`，由 provider 模块的装配用例钉住）。
- **为什么这里不 fail-fast**：层级展开是 Spring Security 的合法能力，框架因为"端口不实现它"就让应用
  启动失败，等于禁止业务使用 Spring Security 的一个特性。代价明写在包说明里：用了层级的应用必须
  自己在 `UserDetails` 里把蕴含的角色**展开后**再交给我们，否则端口判定与厂商判定会给业务两个答案。
- **业务该按哪条写**：权限判定一律走端口的精确匹配；需要层级就在数据侧展开，或只走厂商那条链
  （过滤链/`@PreAuthorize`），**不要两条链混着用**。
- 表里兑现处：`EQ7`（satoken 列 `NOT_EXPRESSIBLE`；`EQ-T6` 保证被跳过的恰好是这一行、且只在 satoken 侧）。

## A4 — 权限数据的来源是进程级静态面 vs 每请求的 token

- **sa-token 侧**：权限来自 `SaManager` 里的 `StpInterface`（**进程级静态面**，业务自己实现并注册；
  框架不产实现）。风险两格：① 业务的实现返回 `null` 会被厂商判成接口契约违规、走 500 那一支；
  ② `StpUtil` 的权限串支持通配符写法，命中与否取决于厂商的匹配实现，而**框架桥不做第二层校验**。
- **spring-security 侧**：权限是登录那一刻物化在 token 上的 `SimpleGrantedAuthority` 集合，
  逐请求、无静态面；`SimpleGrantedAuthority` 在**构造时**就拒绝空白串。
- **业务该按哪条写**：按**精确权限名**写（`"order:read"` 这种），不要用通配符，也不要依赖"改一次
  权限源立刻对所有线程生效"——那是静态面才有的性质，两家行为不同。
- 表里兑现处：`EQ3`（两列各写"这一家把这份集合装进去的写法"，值只问命中/未命中）、`EQ4`（空白名必须抛，
  不许返回 `false`）。

## A5 — 登出撤销的东西不是一个量

- **sa-token 侧**：`logout()` 撤销的是**服务端会话**（有 DAO、有 token-to-session 映射；
  本线程读数为空只是表象，真效果是那枚 token 之后不能再换回身份）。
- **spring-security 侧**：`logout()` 清的是**当前线程的一份 `SecurityContext`**——本模块不持有会话存储，
  也不引 `spring-security-web`（判据：本 provider 在非 Web 线程也必须正确）。
  "同一枚凭据在另一次请求里还能不能用"这件事完全在框架之外。
- **业务该按哪条写**：把端口的 `logout()` 当作"丢掉当前身份"而不是"吊销凭据"。真需要吊销（踢人下线）
  时，satoken 侧可以直接用厂商面，security 侧必须有服务端会话存储——**那是一个功能，不是一个 provider
  差异**，别指望框架替你抹平它。
- 表里兑现处：`EQ5`/`EQ6`（两条都只断端口读数：身份与权限一起消失、无身份时是空操作）。

## A6 — 装配失败的话只有一句，异常类型由入口决定（钉类型是错的）

| 入口 | 抛什么 | 钉它的用例 |
|---|---|---|
| `ApplicationContextRunner` / 直接 `register` 装配类 | **裸** `FrameworkException`（栈：`ConditionEvaluator.shouldSkip` ← `AnnotatedBeanDefinitionReader.register`），经 `getStartupFailure()` 取 | satoken `AC4`/`AC6`/`AC7`、shell `AC5` |
| 选中一家 + 容器里没有 `CredentialVerifier` | **裸** `FrameworkException`（发生在 `afterSingletonsInstantiated`，与 import 期无关） | `CV1` |

- **共同点**：消息文本同一句、含键名 `myddd.auth.provider`、含两个合法值。
- **业务该按哪条写**：诊断只看**消息文本与根因链**，不要断"抛出的异常类型"——真应用走
  `@EnableAutoConfiguration` 时，Spring 的 import 处理会在外层再包一层（类型由入口决定，不由我们决定），
  测试里同理：`assertThatThrownBy(...).isInstanceOf(FrameworkException.class)` 在那种入口下会红。
- **不许把三种形状收敛成一种**：第一种是 Spring 在 import 期的统一包装，改它要动的是框架之外的东西；
  第三种是启动钩子的正常语义。三种形状都在文档里，比"看起来一致"值钱。
- 表里兑现处：`EQ8`（两家对同一份键的答复必须相同，且消息含彼此的名字）。

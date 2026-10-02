/**
 * {@code AuthPort} 的 Sa-Token 实现。
 *
 * <p><b>四个字段各有各的来源</b>（完整判据与探针读数在计划正文 Task 49 Step 0 第 4 条）：
 * {@code userId} 来自厂商会话，{@code roles}/{@code permissions} 来自<b>业务自己声明的</b>
 * {@code StpInterface}，{@code username} 来自我们写进 account session 的一个 {@code String}
 * （键 {@code SaTokenAuthPort.USERNAME_KEY}）。{@code username} 不走厂商的 {@code extra}：
 * 那是 {@code sa-token-jwt} 的能力，未集成时读它抛 {@code ApiDisabledException}（{@code code=11031}，
 * 探针 49a 一手）。写进 account session 而不是 token session：前者的 id 形状是
 * {@code satoken:login:session:<loginId>}（按人一份），后者是 {@code satoken:login:token-session:<uuid>}
 * （按设备一份，探针 49d）。
 *
 * <p><b>本包从不碰 {@code SaManager} 的任何 setter</b>（R15 的字面要求）：权限源的唯一注入者是
 * {@code sa-token-spring-boot4-starter} 里的 {@code SaBeanInject}，我们插一脚就会与业务那一个撞成
 * 多候选（它的 setter 是单参 {@code @Autowired}）。"业务没接权限源"这一格由
 * {@code config.StpInterfacePresenceGuard} 在启动期说，不由这里静默代劳。
 *
 * <p><b>没有 {@code stp} 包</b>：§1.1 的包树里那一格（provider 自己产 {@code @Bean StpInterface}）
 * 按 D-182 回写——计划正文里这一格在 Task 59 修正，代码里从一开始就不存在。
 */
package org.deep361.myddd.auth.satoken;

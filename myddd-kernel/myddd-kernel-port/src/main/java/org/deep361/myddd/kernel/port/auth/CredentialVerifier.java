package org.deep361.myddd.kernel.port.auth;

/**
 * 凭据校验接缝（R14）：把"这个用户名+密码到底对不对"留给业务回答，框架与 provider 都不代答。
 *
 * <p><b>为什么必须有这一个接缝</b>：{@link AuthPort#login(AuthCredentials)} 的实现契约承诺
 * "登录失败抛 {@code BizException(CommonErrorCode.UNAUTHORIZED)}"（{@code AuthPort.java:14}），
 * 而两家 provider 的 API 面并不对称。Spring Security 侧有现成内核（
 * {@code AuthenticationManager.authenticate(Authentication)} 会真校验凭据并抛
 * {@code AuthenticationException}）；Sa-Token 侧没有：一手字节码/API 取证
 * （{@code D:\tmp\p4probe\satoken\api4.txt}）显示 {@code StpUtil.login} 的全部重载是
 * {@code login(Object)}、{@code login(Object,String)}、{@code login(Object,boolean)}、
 * {@code login(Object,long)}、{@code login(Object,SaLoginParameter)}——<b>第一个参数永远是
 * loginId，收了就发 token，一个凭据都不校验</b>。没有本接缝，satoken 侧的 {@code login}
 * 就只能"谁来要 token 就给谁"，那是本框架最不能接受的静默失效形态。
 *
 * <p><b>失败表达只有一种</b>：校验不通过 ⇒ 抛
 * {@code BizException(CommonErrorCode.UNAUTHORIZED)}（401）。<b>不得返回 {@code null}</b>，
 * 也不得换一种异常类型：返回 null 会让"没人校验通过"与"校验通过但查不到用户"在两家的实现里各说各话
 * （R14 的原话是"接缝自身不引入第三种失败表达"），而这两格对前端的语义完全一样——都该跳登录页。
 * 成功一侧不需要本接口再设防：返回类型 {@link AuthUser} 的紧凑构造器已拒绝 {@code null}/空白
 * {@code userId}（一手：{@code AuthUser.java:16-27}），"校验通过但身份不明"在第一次构造时就炸。
 *
 * <p><b>为什么这里不写 {@code @throws} 引用异常类</b>：{@code myddd-kernel-port} 对
 * {@code myddd-kernel-common} 零依赖（一手：本模块 {@code pom.xml} 的 description
 * "错误码由适配器实现方抛出"，以及全模块 main 源码里 {@code kernel.common} 的 import 数为 0），
 * 所以 {@code BizException} 只能以文本形态出现在 javadoc 里——与 {@code AuthPort.java:14} 同一写法。
 *
 * <p><b>违约由谁兜底</b>：容器里出现两个实现时的消歧、以及"实现返回 null"的归一，都在 provider 侧
 * （Task 49/50 各一条用例）。理由不是省事：把这两格做成壳里的包装件，就得给 satoken provider 加一条
 * 指向壳的 compile 边（判据表见计划正文 Task 48 Step 6 开头，mq 侧那条边的理由在这里不成立），
 * 而这两件事在壳里也没有消费者——壳的切面只读
 * {@link AuthPort#currentUser()} 与 {@link AuthPort#hasPermission(String)}，一次 {@code login} 都不发。
 */
public interface CredentialVerifier {

    /**
     * 校验凭据并返回其对应的身份快照。
     *
     * @param credentials 登录凭据；其紧凑构造器已拒绝空白 {@code username}/{@code password}
     *                    （一手：{@code AuthCredentials.java:15-24}），本方法不必再判空
     * @return 校验通过的用户身份，不可为 {@code null}
     */
    AuthUser verify(AuthCredentials credentials);
}

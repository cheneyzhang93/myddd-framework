package org.deep361.myddd.auth.satoken;

import org.deep361.myddd.auth.satoken.testing.SaTokenRequestScope;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.deep361.myddd.testing.contract.AuthPortContractTestBase;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * {@link AuthPortContractTestBase} 的 Sa-Token 侧子类：八条跨厂商契约在这里第一次真跑。
 *
 * <p><b>本文件一条 {@code @Test} 都没有</b>，八条全部由基类继承——用例账里的 {@code +44} 对上
 * {@code @Test} 数的 {@code +36}，差额就是这 8 条。出现任何一条 {@code skipped} 就是有人把某条
 * {@code AUTH-C} 改成了 {@code assumeTrue}：探针 49d 已经证明整条链路在无 Servlet、无 Spring、无 Redis
 * 的环境里跑得通，没有任何一条可以理由性地跳过。
 *
 * <p><b>两道缝的实现</b>：{@code openRequestScope()}/{@code closeRequestScope()} 交给
 * {@link SaTokenRequestScope}（厂商自己的 ThreadLocal 模型盒）；{@code currentUserWithoutRequestScope()}
 * 不是"先关作用域再读"，而是<b>换一条线程读</b>——R9 描述的是批处理线程与 MQ 消费线程，那条线程
 * 从来没见过上下文，而不是"见过又被拆掉"。两种形状都成立，取后者是因为它贴近事故现场，
 * 并且顺手钉住 {@code modelBoxThreadLocal} 不是 {@code InheritableThreadLocal}
 * （一手：{@code staff.txt:3}，普通 {@code ThreadLocal}）。
 *
 * <p><b>校验器返回 {@code null} 而不是抛异常</b>：{@code AUTH-C2} 要求"口令错误 ⇒ 框架语义的 401"，
 * 于是这一条同时钉住端口 {@code login} 里 {@code verified == null} 那一支（{@code AB3} 钉的是同一支的
 * 另一半——"没发 token"）。
 */
class SaTokenAuthPortContractTest extends AuthPortContractTestBase {

    /** 口令只在这一个常量里出现一次，两个凭据工厂方法与校验器共用它。 */
    private static final String PASSWORD = "correct-password";
    /** 本类独占的 loginId 段（{@code RS} 用 49003–49007、{@code AB} 用 492xx，见各自用例）。 */
    private static final String LOGIN_ID = "49101";
    private static final AuthUser CHENEY =
            new AuthUser(LOGIN_ID, "cheney", Set.of("finance"), Set.of("order:read"));

    private final SaTokenRequestScope scope = new SaTokenRequestScope();
    private final SaTokenAuthPort authPort = new SaTokenAuthPort(knownUserVerifier());

    @Override
    protected AuthPort authPort() {
        return authPort;
    }

    @Override
    protected AuthCredentials validCredentials() {
        return new AuthCredentials(CHENEY.username(), PASSWORD);
    }

    @Override
    protected AuthCredentials brokenCredentials() {
        return new AuthCredentials(CHENEY.username(), "wrong-" + PASSWORD);
    }

    @Override
    protected AuthUser knownUser() {
        return CHENEY;
    }

    @Override
    protected void openRequestScope() {
        SaTokenRequestScope.installByUser(
                Map.of(LOGIN_ID, List.of("order:read")), Map.of(LOGIN_ID, List.of("finance")));
        scope.open();
    }

    @Override
    protected void closeRequestScope() {
        scope.close();
        SaTokenRequestScope.restoreDefaultPermissionSource();
    }

    @Override
    protected Optional<AuthUser> currentUserWithoutRequestScope() {
        return CompletableFuture.supplyAsync(authPort::currentUser).join();
    }

    /**
     * 用户名与口令<b>逐字段</b>判、不对就返回 {@code null} 的校验器。
     *
     * <p>刻意不写成 {@code credentials.equals(new AuthCredentials("cheney", PASSWORD))}：那样
     * {@code AUTH-C2} 也是绿的（record 的 {@code equals} 会同用户名错口令返回 false），但读的人得先
     * 推理一次 {@code equals} 才知道这里钉的是口令。
     *
     * <p>这里的 {@code ObjectProvider} 假实现与 {@code SaTokenAuthPortTest} 里那一份<b>各写一份</b>：
     * 两者都只覆写 {@code stream()}（Spring 7 里 {@code ObjectProvider} 的全部方法都是 {@code default}，
     * 一手 {@code ObjectProvider.java:85-95}），把它抽成第三个工具类会让"作用域夹具"同时承担两件事。
     */
    private static ObjectProvider<CredentialVerifier> knownUserVerifier() {
        CredentialVerifier verifier = credentials ->
                CHENEY.username().equals(credentials.username()) && PASSWORD.equals(credentials.password())
                        ? CHENEY
                        : null;
        return new ObjectProvider<>() {
            @Override
            public Stream<CredentialVerifier> stream() {
                return Stream.of(verifier);
            }
        };
    }
}

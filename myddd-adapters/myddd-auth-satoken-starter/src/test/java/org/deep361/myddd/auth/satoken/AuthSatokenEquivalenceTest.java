package org.deep361.myddd.auth.satoken;

import org.deep361.myddd.auth.satoken.config.AuthSatokenProviderSelection;
import org.deep361.myddd.auth.satoken.testing.SaTokenRequestScope;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.deep361.myddd.testing.contract.AuthProviderEquivalenceTestBase;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AuthProviderEquivalenceTestBase} 的 Sa-Token 侧子类：八行期望里本侧能表达七行，
 * {@code EQ7}（角色层级）由基类按 {@link org.deep361.myddd.testing.contract.AuthExpectationTable}
 * 的 {@code NOT_EXPRESSIBLE} 标记跳过，并由基类那条 {@code @AfterAll} 断言"跳过的恰好只有它"。
 *
 * <p><b>本级不写任何判据</b>：判据全在表里，本文件只回答"这一家怎么把表里那句话搭出来"。权限源走
 * {@link SaTokenRequestScope} 的静态 setter（本模块测试类路径上没有 starter ⇒ 没有 {@code SaBeanInject}，
 * 不手动装就永远读到全 deny 的缺省实现），每条用例结束还原。
 *
 * <p><b>loginId 段</b>：{@code 493xx} 归本类独占（{@code RS} 49003–49007、{@code C} 49101、
 * {@code AB} 492xx）——会话存在进程级缺省内存 DAO 里而 {@code @AfterEach} 只清上下文不清会话，
 * 撞号会让上一类留下的会话替本类作答。
 */
class AuthSatokenEquivalenceTest extends AuthProviderEquivalenceTestBase {

    private static final String PASSWORD = "correct-password";
    private static final String LOGIN_ID = "49301";
    private static final AuthUser KNOWN =
            new AuthUser(LOGIN_ID, "cheney", Set.of("finance"), Set.of("order:read"));

    private final SaTokenRequestScope scope = new SaTokenRequestScope();

    @Override
    protected String providerTag() {
        return "satoken";
    }

    @Override
    protected AuthPort newPort() {
        CredentialVerifier verifier = credentials ->
                KNOWN.username().equals(credentials.username()) && PASSWORD.equals(credentials.password())
                        ? KNOWN
                        : null;
        ObjectProvider<CredentialVerifier> verifiers = new ObjectProvider<>() {
            @Override
            public Stream<CredentialVerifier> stream() {
                return Stream.of(verifier);
            }
        };
        return new SaTokenAuthPort(verifiers);
    }

    @Override
    protected AuthUser knownUser() {
        return KNOWN;
    }

    @Override
    protected AuthCredentials knownCredentials() {
        return new AuthCredentials(KNOWN.username(), PASSWORD);
    }

    @Override
    protected AuthCredentials brokenCredentials() {
        return new AuthCredentials(KNOWN.username(), "wrong-" + PASSWORD);
    }

    @Override
    protected void openRequestScope() {
        grant(KNOWN.permissions().toArray(String[]::new));
        scope.open();
    }

    @Override
    protected void closeRequestScope() {
        scope.close();
        SaTokenRequestScope.restoreDefaultPermissionSource();
    }

    /** 本家的"装授予"= 换掉 {@code SaManager} 的权限源；角色那一格始终按 {@link #KNOWN} 给足。 */
    @Override
    protected void grant(String... permissions) {
        SaTokenRequestScope.installByUser(
                Map.of(LOGIN_ID, List.of(permissions)),
                Map.of(LOGIN_ID, List.copyOf(KNOWN.roles())));
    }

    /** 调本模块自己那份 {@code PS} 镜像（表 {@code EQ8} 要求消息出自厂商侧选择类，不许子类自己拼）。 */
    @Override
    protected String selectionFailureMessage(Set<String> present) {
        FrameworkException thrown = assertThrows(FrameworkException.class,
                () -> AuthSatokenProviderSelection.shouldAssembleSatoken(null, present));
        return thrown.getMessage();
    }
}

package org.deep361.myddd.testing.contract;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthProviderEquivalenceTestBase} 的本地自证子列：基类住在 {@code src/main} 里，而它自己的分支
 * （哨兵跳过、两侧跳过集合的比对、默认 {@code grantHierarchy()} 的拒绝）在两个 provider 模块的测试里
 * <b>不计入本模块</b>，也不许等到跨厂商那一轮才第一次跑。
 *
 * <p><b>与 {@code *FakeContractTest} 同一条分工</b>（本模块既有谱系：{@code AuthPortFakeContractTest} 等七份）：
 * 这里只用一个内存端口验"基类自己写得对不对"，<b>不</b>验任何一家厂商的行为；厂商那两路子类
 * （{@code AuthSatokenEquivalenceTest} / {@code AuthSecurityEquivalenceTest}）才承担等价性判据。
 *
 * <p><b>两路子类各跑一遍八行</b>：{@code satoken} 标签那路必须恰好跳 {@code eq:hierarchy} 一行，
 * {@code security} 标签那路必须一行都不跳——判据只有基类那条 {@code @AfterAll}，本类不另写一份。
 */
class AuthProviderEquivalenceFakeContractTest {

    private static final AuthUser FAKE_USER =
            new AuthUser("9001", "fake-user", Set.of("finance"), Set.of("order:read"));

    /** 表 {@code EQ8} 那句话在本地的替身：真消息出自两家各自的选择类，这里只验基类把消息原样送去断言。 */
    private static final String FAKE_SELECTION_MESSAGE =
            "myddd.auth.provider 必填（classpath 上同时有 satoken 与 security）";

    /** {@code satoken} 标签的一路：{@code EQ7} 必须被跳过，默认 {@code grantHierarchy()} 在这里是拒绝。 */
    @Nested
    class SideWithoutHierarchy extends AuthProviderEquivalenceTestBase {

        FakeAuthPort fakePort;

        @Override
        protected String providerTag() {
            return "satoken";
        }

        @Override
        protected AuthPort newPort() {
            fakePort = new FakeAuthPort();
            return fakePort;
        }

        @Override
        protected AuthUser knownUser() {
            return FAKE_USER;
        }

        @Override
        protected AuthCredentials knownCredentials() {
            return new AuthCredentials("fake-user", "right");
        }

        @Override
        protected AuthCredentials brokenCredentials() {
            return new AuthCredentials("fake-user", "wrong");
        }

        @Override
        protected void openRequestScope() {
            // 内存端口没有厂商静态面要装
        }

        @Override
        protected void closeRequestScope() {
        }

        @Override
        protected void grant(String... permissions) {
            fakePort.grantPermissions(permissions);
        }

        @Override
        protected String selectionFailureMessage(Set<String> present) {
            return FAKE_SELECTION_MESSAGE;
        }
    }

    /** {@code security} 标签的一路：八行全表达，{@code EQ7} 在这里真跑"端口读数不跟随层级"。 */
    @Nested
    class SideWithHierarchy extends SideWithoutHierarchy {

        @Override
        protected String providerTag() {
            return "security";
        }

        @Override
        protected void grantHierarchy() {
            fakePort.grantPermissions("ROLE_admin");
        }
    }

    /** 基类默认实现必须<b>拒绝</b>而不是静默给空集：走到这一格意味着"该跳过的行没跳过"。 */
    @Test
    void should_refuse_the_hierarchy_grant_on_the_side_without_one() {
        assertThatThrownBy(() -> new SideWithoutHierarchy().grantHierarchy())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("satoken", "EQ7");
    }

    /** 只服务基类自证的内存端口：身份视图恒为 {@link #FAKE_USER}，权限答复按 {@code granted} 精确匹配。 */
    private static final class FakeAuthPort implements AuthPort {

        private boolean loggedIn;
        private final Set<String> granted = new HashSet<>();

        void grantPermissions(String... permissions) {
            granted.clear();
            granted.addAll(Set.of(permissions));
        }

        @Override
        public Optional<AuthUser> currentUser() {
            return loggedIn ? Optional.of(FAKE_USER) : Optional.empty();
        }

        @Override
        public void login(AuthCredentials credentials) {
            if (!"right".equals(credentials.password())) {
                throw new BizException(CommonErrorCode.UNAUTHORIZED);
            }
            loggedIn = true;
        }

        @Override
        public void logout() {
            loggedIn = false;
            granted.clear();
        }

        @Override
        public boolean hasPermission(String permission) {
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission 不能为空白");
            }
            return loggedIn && granted.contains(permission);
        }
    }
}

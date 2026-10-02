package org.deep361.myddd.auth.satoken.bridge;

import cn.dev33.satoken.exception.ApiDisabledException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.deep361.myddd.auth.satoken.testing.SaTokenRequestScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R9 归一面（{@code RS} 组）：本类只测桥，不测端口；端口的四方法在 {@code AB} 组与继承来的 {@code AUTH-C}。 */
class SaTokenContextBridgeTest {

    private final SaTokenRequestScope scope = new SaTokenRequestScope();

    @AfterEach
    void closeScopeAndRestoreThePermissionSource() {
        scope.close();
        SaTokenRequestScope.restoreDefaultPermissionSource();
    }

    /** RS1：本线程从未装过上下文 ⇒ 空，不抛。 */
    @Test
    void should_answer_empty_on_a_thread_that_never_had_a_context() {
        assertThat(SaTokenContextBridge.currentLoginId()).isEmpty();
    }

    /** RS2：上下文在场但没有会话 ⇒ 空（探针 49d 的 null 读数，不是异常读数）。 */
    @Test
    void should_answer_empty_when_the_context_carries_no_session() {
        scope.open();
        assertThat(SaTokenContextBridge.currentLoginId()).isEmpty();
    }

    /** RS3：登录后拿到字符串形态的 loginId——传进去的是数字也一样。 */
    @Test
    void should_expose_the_login_id_as_a_string() {
        scope.open();
        StpUtil.login(49003L);
        assertThat(SaTokenContextBridge.currentLoginId()).contains("49003");
    }

    /** RS4：列表原样转交（不排序、不去重），权限源给 null 时归一成空列表。本方法全程不装上下文，顺带钉住"显式传 id 不碰上下文"。 */
    @Test
    void should_pass_the_permission_source_through_untouched() {
        SaTokenRequestScope.installByUser(
                Map.of("49004", List.of("z:read", "a:read", "z:read")),
                Map.of("49004", List.of("finance", "audit")));
        assertThat(SaTokenContextBridge.permissions("49004")).containsExactly("z:read", "a:read", "z:read");
        assertThat(SaTokenContextBridge.roles("49004")).containsExactly("finance", "audit");

        SaTokenRequestScope.install(new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return null;
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return null;
            }
        });
        assertThat(SaTokenContextBridge.permissions("49004")).isEmpty();
        assertThat(SaTokenContextBridge.roles("49004")).isEmpty();
    }

    /** RS5：两支"没有身份"的异常被归一成空操作。 */
    @Test
    void should_normalize_both_absences_of_identity_into_a_no_op() {
        AtomicBoolean reached = new AtomicBoolean();
        SaTokenContextBridge.runQuietly(() -> {
            throw new SaTokenContextException("SaTokenContext 上下文尚未初始化");
        });
        SaTokenContextBridge.runQuietly(() -> {
            throw new NotLoginException("未登录", "login", NotLoginException.NOT_TOKEN);
        });
        SaTokenContextBridge.runQuietly(() -> reached.set(true));
        assertTrue(reached.get(), "前两次归一之后动作链断了");
    }

    /** RS6：catch 面恰好两支——配置用错了（ApiDisabledException）和业务自己的异常都必须原样上抛。 */
    @Test
    void should_not_swallow_anything_that_is_not_an_absence_of_identity() {
        assertThrows(ApiDisabledException.class, () -> SaTokenContextBridge.runQuietly(() -> {
            throw new ApiDisabledException("只有在集成 sa-token-jwt 插件后才可以使用的扩展参数");
        }));
        assertThrows(IllegalStateException.class, () -> SaTokenContextBridge.runQuietly(() -> {
            throw new IllegalStateException("业务自己的失败");
        }));
    }

    /** RS7：另一条线程（MQ 消费线程、定时任务线程的形状）读同一个桥 ⇒ 空且不抛，本线程的身份不外泄。 */
    @Test
    void should_not_leak_the_current_context_into_another_thread() {
        scope.open();
        StpUtil.login("49007");
        Optional<String> fromAnotherThread = CompletableFuture
                .supplyAsync(SaTokenContextBridge::currentLoginId)
                .join();
        assertThat(fromAnotherThread).isEmpty();
        assertThat(SaTokenContextBridge.currentLoginId()).contains("49007");
    }
}

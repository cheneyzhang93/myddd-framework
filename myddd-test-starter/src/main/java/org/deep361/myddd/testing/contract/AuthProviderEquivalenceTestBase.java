package org.deep361.myddd.testing.contract;

import static org.deep361.myddd.testing.contract.AuthExpectationTable.ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * auth 两家的<b>等价性</b>基类：八条用例一一对应 {@link AuthExpectationTable} 的八行，
 * 每条只问"这一家对表里那句话的回答是什么"，<b>不问</b>"这一家的绝对值是多少"。
 *
 * <p><b>判据全部来自表</b>：每条断言的失败消息里都带 {@code row.expected()} 原文，
 * 于是"改期望"这件事在 diff 里只发生在表那一个文件，而两侧子类同时重跑。
 *
 * <p><b>跳过不是删除</b>：{@code NOT_EXPRESSIBLE} 那一行由本类的 {@link #skipIfNotExpressible} 统一
 * 变成 {@code assumeTrue(false, …)}，并记进 {@code skippedRows}；{@link #skippedRowsMustMatchTable()}
 * 在全部用例跑完后断言<b>实际跳过的行集合恰好等于表里该侧 {@code NOT_EXPRESSIBLE} 的行集合</b>。
 * 子类<b>没有</b>自己决定跳哪一行的入口（它连 {@code assumeTrue} 都不必写）。
 *
 * <p><b>{@code PER_CLASS} 与那条 {@code @AfterAll}</b>：跳过集合是"这一轮跑掉了哪几行"的读数，
 * 只有等八条都跑完才判得了，所以必须挂在 {@code @AfterAll}；用 {@code PER_CLASS} 是为了让它写成
 * 实例方法（要读 {@code providerTag()}），而不是再造一个按类名索引的静态 Map。
 * ⚠️ 这条断言红的时候 surefire 的 {@code tests} 属性<b>不</b>变（它是容器级失败，
 * 记在 {@code errors}/{@code failures} 位）。
 *
 * <p><b>只用 JUnit 原生断言</b>：本模块 main 源没有 AssertJ（与 job / limiter 两侧契约基类同一条判据），
 * 业务方引这一个 jar 时也不该被强制拖进一个断言库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AuthProviderEquivalenceTestBase {

    /** 本子是哪一家：{@code satoken} 或 {@code security}——决定 {@code skipIfNotExpressible} 看哪一列。 */
    protected abstract String providerTag();

    /** 被测端口（已接好这一家的凭据来源与权限来源）。 */
    protected abstract AuthPort newPort();

    /** 这一家声明的"已知用户"——两家<b>允许</b>互不相同（表 {@code EQ1} 那句"这一家自己声明的"）。 */
    protected abstract AuthUser knownUser();

    /** 与 {@link #knownUser()} 配套的凭据。 */
    protected abstract AuthCredentials knownCredentials();

    /** 用户名必须存在、只有口令错（两条分支的差别见清单 {@code A1}）。 */
    protected abstract AuthCredentials brokenCredentials();

    /** 装好这一家的"有作用域"上下文（不含身份）。 */
    protected abstract void openRequestScope();

    /** 拆掉它，并把这一家可能留下的静态面还原。 */
    protected abstract void closeRequestScope();

    /** 这一家"把这份授予集合装进去"的写法（satoken：权限源；security：token 的 authorities）。 */
    protected abstract void grant(String... permissions);

    /** 调用<b>本模块自己那份</b> {@code PS} 镜像，返回它抛出的消息；不许子类自己拼消息文本。 */
    protected abstract String selectionFailureMessage(Set<String> present);

    /**
     * 层级展开只在 security 一侧存在（表 {@code EQ7}）。默认实现<b>抛</b>而不是返回空：
     * 走到这里意味着"该跳过的那一行没跳过"，那正是 {@link #skippedRowsMustMatchTable()} 要抓的事，
     * 让它在这里也现形一次比静默通过好。satoken 侧子类<b>不必</b>覆写。
     */
    protected void grantHierarchy() {
        throw new IllegalStateException("providerTag()=" + providerTag() + " 一侧没有层级概念，"
                + "EQ7 应当已被跳过；走到这里说明跳过判据失效");
    }

    private final List<String> skippedRows = new ArrayList<>();
    private AuthPort port;

    /**
     * 每条用例都重新开作用域并重建端口：两家的静态面（{@code SecurityContextHolder}、{@code SaManager} 的
     * 权限源）都是进程级的，跨用例残留正是表 {@code EQ-T1} 要防的那件事。
     */
    @BeforeEach
    void openScopeAndPort() {
        openRequestScope();
        port = newPort();
    }

    @AfterEach
    void closeScope() {
        closeRequestScope();
    }

    /** EQ1：登录成功后的身份必须逐字段完整。 */
    @Test
    void should_give_complete_identity_after_login() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ1)) {
            return;
        }
        var row = AuthExpectationTable.EQ1;
        port.login(knownCredentials());
        AuthUser actual = port.currentUser().orElseThrow(() -> new AssertionError(row.expected()));
        AuthUser want = knownUser();
        assertEquals(want.userId(), actual.userId(), row.expected());
        assertEquals(want.username(), actual.username(), row.expected());
        assertEquals(want.roles(), actual.roles(), row.expected());
        assertEquals(want.permissions(), actual.permissions(), row.expected());
    }

    /** EQ2：口令错误只有一条对外语义，且不许留下半截身份。 */
    @Test
    void should_translate_bad_credentials_to_401_without_half_identity() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ2)) {
            return;
        }
        var row = AuthExpectationTable.EQ2;
        var thrown = assertThrows(BizException.class, () -> port.login(brokenCredentials()), row.expected());
        assertEquals(CommonErrorCode.UNAUTHORIZED, thrown.getErrorCode(), row.expected());
        assertTrue(port.currentUser().isEmpty(), row.expected());
    }

    /** EQ3：同一份授予集合下，命中与未命中的答复两家必须同形。 */
    @Test
    void should_answer_permission_hit_and_miss_the_same_way() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ3)) {
            return;
        }
        var row = AuthExpectationTable.EQ3;
        port.login(knownCredentials());
        grant("order:read");
        assertTrue(port.hasPermission("order:read"), row.expected());
        assertFalse(port.hasPermission("order:write"), row.expected());
    }

    /**
     * EQ4：空白权限名要快速失败。只断"抛 + 消息非空"，<b>不断异常类型</b>——类型与消息原文由两家的
     * {@code AUTH-C5} 各钉一次，在这里重复钉会把两家的类型绑成"必须相同"，而没有任何裁定说过它们该相同。
     */
    @Test
    void should_reject_blank_permission_name_instead_of_returning_false() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ4)) {
            return;
        }
        var row = AuthExpectationTable.EQ4;
        port.login(knownCredentials());
        grant("order:read");
        var thrown = assertThrows(RuntimeException.class, () -> port.hasPermission("   "), row.expected());
        assertFalse(thrown.getMessage() == null || thrown.getMessage().isBlank(), row.expected());
    }

    /** EQ5：登出把身份与权限一起带走，再登录必须能整个装回来。 */
    @Test
    void should_drop_identity_and_permissions_on_logout_and_restore_on_relogin() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ5)) {
            return;
        }
        var row = AuthExpectationTable.EQ5;
        port.login(knownCredentials());
        grant("order:read");
        port.logout();
        assertTrue(port.currentUser().isEmpty(), row.expected());
        assertFalse(port.hasPermission("order:read"), row.expected());
        port.login(knownCredentials());
        assertEquals(knownUser(), port.currentUser().orElseThrow(() -> new AssertionError(row.expected())),
                row.expected());
    }

    /** EQ6：无身份时登出是空操作。 */
    @Test
    void should_treat_logout_without_identity_as_noop() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ6)) {
            return;
        }
        var row = AuthExpectationTable.EQ6;
        port.logout();
        port.login(knownCredentials());
        assertTrue(port.currentUser().isPresent(), row.expected());
    }

    /**
     * EQ7：端口读数不跟随角色层级（security 一侧只断端口，"厂商侧层级生效"那一半在 provider 模块的
     * 装配用例里已经钉过，这里不重复给同一条关系写两处判据）。
     */
    @Test
    void should_not_follow_role_hierarchy_from_the_port() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ7)) {
            return;
        }
        var row = AuthExpectationTable.EQ7;
        port.login(knownCredentials());
        grant("user:write");
        grantHierarchy();
        assertFalse(port.hasPermission("user:read"), row.expected());
    }

    /** EQ8：两家都在 classpath 而没写 provider 键时，两侧的答复必须相反且成话。 */
    @Test
    void should_fail_the_same_way_when_provider_key_is_missing() {
        if (skipIfNotExpressible(AuthExpectationTable.EQ8)) {
            return;
        }
        var row = AuthExpectationTable.EQ8;
        String message = selectionFailureMessage(Set.of("satoken", "security"));
        assertTrue(message.contains("satoken") && message.contains("security"), row.expected());
        assertTrue(message.contains("myddd.auth.provider"), row.expected());
    }

    /** 实际跳过的行集合必须恰好等于表里本侧标了 {@code NOT_EXPRESSIBLE} 的那些行。 */
    @AfterAll
    void skippedRowsMustMatchTable() {
        boolean satoken = "satoken".equals(providerTag());
        List<String> want = ALL.stream()
                .filter(row -> AuthExpectationTable.NOT_EXPRESSIBLE
                        .equals(satoken ? row.satokenSetup() : row.securitySetup()))
                .map(AuthExpectationTable::scenario)
                .toList();
        assertEquals(want, skippedRows,
                "被跳过的行必须恰好是表里本侧标记 NOT_EXPRESSIBLE 的那些行（清单见 docs/auth/asymmetry.md）");
    }

    private boolean skipIfNotExpressible(AuthExpectationTable row) {
        boolean satoken = "satoken".equals(providerTag());
        String cell = satoken ? row.satokenSetup() : row.securitySetup();
        if (AuthExpectationTable.NOT_EXPRESSIBLE.equals(cell)) {
            skippedRows.add(row.scenario());
            assumeTrue(false, row.scenario() + " 在 " + providerTag() + " 侧表达不了（asymmetryRef="
                    + row.asymmetryRef() + "）");
            return true;
        }
        return false;
    }
}

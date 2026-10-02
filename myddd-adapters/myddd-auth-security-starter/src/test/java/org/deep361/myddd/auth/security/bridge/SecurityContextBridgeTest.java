package org.deep361.myddd.auth.security.bridge;

import java.util.Collection;
import java.util.List;
import org.deep361.myddd.auth.security.testing.SecurityContextHolderScope;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** holder 归一面（{@code RS} 组）：本类只测桥；端口四方法在 {@code AB} 组与继承来的 {@code AUTH-C}。 */
class SecurityContextBridgeTest {

    private final SecurityContextHolderScope scope = new SecurityContextHolderScope();

    @AfterEach
    void clearTheHolder() {
        this.scope.close();
    }

    /** RS1：从未装过 holder 的线程 ⇒ 空，不抛；并且夹具没碰过全局 strategy。 */
    @Test
    void should_answer_empty_on_a_thread_that_never_installed_a_context() {
        assertThat(SecurityContextBridge.currentAuthentication()).isEmpty();
        assertThat(SecurityContextHolderScope.strategyClassName())
                .isEqualTo("org.springframework.security.core.context.ThreadLocalSecurityContextHolderStrategy");
    }

    /** RS2：匿名 token 躺在 holder 里 ⇒ 空。它是 isAuthenticated()==true 的那种，只看认证位就漏了。 */
    @Test
    void should_treat_the_vendors_anonymous_token_as_no_identity() {
        Authentication anonymous = SecurityContextHolderScope.anonymous();
        assertThat(anonymous.isAuthenticated())
                .as("厂商匿名 token 的认证位必须是 true，否则本用例没有钉子（探针 50c）").isTrue();
        this.scope.open(anonymous);
        assertThat(SecurityContextBridge.currentAuthentication()).isEmpty();
    }

    /** RS3：未认证 token ⇒ 空，先自证它真的在场。 */
    @Test
    void should_answer_empty_for_an_unauthenticated_token() {
        this.scope.open(SecurityContextHolderScope.unauthenticated("50003", "irrelevant"));
        assertThat(this.scope.installed()).isNotNull();
        assertThat(SecurityContextBridge.currentAuthentication()).isEmpty();
    }

    /** RS4：厂商追加的 FACTOR_PASSWORD 被剔，其余原样原顺序；不带 factor 的同一份权限一支都不剔。 */
    @Test
    void should_drop_only_the_authority_the_vendor_appends_itself() {
        this.scope.open(SecurityContextHolderScope.withVendorFactorAuthority("50004",
                List.of("order:read", "user:read")));
        assertThat(SecurityContextBridge.authorityNames(this.scope.installed()))
                .containsExactly("order:read", "user:read");

        this.scope.open(SecurityContextHolderScope.authenticated("50004", List.of("order:read", "user:read")));
        assertThat(SecurityContextBridge.authorityNames(this.scope.installed()))
                .containsExactly("order:read", "user:read");
    }

    /** RS5：ROLE_ 前缀额外给出去壳角色视图，权限集仍然收全部原串（厂商 hasAuthority("ROLE_admin")=true 的口径）。 */
    @Test
    void should_add_a_stripped_role_view_without_narrowing_permissions() {
        this.scope.open(SecurityContextHolderScope.withUserDetails("50005", List.of("ROLE_admin", "order:read")));
        AuthUser user = SecurityContextBridge.toAuthUser(this.scope.installed());
        assertThat(user.roles()).containsExactly("admin");
        // 一手实测（本轮 jshell 探针，同一对字符串跑五个 JVM）：内核 AuthUser 的 Set.copyOf 给
        // [ROLE_admin, order:read] 与 [order:read, ROLE_admin] 两种顺序，逐字 containsExactly 会随机红。
        // 判定表这一格要钉的是"两条都在、不多不少"，与顺序无关 ⇒ 用 InAnyOrder，强度不降。
        assertThat(user.permissions()).containsExactlyInAnyOrder("ROLE_admin", "order:read");
        assertThat(user.hasPermission("order:read")).isTrue();
        assertThat(user.hasPermission("ROLE_admin")).isTrue();
        assertThat(user.hasPermission("admin")).isFalse();
    }

    /** RS6：两处"空白"都交给内核挡，桥不做第二层校验——连异常消息都是内核那句。 */
    @Test
    void should_leave_blankness_validation_to_the_kernel_record() {
        this.scope.open(SecurityContextHolderScope.withPrincipal(null, List.of("order:read")));
        Authentication blankNamed = this.scope.installed();
        assertThat(blankNamed.getName())
                .as("探针 50e：主体为 null 时厂商 getName() 给空串而不是字面量 null").isEmpty();
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> SecurityContextBridge.toAuthUser(blankNamed)).getMessage())
                .isEqualTo("userId 不能为空白");

        this.scope.open(SecurityContextHolderScope.authenticated("50006", List.of("ROLE_")));
        assertThat(assertThrows(IllegalArgumentException.class,
                () -> SecurityContextBridge.toAuthUser(this.scope.installed())).getMessage())
                .isEqualTo("roles 的元素不能为空白");
    }

    /** RS7：logout 清本线程 holder；从未装过的线程上它是空操作。 */
    @Test
    void should_clear_the_holder_and_treat_an_empty_one_as_a_no_op() {
        this.scope.open(SecurityContextHolderScope.authenticated("50007", List.of("order:read")));
        assertThat(SecurityContextBridge.currentAuthentication()).isPresent();
        SecurityContextBridge.logout();
        assertThat(SecurityContextBridge.currentAuthentication()).isEmpty();
        SecurityContextBridge.logout();
        assertThat(SecurityContextBridge.currentAuthentication()).isEmpty();
    }

    /** RS8：身份字符串只有 getName() 一根，四种主体形态各自落到探针 50g 的实测读数上。 */
    @Test
    void should_take_both_identity_fields_from_the_single_vendor_name() {
        this.scope.open(SecurityContextHolderScope.authenticated("50008", List.of("order:read")));
        assertThat(userOf(this.scope.installed())).isEqualTo("50008");

        this.scope.open(SecurityContextHolderScope.withUserDetails("50009", List.of("order:read")));
        assertThat(userOf(this.scope.installed())).isEqualTo("50009");

        this.scope.open(SecurityContextHolderScope.withPrincipal(new Account(), List.of("order:read")));
        assertThat(userOf(this.scope.installed())).isEqualTo("acct-1");

        this.scope.open(SecurityContextHolderScope.withPrincipal(new LoginUser("50010"), List.of("order:read")));
        assertThat(userOf(this.scope.installed())).isEqualTo("LoginUser[id=50010]");
    }

    /** 同源断言的写法：先断 userId 等于期望，再断 username 与 userId 相同（两句顺序即"同源"的机读形态）。 */
    private static String userOf(Authentication authentication) {
        AuthUser user = SecurityContextBridge.toAuthUser(authentication);
        assertThat(user.username()).isEqualTo(user.userId());
        return user.userId();
    }

    /**
     * 自定义 {@code UserDetails}：{@code getUsername()} 与 {@code toString()} 故意不同，用来分辨两条取值路径。
     *
     * <p>⚠️ {@code getAuthorities()} 的返回类型带通配符：一手 {@code javap} 7.1.1 的
     * {@code UserDetails} 声明的是 {@code Collection<? extends GrantedAuthority>}，写成
     * {@code Collection<GrantedAuthority>} 是"不能实现接口方法"的编译错误（片段里那一行是转写误差）。
     */
    static final class Account implements UserDetails {

        @Override
        public String getUsername() {
            return "acct-1";
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority("order:read"));
        }

        @Override
        public String toString() {
            return "Account@custom-tostring";
        }
    }

    /** 非 {@code UserDetails} 的业务 POJO：厂商 {@code getName()} 在这种主体上给 {@code toString()}。 */
    record LoginUser(String id) {
    }
}

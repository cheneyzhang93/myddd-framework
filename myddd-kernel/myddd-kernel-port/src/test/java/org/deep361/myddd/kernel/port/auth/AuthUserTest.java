package org.deep361.myddd.kernel.port.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUserTest {

    @Test
    void should_carry_identity_and_authorities() {
        AuthUser user = new AuthUser("1001", "cheney", Set.of("admin"), Set.of("order:query"));

        assertThat(user.userId()).isEqualTo("1001");
        assertThat(user.username()).isEqualTo("cheney");
        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.permissions()).containsExactly("order:query");
    }

    @Test
    void should_normalize_null_authorities_to_empty() {
        AuthUser user = new AuthUser("1001", "cheney", null, null);

        assertThat(user.roles()).isEmpty();
        assertThat(user.permissions()).isEmpty();
    }

    @Test
    void should_answer_permission_queries() {
        AuthUser user = new AuthUser("1001", "cheney", Set.of(), Set.of("order:query"));

        assertThat(user.hasPermission("order:query")).isTrue();
        assertThat(user.hasPermission("order:pay")).isFalse();
        assertThat(user.hasPermission("ORDER:QUERY")).isFalse();
        assertThat(user.hasPermission("order:*")).isFalse();
    }

    @Test
    void authorities_should_be_immutable_copies() {
        Set<String> roles = new HashSet<>();
        roles.add("admin");
        Set<String> permissions = new HashSet<>();
        permissions.add("order:query");
        AuthUser user = new AuthUser("1001", "cheney", roles, permissions);

        roles.add("vip");
        permissions.add("order:pay");

        assertThat(user.roles()).containsExactly("admin");
        assertThat(user.permissions()).containsExactly("order:query");
        assertThatThrownBy(() -> user.roles().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> user.permissions().add("x")).isInstanceOf(UnsupportedOperationException.class);

        AuthUser normalized = new AuthUser("1", "u", null, null);

        assertThatThrownBy(() -> normalized.roles().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> normalized.permissions().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_blank_authority_element() {
        assertThatThrownBy(() -> new AuthUser("1001", "cheney", Set.of(" "), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roles 的元素不能为空白");
        assertThatThrownBy(() -> new AuthUser("1001", "cheney", Set.of(), Set.of("   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("permissions 的元素不能为空白");
    }

    @Test
    void should_reject_null_identity() {
        assertThatThrownBy(() -> new AuthUser(null, "cheney", Set.of(), Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId 不能为 null");
        assertThatThrownBy(() -> new AuthUser(" ", "cheney", Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("userId 不能为空白");
        assertThatThrownBy(() -> new AuthUser("1001", null, Set.of(), Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("username 不能为 null");
        assertThatThrownBy(() -> new AuthUser("1001", " ", Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("username 不能为空白");
    }

    @Test
    void should_reject_null_authority_element() {
        Set<String> roles = new HashSet<>();
        roles.add(null);
        Set<String> permissions = new HashSet<>();
        permissions.add(null);

        assertThatThrownBy(() -> new AuthUser("1001", "cheney", roles, Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("roles 的元素不能为 null");
        assertThatThrownBy(() -> new AuthUser("1001", "cheney", Set.of(), permissions))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("permissions 的元素不能为 null");
    }

    @Test
    void has_permission_should_reject_blank_argument() {
        AuthUser user = new AuthUser("1001", "cheney", Set.of(), Set.of());

        assertThatThrownBy(() -> user.hasPermission(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("permission 不能为 null");
        assertThatThrownBy(() -> user.hasPermission(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("permission 不能为空白");
    }

    @Test
    void should_implement_value_equality() {
        AuthUser user = new AuthUser("1001", "cheney", Set.of("admin"), Set.of("order:query"));

        assertThat(user).isEqualTo(new AuthUser("1001", "cheney", Set.of("admin"), Set.of("order:query")));
        assertThat(user).hasSameHashCodeAs(new AuthUser("1001", "cheney", Set.of("admin"), Set.of("order:query")));
        assertThat(user).isNotEqualTo(new AuthUser("1002", "cheney", Set.of("admin"), Set.of("order:query")));
    }
}

package org.deep361.myddd.kernel.port.auth;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContractTest {

    static final class StubAuthPort implements AuthPort {
        AuthUser current;

        @Override
        public Optional<AuthUser> currentUser() {
            return Optional.ofNullable(current);
        }

        @Override
        public void login(AuthCredentials credentials) {
            current = new AuthUser(credentials.username(), credentials.username(), Set.of(), Set.of());
        }

        @Override
        public void logout() {
            current = null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return currentUser().map(user -> user.hasPermission(permission)).orElse(false);
        }
    }

    @Test
    void should_login_logout_and_query_permission() {
        StubAuthPort port = new StubAuthPort();
        assertThat(port.currentUser()).isEmpty();
        assertThat(port.hasPermission("order:query")).isFalse();

        port.login(new AuthCredentials("cheney", "p"));

        assertThat(port.currentUser()).isPresent();
        assertThat(port.currentUser().orElseThrow().username()).isEqualTo("cheney");

        port.logout();

        assertThat(port.currentUser()).isEmpty();
    }

    @Test
    void requires_permission_should_be_runtime_visible() throws NoSuchMethodException {
        assertThat(RequiresPermission.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(RequiresPermission.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
        assertThat(RequiresPermission.class.isAnnotationPresent(Documented.class)).isTrue();

        RequiresPermission annotation = StubApi.class.getDeclaredMethod("query").getAnnotation(RequiresPermission.class);
        assertThat(annotation.value()).containsExactly("order:query", "order:detail");
        assertThat(annotation.logical()).isEqualTo(RequiresPermission.Logical.ALL_OF);
        assertThat(RequiresPermission.class.getDeclaredMethod("logical").getDefaultValue())
                .isEqualTo(RequiresPermission.Logical.ALL_OF);
        assertThat(RequiresPermission.class.getDeclaredMethod("message").getDefaultValue()).isEqualTo("");
    }

    @Test
    void requires_permission_should_require_value_and_pin_logical_enum() throws NoSuchMethodException {
        assertThat(RequiresPermission.class.getDeclaredMethod("value").getDefaultValue()).isNull();
        assertThat(RequiresPermission.Logical.values())
                .containsExactly(RequiresPermission.Logical.ALL_OF, RequiresPermission.Logical.ANY_OF);
    }

    static class StubApi {
        @RequiresPermission({"order:query", "order:detail"})
        @SuppressWarnings("unused")
        void query() {
        }
    }
}

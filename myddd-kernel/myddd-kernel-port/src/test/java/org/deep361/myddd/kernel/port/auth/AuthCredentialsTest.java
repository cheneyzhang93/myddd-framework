package org.deep361.myddd.kernel.port.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCredentialsTest {

    @Test
    void should_carry_username_and_password() {
        AuthCredentials credentials = new AuthCredentials("cheney", "s3cret");

        assertThat(credentials.username()).isEqualTo("cheney");
        assertThat(credentials.password()).isEqualTo("s3cret");
    }

    @Test
    void should_reject_blank_fields() {
        assertThatThrownBy(() -> new AuthCredentials(null, "p"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("username 不能为 null");
        assertThatThrownBy(() -> new AuthCredentials(" ", "p"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("username 不能为空白");
        assertThatThrownBy(() -> new AuthCredentials("u", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("password 不能为 null");
        assertThatThrownBy(() -> new AuthCredentials("u", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password 不能为空白");
        assertThatThrownBy(() -> new AuthCredentials("u", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password 不能为空白");
    }

    @Test
    void should_not_leak_password_in_to_string() {
        AuthCredentials credentials = new AuthCredentials("cheney", "s3cret");

        assertThat(credentials.toString()).doesNotContain("s3cret");
        assertThat(credentials.toString()).isEqualTo("AuthCredentials[username=cheney, password=***]");
    }
}

package org.deep361.myddd.auth.credential;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.deep361.myddd.auth.config.MydddAuthProperties;
import org.deep361.myddd.authfixture.FakeAuthPort;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code CV} 组：{@link CredentialVerifierGuard} 的三步短路与那条启动期诊断的完整形状。
 *
 * <p>容器形态用替身 {@link ObjectProvider}（探针 {@code 48c} 的四格语义），守卫只走
 * {@code stream()}——{@code getIfAvailable()} 在替身里同样可编程，但守卫一次都不该碰它；
 * {@code CV3} 用访问计数器钉"先问属性、再问端口、最后才问 verifier"的顺序。
 */
class CredentialVerifierGuardTest {

    /** 替身：分类真源是 {@code stream()}（守卫唯一该调的方法），计数器供 {@code CV3} 读。 */
    private static final class StubProvider<T> implements ObjectProvider<T> {

        private final Class<T> type;
        private final List<T> items;
        private int streamAccesses;

        StubProvider(Class<T> type, List<T> items) {
            this.type = type;
            this.items = items;
        }

        @Override
        public T getObject() throws BeansException {
            if (this.items.isEmpty()) {
                throw new NoSuchBeanDefinitionException(this.type);
            }
            return this.items.get(0);
        }

        @Override
        public Stream<T> stream() {
            this.streamAccesses++;
            return this.items.stream();
        }

        @Override
        public T getIfAvailable() throws BeansException {
            if (this.items.isEmpty()) {
                return null;
            }
            if (this.items.size() > 1) {
                throw new NoUniqueBeanDefinitionException(this.type,
                        this.items.stream().map(item -> item.getClass().getName()).toList());
            }
            return this.items.get(0);
        }
    }

    private static final class NamedVerifier implements CredentialVerifier {

        private final String name;

        NamedVerifier(String name) {
            this.name = name;
        }

        @Override
        public AuthUser verify(AuthCredentials credentials) {
            return new AuthUser("1", "u-" + this.name, Set.of(), Set.of());
        }
    }

    private static CredentialVerifierGuard guard(List<AuthPort> ports, List<CredentialVerifier> verifiers,
            MydddAuthProperties properties) {
        return new CredentialVerifierGuard(new StubProvider<>(CredentialVerifier.class, verifiers),
                new StubProvider<>(AuthPort.class, ports), properties);
    }

    /**
     * {@code CV1}：有端口、没人校验凭据 ⇒ 启动失败，且七个锚点全在同一条消息里
     * （键名、接缝类型、错误身份、两家厂商各自的失败点、以及关能力不是替身的提醒）。
     */
    @Test
    void should_refuse_startup_when_a_port_is_present_but_nobody_verifies_credentials() {
        assertThatThrownBy(() -> guard(List.of(new FakeAuthPort()), List.of(), new MydddAuthProperties())
                .afterSingletonsInstantiated())
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.auth.credential.verifier-required")
                .hasMessageContaining("CredentialVerifier")
                .hasMessageContaining("AuthPort")
                .hasMessageContaining("BizException")
                .hasMessageContaining("CommonErrorCode.UNAUTHORIZED")
                .hasMessageContaining("StpUtil.login")
                .hasMessageContaining("myddd.auth.enabled=false");
    }

    /** {@code CV2}：在场一个就够；两个也不判"多个"——本守卫与 {@code AuthPortGuard} 的不对称是有意的。 */
    @Test
    void should_stay_silent_when_at_least_one_verifier_is_present_even_among_several() {
        assertThatCode(() -> guard(List.of(new FakeAuthPort()), List.of(new NamedVerifier("only")),
                new MydddAuthProperties()).afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThatCode(() -> guard(List.of(new FakeAuthPort()),
                List.of(new NamedVerifier("a"), new NamedVerifier("b")), new MydddAuthProperties())
                        .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    /** {@code CV3}：属性关掉 ⇒ 整段闭嘴，两个容器面一次都不该被问（计数钉顺序）。 */
    @Test
    void should_not_even_ask_the_container_when_the_property_is_off() {
        StubProvider<CredentialVerifier> verifiers = new StubProvider<>(CredentialVerifier.class, List.of());
        StubProvider<AuthPort> ports = new StubProvider<>(AuthPort.class, List.of(new FakeAuthPort()));
        MydddAuthProperties properties = new MydddAuthProperties();
        properties.getCredential().setVerifierRequired(false);
        assertThatCode(() -> new CredentialVerifierGuard(verifiers, ports, properties)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThat(ports.streamAccesses).as("verifier-required=false 时连端口都不该被问").isZero();
        assertThat(verifiers.streamAccesses).isZero();
    }

    /** {@code CV4}：零端口 ⇒ 闭嘴——"没装配 auth"的诊断归 {@code AuthPortGuard}，同一启动不许两条消息抢话。 */
    @Test
    void should_stay_silent_when_there_is_no_port_at_all() {
        assertThatCode(() -> guard(List.of(), List.of(), new MydddAuthProperties())
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }
}

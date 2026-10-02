package org.deep361.myddd.auth.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.deep361.myddd.auth.config.AuthPortGuard.Presence;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code PG} 组：{@link AuthPortGuard} 的三态判据。分类真源是 {@code stream()}（探针 {@code 48c}
 * 四格语义），替身把 {@code getIfAvailable()} 的"两个且无 {@code @Primary} ⇒ 抛"如实演出来——
 * {@code PG3} 钉的就是守卫把它吞成自己的诊断，而不是让 Spring 的原始异常漏给业务。
 */
class AuthPortGuardTest {

    static class PortA implements AuthPort {

        @Override
        public Optional<AuthUser> currentUser() {
            return Optional.empty();
        }

        @Override
        public void login(AuthCredentials credentials) {
        }

        @Override
        public void logout() {
        }

        @Override
        public boolean hasPermission(String permission) {
            return false;
        }
    }

    static class PortB extends PortA {
    }

    /** 替身：四格容器形态里唯一需要的三格（零 / 一个 / 多个±Primary），{@code stream()} 带访问计数。 */
    static final class StubProvider implements ObjectProvider<AuthPort> {

        final List<AuthPort> items = new ArrayList<>();
        boolean primaryResolvable;
        int streamAccesses;

        StubProvider(AuthPort... ports) {
            Collections.addAll(this.items, ports);
        }

        @Override
        public AuthPort getObject() throws BeansException {
            if (this.items.isEmpty()) {
                throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(AuthPort.class);
            }
            return this.items.get(0);
        }

        @Override
        public Stream<AuthPort> stream() {
            this.streamAccesses++;
            return this.items.stream();
        }

        @Override
        public AuthPort getIfAvailable() throws BeansException {
            if (this.items.isEmpty()) {
                return null;
            }
            if (this.items.size() > 1 && !this.primaryResolvable) {
                throw new NoUniqueBeanDefinitionException(AuthPort.class,
                        this.items.stream().map(item -> item.getClass().getName()).toList());
            }
            return this.items.get(0);
        }
    }

    /** {@code PG1}：恰好一个——{@code require()} 原样给出同一个实例，快照报 {@code EXACTLY_ONE} 与实现类名。 */
    @Test
    void should_hand_out_the_only_candidate_and_report_exactly_one() {
        PortA portA = new PortA();
        AuthPortGuard guard = new AuthPortGuard(new StubProvider(portA));
        assertThat(guard.require()).isSameAs(portA);
        AuthPortGuard.Snapshot status = guard.status();
        assertThat(status.presence()).isEqualTo(Presence.EXACTLY_ONE);
        assertThat(status.implementations()).containsExactly(PortA.class.getName());
    }

    /**
     * {@code PG2}：零个——{@code require()} 的诊断必须自带出路（两家 provider 的坐标与键名、
     * 自供实现的第三条路、关总开关的真实代价）；{@code status()} 不抛，报 {@code NONE} 与空表。
     */
    @Test
    void should_diagnose_the_missing_port_with_the_provider_key_and_stay_quiet_in_status() {
        AuthPortGuard guard = new AuthPortGuard(new StubProvider());
        assertThatThrownBy(guard::require)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.auth.provider")
                .hasMessageContaining("satoken")
                .hasMessageContaining("security")
                .hasMessageContaining("myddd-auth-satoken-starter")
                .hasMessageContaining("myddd-auth-security-starter")
                .hasMessageContaining("myddd.auth.enabled=false");
        assertThatCode(guard::status).doesNotThrowAnyException();
        assertThat(guard.status().presence()).isEqualTo(Presence.NONE);
        assertThat(guard.status().implementations()).isEmpty();
    }
    /**
     * {@code PG3}：两个且无 {@code @Primary}——Spring 的 {@code NoUniqueBeanDefinitionException}
     * 被吞成框架自己的诊断（指名两个实现与 {@code @Primary} 出路），原始异常一滴都不外漏。
     */
    @Test
    void should_translate_the_multi_candidate_spring_exception_into_its_own_diagnosis() {
        AuthPortGuard guard = new AuthPortGuard(new StubProvider(new PortA(), new PortB()));
        assertThatThrownBy(guard::require)
                .isInstanceOf(FrameworkException.class)
                .isNotInstanceOf(NoUniqueBeanDefinitionException.class)
                .hasMessageContaining("PortA")
                .hasMessageContaining("PortB")
                .hasMessageContaining("@Primary");
    }

    /**
     * {@code PG4}：{@code @Primary} 消歧成功——{@code require()} 用消歧结果，而 {@code status()}
     * 依旧报 {@code MULTIPLE} 与两个类名："容器里有什么"不许被"我用哪个"改写（Task 51 的展示读前者）。
     */
    @Test
    void should_honour_primary_for_the_call_and_still_report_multiple_for_the_status() {
        PortA primary = new PortA();
        StubProvider provider = new StubProvider(primary, new PortB());
        provider.primaryResolvable = true;
        AuthPortGuard guard = new AuthPortGuard(provider);
        assertThat(guard.require()).isSameAs(primary);
        AuthPortGuard.Snapshot status = guard.status();
        assertThat(status.presence()).isEqualTo(Presence.MULTIPLE);
        assertThat(status.implementations()).containsExactly(PortA.class.getName(), PortB.class.getName());
    }
    // <<<tests2>>>
}

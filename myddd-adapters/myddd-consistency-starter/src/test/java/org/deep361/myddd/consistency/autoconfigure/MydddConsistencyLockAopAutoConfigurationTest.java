package org.deep361.myddd.consistency.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.consistency.lock.DistributedLock;
import org.deep361.myddd.consistency.lock.DistributedLockAspect;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式锁的装配面：{@link MydddConsistencyLockAopAutoConfiguration} 在不在、什么条件下让位、
 * 以及"框架只交切面、锁的实现在别处"这条分工是否真的成立。
 *
 * <p>与 {@code DistributedLockAspectTest} 的分工：那边用手工装配的上下文钉<b>切面算错了什么</b>
 * （键、时长、顺序、异常透传）；这里只钉<b>切面在不在容器里、拿到的是哪个 {@code LockPort}</b>。
 * 两侧不重复：这里不重算 SpEL，那边不碰条件。
 */
class MydddConsistencyLockAopAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddConsistencyLockAopAutoConfiguration.class));

    /** 记录键的执行端口：装配证据要落在"调用真的穿过切面到了端口"上。 */
    private static final class RecordingPort implements LockPort {

        private final List<String> keys = new ArrayList<>();

        @Override
        public <T> T executeLocked(String key, java.time.Duration waitTime, java.time.Duration leaseTime,
                java.util.function.Supplier<T> action) {
            this.keys.add(key);
            return action.get();
        }
    }

    static class AccountService {

        @DistributedLock(key = "'account:' + #accountId")
        public String close(String accountId) {
            return "closed:" + accountId;
        }
    }

    /** 代理开关由应用自己声明（框架不加 {@code @EnableAspectJAutoProxy}），所以测试上下文带上它。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class ProxyingConfiguration {

        @Bean
        AccountService accountService() {
            return new AccountService();
        }
    }

    @Test
    void should_assemble_the_aspect_by_default() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DistributedLockAspect.class);
        });
    }

    @Test
    void should_back_off_when_aspectj_is_not_on_the_classpath() {
        // 条件里那一句不是装饰：没有 AspectJ 就没有代理基础设施，切面 bean 留着只会让人以为注解生效了。
        runner.withClassLoader(new FilteredClassLoader(Aspects.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
        });
    }

    @Test
    void should_back_off_when_the_capability_switch_is_off() {
        runner.withPropertyValues("myddd.consistency.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
        });
    }

    @Test
    void should_still_assemble_when_the_switch_is_written_explicitly_true() {
        // matchIfMissing 的另一半：显式写 true 也得装配。缺了这条，"缺省开"可以被写成"缺省关"而没人发现。
        runner.withPropertyValues("myddd.consistency.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DistributedLockAspect.class);
        });
    }

    @Test
    void should_lock_through_the_lock_port_the_application_provided() {
        RecordingPort port = new RecordingPort();

        runner.withBean(LockPort.class, () -> port)
                .withUserConfiguration(ProxyingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AccountService.class).close("a-1")).isEqualTo("closed:a-1");
                    // 键原样到达业务自带的端口：中间没有被框架的实现换掉。
                    assertThat(port.keys).containsExactly("account:a-1");
                });
    }

    @Test
    void should_keep_the_aspect_assembled_when_the_redisson_lock_branch_is_off() {
        // 声明面与实现面分开条件的全部意义：关掉框架的实现、又不自带 LockPort 时，
        // 应用照常起得来，问题在第一次 @DistributedLock 调用时说清缺什么。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddConsistencyAutoConfiguration.class,
                        MydddConsistencyLockAopAutoConfiguration.class))
                .withPropertyValues("myddd.consistency.lock.enabled=false")
                .withUserConfiguration(ProxyingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedLockAspect.class);
                    assertThat(context).doesNotHaveBean(LockPort.class);
                    assertThat(context).doesNotHaveBean(RedissonClient.class);
                    assertThatThrownBy(() -> context.getBean(AccountService.class).close("a-1"))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.consistency.lock.enabled");
                });
    }
}

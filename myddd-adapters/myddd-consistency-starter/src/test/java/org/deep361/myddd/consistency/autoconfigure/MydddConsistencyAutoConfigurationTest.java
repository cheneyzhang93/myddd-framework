package org.deep361.myddd.consistency.autoconfigure;

import java.time.Duration;
import org.deep361.myddd.consistency.lock.DefaultLockPort;
import org.deep361.myddd.consistency.lock.RecordingLockClient;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁能力的装配面：条件在不在、bean 以什么身份在、业务自带实现时框架怎么让位。
 *
 * <p>用 {@code ApplicationContextRunner} 而不是真上下文（与缓存/持久化侧同一分工）：这里只问条件装配，
 * 真 Redis 的证据归 {@code LockRedisIT}。
 *
 * <p><b>happy path 的"建出真客户端"这一段在这里刻意不覆盖</b>：{@code mydddRedissonClient} 会真的连 Redis
 * （一手实测约 7 秒后失败），离线上下文里给不出可连的实例。因此本类用两条互补的证据代替：
 * ① 容器里没有 {@code DataRedisConnectionDetails} 时抛的那句 {@link FrameworkException}（证明 bean 方法
 * 真的消费了这个接口，而不是读了别的什么）；② 业务自带 {@code RedissonClient} 时 {@code LockPort}
 * 拿到的就是那一个（证明 {@code @ConditionalOnMissingBean} 与注入关系）。
 */
class MydddConsistencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddConsistencyAutoConfiguration.class));

    @Test
    void should_assemble_nothing_when_the_connection_details_are_absent() {
        // 缺省就是 lock.enabled=true，所以离线上下文必须"说清缺什么"而不是静默没有 LockPort。
        runner.run(context -> {
            assertThat(context).hasFailed();
            // 失败本身就是证据：起不来的容器里根本没有 bean 可断言（AssertJ 对失败上下文做
            // doesNotHaveBean 会直接把启动失败当成断言失败），所以这里只核对那句能照着做的话。
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("DataRedisConnectionDetails")
                    .hasMessageContaining("spring.data.redis.host")
                    .hasMessageContaining("myddd.consistency.lock.enabled=false");
        });
    }

    @Test
    void should_use_the_injected_client_and_back_off_when_the_application_already_has_one() {
        RecordingLockClient redisson = new RecordingLockClient();

        runner.withBean(RedissonClient.class, redisson::client).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedissonClient.class);
            // 同一个实例：框架没有另建客户端，也没有把业务的客户端包一层。
            assertThat(context.getBean(RedissonClient.class)).isSameAs(redisson.client());
            assertThat(context).hasSingleBean(LockPort.class);
            assertThat(context.getBean(LockPort.class)).isInstanceOf(DefaultLockPort.class);
        });
    }

    @Test
    void should_back_off_completely_when_the_application_provides_its_own_lock_port() {
        LockPort custom = new LockPort() {
            @Override
            public <T> T executeLocked(String key, Duration waitTime, Duration leaseTime,
                    java.util.function.Supplier<T> action) {
                return action.get();
            }
        };

        runner.withBean(LockPort.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LockPort.class);
            assertThat(context.getBean(LockPort.class)).isSameAs(custom);
            // 让位要退得干净：锁端口都不要了，就不该再为它建一个会去连 Redis 的客户端。
            assertThat(context).doesNotHaveBean(RedissonClient.class);
        });
    }

    @Test
    void should_still_validate_the_configuration_when_the_framework_backs_off() {
        // 让位不等于失声：业务自带 RedissonClient，却配了空白锁名前缀，依然在启动期失败。
        // 少了这条，"配了 key-prefix= 又自带客户端"会留下一个所有锁都打在同一裸键名上的应用。
        RecordingLockClient redisson = new RecordingLockClient();

        runner.withBean(RedissonClient.class, redisson::client)
                .withPropertyValues("myddd.consistency.lock.key-prefix=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.consistency.lock.key-prefix");
                });
    }

    @Test
    void should_bind_every_documented_key_and_reach_the_port_with_them() {
        RecordingLockClient redisson = new RecordingLockClient();

        runner.withBean(RedissonClient.class, redisson::client)
                .withPropertyValues(
                        "myddd.consistency.lock.key-prefix=acme:lock",
                        "myddd.consistency.lock.watchdog-timeout=3s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MydddConsistencyProperties properties = context.getBean(MydddConsistencyProperties.class);
                    assertThat(properties.getLock().getKeyPrefix()).isEqualTo("acme:lock");
                    assertThat(properties.getLock().getWatchdogTimeout()).isEqualTo(Duration.ofSeconds(3));
                    // 键不只是被绑上了，还要真的走到行为里：锁名由配置前缀拼出。
                    DefaultLockPort port = (DefaultLockPort) context.getBean(LockPort.class);
                    assertThat(port.lockName("order:1")).isEqualTo("acme:lock:order:1");
                });
    }

    @Test
    void should_skip_everything_when_the_capability_switch_is_off() {
        runner.withPropertyValues("myddd.consistency.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockPort.class);
            assertThat(context).doesNotHaveBean(RedissonClient.class);
            // 总开关关了连配置 bean 都不该有：没有代码读它，留着只会让人以为键还生效。
            assertThat(context).doesNotHaveBean(MydddConsistencyProperties.class);
        });
    }

    @Test
    void should_skip_the_whole_lock_branch_when_only_the_lock_is_off() {
        // 关掉锁以后不该仍然要求 Redis 可达——那会把一个退路开关变成启动依赖。
        runner.withPropertyValues("myddd.consistency.lock.enabled=false",
                "myddd.consistency.lock.key-prefix=acme:lock").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockPort.class);
            assertThat(context).doesNotHaveBean(RedissonClient.class);
            // 配置 bean 还在（幂等等后续能力共用它），但校验因 lock.enabled=false 而跳过。
            assertThat(context).hasSingleBean(MydddConsistencyProperties.class);
            assertThat(context.getBean(MydddConsistencyProperties.class).getLock().getKeyPrefix())
                    .isEqualTo("acme:lock");
        });
    }

    /**
     * 两个开关<b>显式写成 {@code true}</b>时必须照常装配。
     *
     * <p>这条用例是被变异 M47 找出来的：把锁分支的 {@code havingValue} 从 {@code "true"} 改成 {@code "on"}，
     * 当时 A 组 9 条全绿——缺省那条靠 {@code matchIfMissing} 生效，{@code =false} 那条靠"值不等于 true 就退让"，
     * 两个方向都碰不到"显式写了肯定值"。这不是理论风险：{@code havingValue = "true"} 的真实语义是
     * "<b>除 {@code false} 以外都算开</b>"，写成别的字面量之后 {@code myddd.consistency.lock.enabled=true}
     * 的用户会拿不到锁，而表现正是最难查的"配了却不生效"。
     *
     * <p>业务自带 {@code RedissonClient} 只为让这条用例停在离线：缺省那条链路会真的去连 Redis（见类注释）。
     */
    @Test
    void should_still_assemble_when_both_switches_are_written_explicitly_true() {
        RecordingLockClient redisson = new RecordingLockClient();

        runner.withBean(RedissonClient.class, redisson::client)
                .withPropertyValues("myddd.consistency.enabled=true",
                        "myddd.consistency.lock.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LockPort.class);
                    assertThat(context.getBean(LockPort.class)).isInstanceOf(DefaultLockPort.class);
                    assertThat(context).hasSingleBean(MydddConsistencyProperties.class);
                });
    }

    @Test
    void should_assemble_nothing_when_redisson_is_not_on_the_classpath() {
        // 锁客户端的实现手段缺席时整块退让，而不是在建 bean 时抛 NoClassDefFoundError。
        runner.withClassLoader(new FilteredClassLoader(RedissonClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockPort.class);
        });
    }

    @Test
    void should_assemble_nothing_when_boot_exposes_no_redis_connection_details_type() {
        // 内层 @ConditionalOnClass(DataRedisConnectionDetails)：Boot 的 redis 装配缺席时不该去要那个 bean。
        runner.withClassLoader(new FilteredClassLoader(DataRedisConnectionDetails.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LockPort.class);
            assertThat(context).doesNotHaveBean(RedissonClient.class);
            // 外层还留着配置 bean：幂等能力（Task 26）共用同一份配置，它不经 Redisson。
            assertThat(context).hasSingleBean(MydddConsistencyProperties.class);
        });
    }
}

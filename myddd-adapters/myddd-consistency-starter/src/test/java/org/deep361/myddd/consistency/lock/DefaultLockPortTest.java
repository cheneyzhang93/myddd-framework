package org.deep361.myddd.consistency.lock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultLockPort} 的行为：R3 的两条时序怎么分叉、失败长成什么样、以及它到底调了 Redisson 的哪几个方法。
 *
 * <p>夹具是 {@link RecordingLockClient}（{@code java.lang.reflect.Proxy} 造的记录型 {@code RedissonClient}），
 * 所以每条用例的断言里都有<b>逐条交互日志</b>：端口对 Redisson 的依赖面被钉在"getLock + 一个 tryLock 重载 +
 * isHeldByCurrentThread + unlock"这四个调用上，多一个都红。这一层比"结果对不对"更重要——
 * 锁的错误用法（该带租约时没带、不持锁还去 unlock）在功能测试里往往看不出来。
 *
 * <p>真 Redis 上的互斥证据在 {@code LockRedisIT}（需要 Docker）；这里证明的是"我方代码发出了哪种调用"，
 * 而不是"Redisson 怎么实现这把锁"。
 */
class DefaultLockPortTest {

    private final RecordingLockClient redisson = new RecordingLockClient();
    private final MydddConsistencyProperties properties = new MydddConsistencyProperties();

    @AfterEach
    void clearInterruptFlag() {
        // 有一条用例会真的把中断位设回去。JUnit 每个用例一个新线程的话不必清，
        // 但 surefire 默认复用线程——留着它会让后面任何碰 tryLock 的用例莫名其妙走进中断分支。
        Thread.interrupted();
    }

    private DefaultLockPort port() {
        return new DefaultLockPort(redisson.client(), properties);
    }

    @Test
    void should_take_a_bounded_lease_and_release_it_when_the_action_finishes() {
        AtomicBoolean ran = new AtomicBoolean();

        String result = port().executeLocked("order:1", Duration.ofSeconds(2), Duration.ofSeconds(30), () -> {
            ran.set(true);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(ran).isTrue();
        // 三个数字都要对上：等待预算换成了毫秒、租约带上了、释放发生在动作之后。
        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(2000,30000,MILLISECONDS)",
                "isHeldByCurrentThread()",
                "unlock()");
    }

    @Test
    void should_drop_the_lease_argument_when_the_caller_asks_for_the_watchdog() {
        // R3 的第二条：leaseTime == 0 必须走 tryLock(wait, unit) 那个两参重载——
        // Redisson 只在"没有显式租约"时才启动 watchdog。写成三参 + lease 0 会得到一把立刻过期的锁。
        assertThat(port().executeLocked("order:1", Duration.ofMillis(5_000), Duration.ZERO, () -> "x"))
                .isEqualTo("x");

        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(5000,MILLISECONDS)",
                "isHeldByCurrentThread()",
                "unlock()");
    }

    @Test
    void should_use_the_kernel_default_of_zero_wait_and_a_bounded_thirty_second_lease() {
        // LockPort 的模板 default 方法：缺省语义是"有界租约不续租"（R3），业务不传就是这一条。
        assertThat(port().executeLocked("order:1", () -> "v")).isEqualTo("v");

        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(0,30000,MILLISECONDS)",
                "isHeldByCurrentThread()",
                "unlock()");
    }

    @Test
    void should_run_the_kernel_default_runnable_variant_inside_the_same_bounded_lease() {
        AtomicBoolean ran = new AtomicBoolean();

        port().runLocked("order:1", () -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(0,30000,MILLISECONDS)",
                "isHeldByCurrentThread()",
                "unlock()");
    }

    @Test
    void should_fail_with_lock_acquire_failed_without_running_the_action_when_the_lock_is_taken() {
        AtomicBoolean ran = new AtomicBoolean();
        redisson.lockAcquired(false);

        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(30),
                () -> ran.get()))
                .isInstanceOfSatisfying(BizException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.LOCK_ACQUIRE_FAILED);
                    assertThat(failure.getMessageArgs()).isEmpty();
                });

        assertThat(ran).isFalse();
        // 没拿到锁就去 unlock 会误删别人刚拿到的锁；isHeldByCurrentThread 也不该被调——
        // 交互日志停在 tryLock 就是这条纪律的证据。
        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(0,30000,MILLISECONDS)");
    }

    @Test
    void should_restore_the_interrupt_flag_and_wrap_the_interrupt_as_a_business_failure() {
        redisson.tryLockFailing(new InterruptedException("cancelled while waiting"));
        AtomicReference<BizException> captured = new AtomicReference<>();

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ofSeconds(5), Duration.ofSeconds(30),
                () -> "never"))
                .isInstanceOfSatisfying(BizException.class, captured::set);

        BizException failure = captured.get();
        assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.LOCK_ACQUIRE_FAILED);
        assertThat(failure.getCause()).isInstanceOf(InterruptedException.class);
        // 中断位必须还在：吞掉中断会让上层线程池以为"没人叫我停"。
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(redisson.countOf("unlock()")).isZero();
    }

    @Test
    void should_release_the_lock_even_when_the_action_fails() {
        IllegalStateException boom = new IllegalStateException("业务炸了");

        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(30), () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(0,30000,MILLISECONDS)",
                "isHeldByCurrentThread()",
                "unlock()");
    }

    @Test
    void should_not_unlock_when_the_lease_already_expired_under_the_action() {
        // R3 第一条形态的正常后果：任务比 leaseTime 长，锁在动作期间被 Redis 回收，
        // 此时另一个线程可能已经拿到同一把锁。无条件 unlock 会抛 IllegalMonitorStateException
        // （把一个已经成功的业务动作改成抛错），更糟的写法是"想办法 unlock 成功"——那会删掉别人的锁。
        AtomicBoolean ran = new AtomicBoolean();
        redisson.heldByCurrentThread(false);

        assertThat(port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(30), () -> {
            ran.set(true);
            return "ok";
        })).isEqualTo("ok");

        assertThat(ran).isTrue();
        assertThat(redisson.interactions()).containsExactly(
                "getLock(myddd:lock:order:1)",
                "tryLock(0,30000,MILLISECONDS)",
                "isHeldByCurrentThread()");
    }

    @Test
    void should_prefix_the_lock_name_with_the_configured_namespace_and_nothing_else() {
        properties.getLock().setKeyPrefix("acme:lock");
        DefaultLockPort port = port();

        assertThat(port.lockName("order:1")).isEqualTo("acme:lock:order:1");
        port.runLocked("order:1", () -> {
        });
        assertThat(redisson.requestedLockNames()).containsExactly("acme:lock:order:1");

        // 不规范化：业务给的键原样出现在 Redis 里，redis-cli 扫到的名字和代码里写的是同一个串。
        assertThat(port.lockName("Order::1")).isEqualTo("acme:lock:Order::1");
    }

    @Test
    void should_reject_illegal_arguments_before_touching_redis() {
        Supplier<String> action = () -> "x";

        // 每一条非法参数都要在"getLock 之前"被拒：让 Redis 参与一次本可以避免的往返，
        // 等于把一个参数错误升级成一次可能拿到锁的调用。
        assertThatThrownBy(() -> port().executeLocked(null, Duration.ZERO, Duration.ofSeconds(30), action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("锁键不能为空白");
        assertThatThrownBy(() -> port().executeLocked("   ", Duration.ZERO, Duration.ofSeconds(30), action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("锁键不能为空白");
        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(30), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("受保护的动作不能为 null");
        assertThatThrownBy(() -> port().executeLocked("order:1", null, Duration.ofSeconds(30), action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitTime 不能为 null 或负数");
        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ofMillis(-1), Duration.ofSeconds(30),
                action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waitTime 不能为 null 或负数");
        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ZERO, null, action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseTime 不能为 null 或负数");
        assertThatThrownBy(() -> port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(-30), action))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaseTime 不能为 null 或负数")
                .hasMessageContaining("Duration.ZERO");

        assertThat(redisson.interactions()).isEmpty();
    }

    @Test
    void should_reject_a_null_backend_or_properties_at_construction_time() {
        assertThatThrownBy(() -> new DefaultLockPort(null, properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RedissonClient 不能为 null");
        assertThatThrownBy(() -> new DefaultLockPort(redisson.client(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MydddConsistencyProperties 不能为 null");
    }

    @Test
    void should_run_the_action_exactly_once_and_only_inside_the_lock() {
        int[] runs = new int[1];

        port().executeLocked("order:1", Duration.ZERO, Duration.ofSeconds(30), () -> {
            // 动作体内就能看到锁已拿到：交互日志此刻是 [getLock, tryLock]，还没有 unlock。
            assertThat(redisson.interactions()).containsExactly(
                    "getLock(myddd:lock:order:1)",
                    "tryLock(0,30000,MILLISECONDS)");
            runs[0]++;
            return "ok";
        });

        assertThat(runs[0]).isEqualTo(1);
    }
}

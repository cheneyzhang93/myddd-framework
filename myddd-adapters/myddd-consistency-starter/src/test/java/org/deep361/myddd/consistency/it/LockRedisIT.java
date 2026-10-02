package org.deep361.myddd.consistency.it;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyAutoConfiguration;
import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.consistency.lock.DistributedLock;
import org.deep361.myddd.consistency.lock.RedissonClientFactory;
import org.deep361.myddd.consistency.lock.StubConnectionDetails;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.deep361.myddd.testing.container.MydddRedisContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分布式锁在<b>真 Redis</b> 上的验收（Plan 3 Task 24）。
 *
 * <p><b>本类只做单元层做不了的那半件事</b>：{@code DefaultLockPortTest} 用代理夹具证明了"端口发出了哪种
 * {@code tryLock} 调用"，但它证明不了 Redis 侧的租约真的会到期、watchdog 真的在续租、以及<b>另一个线程
 * 或另一个客户端</b>看这把锁是不是占着的。这些只能问 Redis 自己。
 *
 * <p><b>无 Docker 时整类跳过而不是失败</b>：基注解 {@link MydddIntegrationTest} 带
 * {@code @Testcontainers(disabledWithoutDocker = true)}，这是 §0.3-7 的实现路径。本机没有 Docker，
 * 因此下面每条用例的实际执行证据都要等 Docker 就位后由用户重跑（交付说明里按"仅证明能干净跳过"记录）。
 *
 * <p><b>声明式锁（{@code @DistributedLock}）也在本类验收</b>：{@code DistributedLockAspectTest} 用记录型端口
 * 证明了切面算出的三元组，但"注解 → SpEL → LockPort → Redis 里那把真锁"这条链只在真 Redis 上走通一次才算
 * 数。因此这里另起一个带注解的业务 bean（见 {@link AnnotatedOperations}），不重复端口层那六条。
 *
 * <p><b>时序类用例的节奏</b>：谁先谁后用 {@code CountDownLatch} 对齐，不靠"睡一觉应该够了"；
 * 只有"必须跨越租约时长"的那一段用真实 sleep（那本身就是被测事实）。锁名每条用例各用一个后缀，
 * 免得两条用例在同一把锁上互相干扰。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddRedisContainer.class)
@TestPropertySource(properties = {
        "myddd.consistency.lock.key-prefix=myddd:it:lock",
        // 3s 而不是缺省 30s：watchdog 那条用例要"持锁时间明显长于一拍"，等 30s 不值得。
        "myddd.consistency.lock.watchdog-timeout=3s",
})
class LockRedisIT {

    @Autowired
    private LockPort lockPort;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private AnnotatedOperations annotated;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDownPool() {
        pool.shutdownNow();
    }

    /**
     * 互斥这件事本身：<b>两个线程进不了同一个键的临界区</b>。
     *
     * <p>单元层给不出这条证据——代理夹具里"锁被别人占着"是我方设定的返回值，而不是 Redis 的判断。
     */
    @Test
    void should_keep_two_threads_out_of_the_same_critical_section() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        // 会合点放在锁<b>外</b>：两个线程同时到起跑线，才能把"同时抢同一把锁"这件事真的发生出来。
        // 放在锁内是设计错误——互斥成立时第二个线程永远进不来，栅栏只能超时。
        CyclicBarrier startLine = new CyclicBarrier(2);

        Callable<Object> body = () -> {
            awaitQuietly(startLine);
            return lockPort.executeLocked("contended:1", Duration.ofSeconds(10), Duration.ofSeconds(30), () -> {
                peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                // 临界区里睡一拍：互斥失效时这个窗口就是给第二个线程准备的破绽。
                sleep(200);
                inside.decrementAndGet();
                return null;
            });
        };

        Future<?> first = pool.submit(body);
        Future<?> second = pool.submit(body);
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertThat(peak.get()).isEqualTo(1);
    }

    /** 不同键必须<b>能</b>并行：否则"锁"退化成了全局串行，语义与吞吐都错。 */
    @Test
    void should_run_two_different_keys_in_parallel() throws Exception {
        // 不同键允许并发，所以这条的栅栏放在临界区<b>内</b>才数得完；与上一条互为对照——
        // 上一条的会合点在锁外，因为互斥成立时第二个线程根本进不来。
        CountDownLatch bothInside = new CountDownLatch(2);

        Future<?> first = pool.submit(() -> lockPort.executeLocked("parallel:a", Duration.ofSeconds(10),
                Duration.ofSeconds(30), () -> {
                    bothInside.countDown();
                    awaitQuietly(bothInside);
                    return "a";
                }));
        Future<?> second = pool.submit(() -> lockPort.executeLocked("parallel:b", Duration.ofSeconds(10),
                Duration.ofSeconds(30), () -> {
                    bothInside.countDown();
                    awaitQuietly(bothInside);
                    return "b";
                }));

        // 两条动作在彼此的动作体内等同一个栅栏：只有并行才可能都通过（串行则第一个永远等不到）。
        assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo("b");
    }

    /** 同一个键、等待预算为 0：<b>第二个线程立刻失败</b>，拿不到锁就是拿不到。 */
    @Test
    void should_refuse_the_second_holder_of_the_same_key() throws Exception {
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<String> holder = pool.submit(() -> lockPort.executeLocked("exclusive:1", Duration.ZERO,
                Duration.ofSeconds(30), () -> {
                    holderIn.countDown();
                    awaitQuietly(release);
                    return "held";
                }));

        assertThat(holderIn.await(10, TimeUnit.SECONDS)).isTrue();
        // 在持锁线程之外（主线程）尝试同一把锁：不等待，必须抛业务异常而不是返回 null。
        assertThatThrownBy(() -> lockPort.executeLocked("exclusive:1", Duration.ZERO, Duration.ofSeconds(30),
                () -> "must not run"))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.LOCK_ACQUIRE_FAILED));

        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("held");
    }

    /**
     * R3 第一条：{@code leaseTime > 0} 的租约<b>到点自动释放、不续租</b>。
     *
     * <p>持锁动作故意比租约长：期望是"锁在动作还没跑完时就已经不在我手上"，并且此刻另一个线程能拿到同一把锁。
     * 这一条同时证明 {@code DefaultLockPort} 的 {@code isHeldByCurrentThread()} 守卫在真 Redis 上成立——
     * 少了它，这里会以 {@code IllegalMonitorStateException} 结束而不是正常返回结果。
     */
    @Test
    void should_let_a_bounded_lease_expire_under_a_longer_action() {
        AtomicBoolean otherThreadGotIn = new AtomicBoolean();
        CountDownLatch interloperDone = new CountDownLatch(1);

        String result = lockPort.executeLocked("expiring:1", Duration.ZERO, Duration.ofSeconds(1), () -> {
            // 动作跑 2s，租约只有 1s：中途锁被 Redis 回收，另一个线程因此能进同一把锁。
            pool.submit(() -> {
                try {
                    otherThreadGotIn.set(Boolean.TRUE.equals(lockPort.executeLocked("expiring:1",
                            Duration.ofSeconds(3), Duration.ofSeconds(5), () -> true)));
                } finally {
                    interloperDone.countDown();
                }
            });
            awaitQuietly(interloperDone);
            sleep(1_200);
            return "finished-after-expiry";
        });

        assertThat(result).isEqualTo("finished-after-expiry");
        assertThat(otherThreadGotIn).isTrue();
    }

    /**
     * R3 第二条：{@code leaseTime == 0} 时 watchdog 按 {@code watchdog-timeout}（本上下文配成 3s）续租，
     * <b>持锁 5 秒不会丢锁</b>。
     *
     * <p>反证很直接：如果没续租，这把锁会在 3 秒后自动过期，第 4 秒时另一个线程就能拿到它。
     */
    @Test
    void should_renew_the_lease_while_the_holder_is_still_running() throws Exception {
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch holderOut = new CountDownLatch(1);

        Future<?> holder = pool.submit(() -> lockPort.executeLocked("watchdog:1", Duration.ZERO, Duration.ZERO,
                () -> {
                    holderIn.countDown();
                    sleep(5_000);
                    holderOut.countDown();
                    return null;
                }));

        assertThat(holderIn.await(10, TimeUnit.SECONDS)).isTrue();
        sleep(4_000);
        // 第 4 秒：租约窗口（3s）早已过，锁还在，说明 watchdog 续过。
        assertThat(redissonClient.getLock("myddd:it:lock:watchdog:1").isLocked()).isTrue();
        assertThatThrownBy(() -> lockPort.executeLocked("watchdog:1", Duration.ZERO, Duration.ofSeconds(30),
                () -> "must not run"))
                .isInstanceOf(BizException.class);

        holder.get(20, TimeUnit.SECONDS);
        assertThat(holderOut.await(10, TimeUnit.SECONDS)).isTrue();
        // 显式释放之后锁立刻可见地消失：watchdog 形态不会把锁留在 Redis 里。
        assertThat(redissonClient.getLock("myddd:it:lock:watchdog:1").isLocked()).isFalse();
    }

    /** 锁名真的以配置的前缀落在 Redis 里（{@code redis-cli --scan --pattern 'myddd:it:lock:*'} 的等价检查）。 */
    @Test
    void should_put_the_lock_in_redis_under_the_prefixed_name() {
        String lockName = "myddd:it:lock:named:1";

        lockPort.runLocked("named:1", () -> {
            assertThat(redissonClient.getLock(lockName).isLocked()).isTrue();
            assertThat(redissonClient.getKeys().countExists(lockName)).isEqualTo(1L);
            // 业务给的原样键名不会自己出现在 Redis 里：前缀段是唯一入口。
            assertThat(redissonClient.getKeys().countExists("named:1")).isZero();
        });

        assertThat(redissonClient.getLock(lockName).isLocked()).isFalse();
        assertThat(redissonClient.getKeys().countExists(lockName)).isZero();
    }

    /**
     * 容器关停时客户端真的被关：<b>{@code @Bean(destroyMethod = "shutdown")} 的证据</b>。
     *
     * <p>这里另起一个可关闭的小上下文，而不是用 {@code @SpringBootTest} 那个共享上下文——后者的关闭时机
     * 由测试框架决定，"关完之后"就无从对齐。漏掉 {@code destroyMethod} 的表现是 netty 线程组与连接池
     * 一直活着（进程退不出去），那是运维故障而不是测试故障，所以只在这里钉一次。
     */
    @Test
    void should_shut_the_client_down_when_the_context_closes() throws Exception {
        StubConnectionDetails details = StubConnectionDetails.standalone(
                MydddRedisContainer.REDIS.getHost(), MydddRedisContainer.REDIS.getFirstMappedPort());

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DataRedisConnectionDetails.class, () -> details);
        context.register(MydddConsistencyAutoConfiguration.class);
        context.refresh();

        RedissonClient created = context.getBean(RedissonClient.class);
        RLock probe = created.getLock("myddd:it:lock:shutdown-probe");
        // 先确认这个客户端真是我方装配出来的、并且连得上容器（而不是一个没初始化的壳）。
        assertThat(probe.tryLock()).isTrue();
        context.close();

        assertThat(created.isShutdown()).isTrue();
    }

    /**
     * 直接调工厂建客户端：连接参数从 {@link DataRedisConnectionDetails} 这一层进来，
     * 与容器 {@code @ServiceConnection} 提供的那份同源。这条用例补上离线映射用例的最后一段——
     * <b>翻译出来的配置连得上真 Redis</b>。
     */
    @Test
    void should_build_a_working_client_from_the_translated_config() throws Exception {
        StubConnectionDetails details = StubConnectionDetails.standalone(
                MydddRedisContainer.REDIS.getHost(), MydddRedisContainer.REDIS.getFirstMappedPort());
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        RLock lock;

        RedissonClient client = RedissonClientFactory.create(details, properties);
        try {
            lock = client.getLock("myddd:it:lock:factory");
            assertThat(lock.tryLock(1, 5, TimeUnit.SECONDS)).isTrue();
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            // 自动装配那条路径与这条路径产出的是同一套地址：同名的锁在两边看得到彼此。
            assertThat(redissonClient.getLock("myddd:it:lock:factory").isLocked()).isTrue();
        } finally {
            client.shutdown();
        }
    }

    /**
     * 声明式锁在真 Redis 上走通：注解 → SpEL 键 → {@code LockPort} → 容器里那把真锁。
     *
     * <p>第二个线程用<b>同一个 SpEL 模板、不同的参数值</b>去调用，得到的是另一把锁——
     * 这条用例钉的是"键真的按实参算"，不是"键真的算出来了"（后者单元层已覆盖）。
     * 同时核验 Redis 侧的锁名：SpEL 结果加上配置前缀，原样可见。
     */
    @Test
    void should_lock_the_annotated_method_under_a_key_built_from_its_argument() throws Exception {
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<String> holder = pool.submit(() -> annotated.shelve("SKU-9", holderIn, release));
        assertThat(holderIn.await(10, TimeUnit.SECONDS)).isTrue();
        // 持锁期间，Redis 里的锁名是「配置前缀 + SpEL 结果」：既不是原样键名，也不是固定串。
        assertThat(redissonClient.getLock("myddd:it:lock:shelve:SKU-9").isLocked()).isTrue();
        // 同模板不同实参是另一把锁：等待预算 0 也能立刻拿到，说明键按参数分开算。
        assertThat(annotated.shelve("SKU-8", new CountDownLatch(1), new CountDownLatch(0)))
                .isEqualTo("shelved:SKU-8");

        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("shelved:SKU-9");
    }

    /** 声明式锁的互斥同样只能问 Redis：注解方法被两个线程抢同一把锁时，第二个必须失败。 */
    @Test
    void should_refuse_a_concurrent_call_to_the_annotated_method() throws Exception {
        CountDownLatch holderIn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<String> holder = pool.submit(() -> annotated.shelve("SKU-7", holderIn, release));
        assertThat(holderIn.await(10, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> annotated.shelve("SKU-7", new CountDownLatch(1), new CountDownLatch(0)))
                .isInstanceOfSatisfying(BizException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.LOCK_ACQUIRE_FAILED));

        release.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isEqualTo("shelved:SKU-7");
    }

    /** 被注解的业务 bean：只服务上面两条声明式锁用例。 */
    static class AnnotatedOperations {

        @DistributedLock(key = "'shelve:' + #skuId", waitTime = "0s", leaseTime = "30s")
        public String shelve(String skuId, CountDownLatch holderIn, CountDownLatch release) {
            holderIn.countDown();
            awaitQuietly(release);
            return "shelved:" + skuId;
        }
    }

    /**
     * 声明式锁需要的代理开关在这里显式打开：与"框架不替应用加 {@code @EnableAspectJAutoProxy}"这条裁定一致
     * ——本 IT 验的是应用开了之后的链路，不是框架替它开。
     */
    @TestConfiguration
    @EnableAspectJAutoProxy
    static class AspectTestConfiguration {

        @Bean
        AnnotatedOperations annotatedOperations() {
            return new AnnotatedOperations();
        }
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(15, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException("会合点没等齐：用例的时序假设不成立", failure);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("用例被中断", failure);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("栅栏没被数完：用例的时序假设不成立");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("用例被中断", failure);
        }
    }
}

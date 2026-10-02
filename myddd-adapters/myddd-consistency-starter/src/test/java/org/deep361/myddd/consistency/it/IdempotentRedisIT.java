package org.deep361.myddd.consistency.it;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.deep361.myddd.consistency.idempotent.AbstractIdempotentPortContractTest;
import org.deep361.myddd.consistency.idempotent.RedisIdempotentStore;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.consistency.Idempotent;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.deep361.myddd.testing.container.MydddRedisContainer;
import org.deep361.myddd.testing.integration.MydddIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等占位在<b>真 Redis</b> 上的验收（Plan 3 Task 26），并作为
 * {@link AbstractIdempotentPortContractTest} 的 Redis 一侧子类参与 Task 27 的等价性契约。
 *
 * <p><b>本类的两部分</b>：继承来的 EQ1–EQ8 证明"换存储不换契约"（与 H2 上的
 * {@code JdbcIdempotentStoreContractTest} 跑同一批方法体）；本类自己那几条证明只有真 Redis 才给得出的
 * 形状——{@code SET NX PX} 之后那条键带着<b>活的 TTL</b>，以及原样键名不会出现在 Redis 里。
 * H2 那一侧跑不了这几条，因为 TTL 在表里是一个列而不是一个会自己到点的东西。
 *
 * <p><b>为什么 EQ5 与 EQ6 在 Redis 侧是"真"的</b>：单元层（{@code RedisIdempotentStoreTest}）用替身模板
 * 证明了"发出了哪条命令、几条、按什么顺序"，但替身里"别人已经占着"是我方设定的返回值，而不是 Redis 的
 * 判断；而"到点了"在替身里是我方设定的时钟读数，Redis 并不知道。这两件事只有对着真服务端跑才算证据。
 *
 * <p><b>无 Docker 时整类跳过而不是失败</b>：基注解 {@link MydddIntegrationTest} 带
 * {@code @Testcontainers(disabledWithoutDocker = true)}，这是 §0.3-7 的实现路径。本机没有 Docker，
 * 因此下面每条用例（含继承来的八条）的实际执行证据都要等 Docker 就位后由用户重跑（交付说明按
 * "仅证明能干净跳过"记录）。
 *
 * <p><b>声明式幂等（{@code @Idempotent}）也在本类验收</b>：注解 → SpEL 键 → 端口 → Redis 里那条真键
 * 的整条链路只在真 Redis 上走通一次才算数（与 {@code LockRedisIT} 的声明式半边同一个分工）。
 *
 * <p><b>键名与缓存/锁侧同源</b>：前缀段来自 {@code myddd.consistency.idempotent.key-prefix}，
 * 本上下文把它配成 {@link #PREFIX}，与业务前缀分开，免得用例之间、模块之间互相看见。
 */
@MydddIntegrationTest
@ImportTestcontainers(MydddRedisContainer.class)
@TestPropertySource(properties = "myddd.consistency.idempotent.key-prefix=" + IdempotentRedisIT.PREFIX)
class IdempotentRedisIT extends AbstractIdempotentPortContractTest {

    /** 本上下文的键前缀段：既是配置值，也是 {@link #configuredKeyPrefix()} 的期望值，单一真源。 */
    static final String PREFIX = "myddd:it:idempotent";

    /**
     * EQ6 的"让时间过去"在多出的那一段里等：Redis 的 TTL 是服务端计时，与 {@code Thread.sleep} 不同源，
     * 差一点没到点就会把用例变成偶发红。
     */
    private static final Duration GRACE = Duration.ofMillis(500);

    @Autowired
    private IdempotentPort idempotentPort;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AnnotatedOperations annotated;

    /** 容器里那个端口的真身：bean 的声明类型是 {@code IdempotentPort}，所以按键名算的那两步要向下转型。 */
    private RedisIdempotentStore store() {
        return (RedisIdempotentStore) this.idempotentPort;
    }

    @Override
    protected IdempotentPort port() {
        return this.idempotentPort;
    }

    @Override
    protected String configuredKeyPrefix() {
        return PREFIX;
    }

    @Override
    protected String physicalKeyOf(String key) {
        return store().idempotentKey(key);
    }

    @Override
    protected boolean placeholderExists(String physicalKey) {
        return Boolean.TRUE.equals(this.redis.hasKey(physicalKey));
    }

    @Override
    protected String tokenStoredAt(String physicalKey) {
        return this.redis.opsForValue().get(physicalKey);
    }

    @Override
    protected void letTimePassBeyond(Duration span) {
        sleep(span.plus(GRACE));
    }

    /**
     * 清场按前缀整段删，而不是逐条删用例知道的名字：EQ5 与声明式那几条写入的键（UUID token、缺省键摘要）
     * 事先算不出来，漏一条就会把上一次运行的占位带进下一次。
     */
    @Override
    protected void clearPlaceholders() {
        var keys = this.redis.keys(PREFIX + ":*");
        if (keys != null && !keys.isEmpty()) {
            this.redis.delete(keys);
        }
    }

    /** 占位落在配置前缀下面，而且<b>带着活的 TTL</b>——没有过期时间的幂等键就是一把永久锁死的门。 */
    @Test
    void should_store_the_placeholder_under_the_prefixed_key_with_a_live_ttl() {
        String token = java.util.UUID.randomUUID().toString();

        assertThat(store().tryAcquire("documented:1", token, Duration.ofSeconds(60))).isTrue();
        String wireKey = store().idempotentKey("documented:1");
        assertThat(wireKey).isEqualTo(PREFIX + ":documented:1");
        assertThat(placeholderExists(wireKey)).isTrue();
        assertThat(this.redis.opsForValue().get(wireKey)).isEqualTo(token);
        assertThat(this.redis.getExpire(wireKey)).isBetween(1L, 60_000L);
        // 原样键名不会自己出现在 Redis 里：前缀段是唯一入口（与锁侧同一条检查）。
        assertThat(placeholderExists("documented:1")).isFalse();
    }

    /**
     * 声明式幂等整条链路：注解 → SpEL 键 → 端口 → Redis 里那条真键。
     *
     * <p>第二个线程用<b>同一个 SpEL 模板、不同的参数值</b>调用，必须被当成另一次请求放行——
     * 这条钉的是"键真的按实参算"，而不是"键真的算出来了"（后者单元层已覆盖）。
     */
    @Test
    void should_reject_the_second_call_with_equal_arguments_and_accept_a_different_one() throws Exception {
        CountDownLatch firstIn = new CountDownLatch(1);
        CountDownLatch firstOut = new CountDownLatch(1);

        Future<String> first = this.pool.submit(() -> annotated.submit("o-1", firstIn, firstOut));
        assertThat(firstIn.await(10, TimeUnit.SECONDS)).isTrue();
        // 第一次还在跑（没抛异常），窗口内的同参数调用就该被拒——这正是"防重复提交"要省掉的那次执行。
        assertThatThrownBy(() -> annotated.submit("o-1", new CountDownLatch(1), new CountDownLatch(0)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENT_CONFLICT));
        // 换一个业务坐标是另一次请求：同模板不同实参算出另一条键。
        assertThat(annotated.submit("o-2", new CountDownLatch(1), new CountDownLatch(0))).isEqualTo("submitted:o-2");
        assertThat(placeholderExists(store().idempotentKey("it:submit:o-2"))).isTrue();

        firstOut.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("submitted:o-1");
    }

    /** {@code releaseOnError} 缺省 {@code true}：业务失败以后占位<b>必须</b>被撤掉，用户可以立刻改正重试。 */
    @Test
    void should_release_the_placeholder_when_the_annotated_method_fails() {
        assertThatThrownBy(() -> annotated.failsOnce("it:fails-once"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("业务失败");
        assertThat(placeholderExists(store().idempotentKey("it:fails-once"))).isFalse();

        // 同一条键立刻可以再用，而且这次真的跑完了业务方法：这条断言才是"失败不锁死用户"的全部含义。
        assertThat(annotated.failsOnce("it:fails-once")).isEqualTo("ok:it:fails-once");
    }

    /** {@code releaseOnError = false}：失败以后占位留到 TTL 过期，重试照样被拒。 */
    @Test
    void should_keep_the_placeholder_after_a_failure_when_release_on_error_is_off() {
        assertThatThrownBy(() -> annotated.failsKeeps())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("失败也不许立刻重试");
        assertThat(placeholderExists(store().idempotentKey("it:fails-kept"))).isTrue();

        assertThatThrownBy(() -> annotated.failsKeeps())
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(CommonErrorCode.IDEMPOTENT_CONFLICT));
    }

    /** 缺省键（没写 {@code key}）走 R2 的摘要：<b>同参数同键、换参数换键</b>，在真 Redis 上各占一条键。 */
    @Test
    void should_derive_the_default_key_from_the_arguments() {
        assertThat(annotated.defaulted("o-3")).isEqualTo("defaulted:o-3");
        assertThatThrownBy(() -> annotated.defaulted("o-3"))
                .isInstanceOf(BizException.class);
        assertThat(annotated.defaulted("o-4")).isEqualTo("defaulted:o-4");
        // 上面三条合起来就是缺省键的全部契约：同参数撞同一条键（第二条被拒）、换参数换一条键（第三条放行）。
    }

    /** 被注解的业务 bean：只服务上面四条声明式用例。 */
    static class AnnotatedOperations {

        @Idempotent(key = "'it:submit:' + #orderNo", ttl = 120)
        public String submit(String orderNo, CountDownLatch in, CountDownLatch out) {
            in.countDown();
            awaitQuietly(out);
            return "submitted:" + orderNo;
        }

        /** 第一次必炸、第二次通过：占位释放以后同一条键要能真的跑完，靠这个计数器演出来。 */
        @Idempotent(key = "#which", ttl = 120)
        public String failsOnce(String which) {
            if (this.attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("业务失败");
            }
            return "ok:" + which;
        }

        @Idempotent(key = "'it:fails-kept'", ttl = 120, releaseOnError = false)
        public String failsKeeps() {
            throw new IllegalStateException("失败也不许立刻重试");
        }

        @Idempotent(ttl = 120)
        public String defaulted(String orderNo) {
            return "defaulted:" + orderNo;
        }

        private final java.util.concurrent.atomic.AtomicInteger attempts
                = new java.util.concurrent.atomic.AtomicInteger();
    }

    /**
     * 声明式幂等需要的代理开关在这里显式打开：与"框架不替应用加 {@code @EnableAspectJAutoProxy}"这条裁定一致
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("用例被中断", failure);
        }
    }
}

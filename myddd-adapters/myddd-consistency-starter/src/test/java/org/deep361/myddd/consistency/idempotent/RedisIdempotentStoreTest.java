package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RedisIdempotentStore} 的命令形状取证（离线，无需 Docker）。
 *
 * <p>分工写在 {@link RecordingRedisTemplate} 上：这里钉"发了哪条命令、参数是什么"，
 * Redis 对这些命令的真实反应由 {@code IdempotentRedisIT} 在真实例上钉。两侧都不重复对方。
 */
class RedisIdempotentStoreTest {

    private static final String SCRIPT = "if redis.call('GET', KEYS[1]) == ARGV[1] then "
            + "return redis.call('DEL', KEYS[1]) else return 0 end";

    /** 只改幂等段的前缀，锁段留缺省——用于证明实现读的是自己那一段。 */
    private static MydddConsistencyProperties properties(String idempotentPrefix) {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setKeyPrefix(idempotentPrefix);
        return properties;
    }

    @Test
    void should_place_the_placeholder_with_one_atomic_set_command_carrying_token_and_ttl() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        assertThat(store.tryAcquire("order:1", "tok-1", Duration.ofSeconds(60))).isTrue();
        assertThat(redis.calls()).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo("setIfAbsent");
            assertThat(call.arguments()).containsExactly("myddd:idempotent:order:1", "tok-1", Duration.ofSeconds(60));
        });
    }

    @Test
    void should_report_a_duplicate_when_the_placeholder_was_already_there() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate().answering(Boolean.FALSE);
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        // 端口只关心"我是不是第一个"，答案原样来自 Redis 的 NX 结果。
        assertThat(store.tryAcquire("order:1", "tok-2", Duration.ofSeconds(60))).isFalse();
        assertThat(redis.calls()).hasSize(1);
    }

    @Test
    void should_never_set_a_placeholder_without_its_lifetime() {
        // 拆开成 SETNX + EXPIRE 会得到"占位成功但进程在两步之间崩溃 → 键永不过期 → 这个业务坐标被永久拒绝"。
        // 这条用例钉住的就是那个形状：setIfAbsent 必须带 TTL 参数，没有两参形态。
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));
        store.tryAcquire("order:1", "tok-1", Duration.ofMinutes(5));

        assertThat(redis.lastArguments()).hasSize(3);
    }

    @Test
    void should_prefix_the_redis_key_with_the_configured_segment() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("app:hold"));

        assertThat(store.idempotentKey("order:1")).isEqualTo("app:hold:order:1");
        store.tryAcquire("order:1", "tok-1", Duration.ofSeconds(1));
        // 两个方向都取证据：拼名函数与真正上线的那个键名必须是同一个串。
        assertThat(redis.lastArguments()).containsExactly("app:hold:order:1", "tok-1", Duration.ofSeconds(1));
    }

    @Test
    void should_not_normalize_the_key_it_sends() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        store.tryAcquire("Order : 1::创建", "tok-1", Duration.ofSeconds(1));

        // 大小写、空白、重复分隔符一律原样：Redis 侧与 DB 侧必须产出同一个键（R2），而加工只会让
        // "扫一眼键"变成"先猜框架改了什么"。
        assertThat(redis.lastArguments()).containsExactly("myddd:idempotent:Order : 1::创建", "tok-1",
                Duration.ofSeconds(1));
    }

    @Test
    void should_read_the_prefix_from_the_idempotent_section_and_ignore_the_lock_one() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getLock().setKeyPrefix("myddd:lock");
        properties.getIdempotent().setKeyPrefix("myddd:idempotent");
        RecordingRedisTemplate redis = new RecordingRedisTemplate();

        new RedisIdempotentStore(redis, properties).tryAcquire("order:1", "tok-1", Duration.ofSeconds(1));

        // 抄错段的表现是"锁键与幂等键挤在同一个命名空间里"，而 {@code redis-cli --scan} 再也圈不出范围。
        assertThat(redis.lastArguments()).containsExactly("myddd:idempotent:order:1", "tok-1", Duration.ofSeconds(1));
    }

    @Test
    void should_fail_loudly_when_redis_gives_no_immediate_answer() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate().answering(null);
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        // null 是流水线/事务形态下的返回值。当首次会击穿幂等，当重复会把正常请求判成 409，所以报而不是猜。
        assertThatThrownBy(() -> store.tryAcquire("order:1", "tok-1", Duration.ofSeconds(60)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("setIfAbsent")
                .hasMessageContaining("executePipelined")
                .hasMessageContaining("store=db");
    }

    @Test
    void should_release_through_a_script_that_compares_the_token_before_deleting() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        store.release("order:1", "tok-1");

        assertThat(redis.calls()).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo("executeScript");
            assertThat(call.arguments()).containsExactly(SCRIPT, java.util.List.of("myddd:idempotent:order:1"),
                    java.util.List.of("tok-1"));
        });
    }

    @Test
    void should_release_in_one_round_trip_without_reading_the_placeholder_first() {
        // "先 GET 比对再 DEL"在两步之间会插进别的请求：它刚把占位重新拿走，本请求就删掉了别人的占位。
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        store.release("order:1", "tok-1");

        assertThat(redis.calls()).hasSize(1);
    }

    @Test
    void should_treat_a_release_that_matched_nothing_as_a_normal_result() {
        // 端口契约："token 不一致或占位不存在时为空操作，不得抛异常"。动作跑得比 TTL 慢是常态，不是错误。
        RecordingRedisTemplate redis = new RecordingRedisTemplate().releasingWith(0L);
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        assertThatCode(() -> store.release("order:1", "tok-other")).doesNotThrowAnyException();
    }

    @Test
    void should_reject_a_blank_idempotency_key_on_both_methods() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        for (String blank : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> store.tryAcquire(blank, "tok-1", Duration.ofSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等键");
            assertThatThrownBy(() -> store.release(blank, "tok-1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等键");
        }
        assertThat(redis.calls()).isEmpty();
    }

    @Test
    void should_reject_a_blank_token_on_both_methods() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        assertThatThrownBy(() -> store.tryAcquire("order:1", " ", Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
        assertThatThrownBy(() -> store.release("order:1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");
        assertThat(redis.calls()).isEmpty();
    }

    @Test
    void should_reject_a_non_positive_ttl_before_touching_redis() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RedisIdempotentStore store = new RedisIdempotentStore(redis, properties("myddd:idempotent"));

        for (Duration ttl : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> store.tryAcquire("order:1", "tok-1", ttl))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ttl");
        }
        assertThat(redis.calls()).isEmpty();
    }

    @Test
    void should_reject_a_null_template_or_properties_at_construction_time() {
        // 与 DefaultLockPort 同一条纪律：接线错在构造期说话，不等第一次调用去读 null 字段。
        assertThatThrownBy(() -> new RedisIdempotentStore(null, properties("myddd:idempotent")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StringRedisTemplate");
        assertThatThrownBy(() -> new RedisIdempotentStore(new RecordingRedisTemplate(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MydddConsistencyProperties");
    }
}

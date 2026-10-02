package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 两种占位存储必须<b>看得见同一件事</b>：同一次调用的物理键名、同一次接线错的同一句话。
 *
 * <p>与等价性契约基类（{@link AbstractIdempotentPortContractTest}）的分工：那边跑真命令、比行为，
 * 其中 Redis 那一半需要 Docker；本类比不了行为（替身连接工厂不发真命令），但比的是<b>纯函数</b>的一面——
 * 键名拼接与入参守卫，因此它在离线环境里就是完整证据。路线图 :124 那句"redis 与 db 两种存储只是落库，
 * 摘要算法必须稳定可复现"里，"换存储以后还是不是同一条键"这一半由它当场钉住。
 *
 * <p>{@link StubRedisConnectionFactory} 在这里只用一个用途：让 {@code StringRedisTemplate} 建得出来
 * （一手实测：它的 {@code afterPropertiesSet()} 断言连接工厂在场）。本类一条命令都不发。
 */
class IdempotentStoreEquivalenceTest {

    private static final String PREFIX = "myddd:eq";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final RedisIdempotentStore redis = new RedisIdempotentStore(
            new org.springframework.data.redis.core.StringRedisTemplate(new StubRedisConnectionFactory()),
            properties());
    private final JdbcIdempotentStore jdbc = new JdbcIdempotentStore(IdempotentH2.open().dataSource(), properties(),
            JdbcIdempotentStore.DEFAULT_TABLE);

    /** 同一条逻辑键在两种存储里是同一个物理键：换存储不等于换键空间。 */
    @Test
    void should_derive_the_same_physical_key_in_both_stores() {
        for (String key : new String[] {"order:1", "Refund-2", "a:b:c", "中文键"}) {
            assertThat(this.jdbc.idempotentKey(key)).as("键 %s", key).isEqualTo(this.redis.idempotentKey(key));
        }
        assertThat(this.redis.idempotentKey("order:1")).isEqualTo(PREFIX + ":order:1");
    }

    /** 同一份非法入参在两种存储里抛同一句话：换存储不许换契约。 */
    @Test
    void should_reject_the_same_illegal_arguments_with_the_same_message() {
        assertThat(messageOf(() -> this.redis.tryAcquire(" ", "token-a", TTL), IllegalArgumentException.class))
                .isEqualTo(messageOf(() -> this.jdbc.tryAcquire(" ", "token-a", TTL),
                        IllegalArgumentException.class));
        assertThat(messageOf(() -> this.redis.release("k", ""), IllegalArgumentException.class))
                .isEqualTo(messageOf(() -> this.jdbc.release("k", ""), IllegalArgumentException.class));
        for (Duration illegal : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-5)}) {
            assertThat(messageOf(() -> this.redis.tryAcquire("k", "token-a", illegal),
                    IllegalArgumentException.class))
                            .as("ttl %s", illegal)
                            .isEqualTo(messageOf(() -> this.jdbc.tryAcquire("k", "token-a", illegal),
                                    IllegalArgumentException.class));
        }
        // 反面：不许有哪一种存储对同一个入参网开一面。
        assertThatThrownBy(() -> this.jdbc.tryAcquire("k", "token-a", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MydddConsistencyProperties properties() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setKeyPrefix(PREFIX);
        return properties;
    }

    private static String messageOf(Runnable call, Class<? extends RuntimeException> expected) {
        return catchThrowableOfType(call::run, expected).getMessage();
    }
}

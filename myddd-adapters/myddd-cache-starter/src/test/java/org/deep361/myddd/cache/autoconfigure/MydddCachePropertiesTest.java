package org.deep361.myddd.cache.autoconfigure;

import java.time.Duration;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缓存配置键的交付缺省值与启动期校验。
 *
 * <p>只测"默认交付值是什么"与"哪种配法会让应用起不来"，不测绑定机制（松散绑定、枚举转换是 Boot 的规矩）。
 *
 * <p>Task 22 曾有一条"配 {@code multi} 就启动失败"的用例，因为那时 L2 还没有实现。Task 23 交付了两级装配后
 * 该拒绝分支被删除，缺省值同时翻回 {@code multi}（§1.2.1 的终态缺省），所以本文件的 P1 用例改的是<b>缺省取值</b>
 * 而不是新增用例。
 */
class MydddCachePropertiesTest {

    @Test
    void should_default_to_two_levels_with_a_prefixed_redis_and_thirty_minute_ttl() {
        MydddCacheProperties properties = new MydddCacheProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getType()).isEqualTo(MydddCacheProperties.Type.MULTI);
        assertThat(properties.getCacheNames()).isEmpty();
        assertThat(properties.getCaffeine().getMaxSize()).isEqualTo(1000L);
        assertThat(properties.getCaffeine().getExpireAfterWrite()).isEqualTo(Duration.ofMinutes(10));
        // 前缀段是共享 Redis 实例上"哪些键属于本应用"的唯一凭据，缺省必须给而不是留空让人自己想起来配。
        assertThat(properties.getKeyPrefix()).isEqualTo("myddd:cache");
        assertThat(properties.getRedis().getTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getRedis().getTtlPerCache()).isEmpty();
        // 默认不缓存 null：宁可对同一个空结果多打一次库，也不要"新增数据在 TTL 内查不到"。
        assertThat(properties.isCacheNullValues()).isFalse();
    }

    @Test
    void should_refuse_a_missing_type_because_it_decides_which_manager_gets_built() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setType(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.cache.type");
    }

    @Test
    void should_refuse_a_max_size_that_cannot_hold_a_single_entry() {
        for (long illegal : new long[] {0L, -1L}) {
            MydddCacheProperties properties = new MydddCacheProperties();
            properties.getCaffeine().setMaxSize(illegal);

            // Caffeine 自己不会为 maximumSize(0) 报错，只会让缓存永远存不下东西——那种静默比启动失败难查。
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.cache.caffeine.max-size");
        }
    }

    @Test
    void should_refuse_an_expiration_that_evicts_on_arrival() {
        for (Duration illegal : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            MydddCacheProperties properties = new MydddCacheProperties();
            properties.getCaffeine().setExpireAfterWrite(illegal);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.cache.caffeine.expire-after-write");
        }
    }

    @Test
    void should_refuse_a_blank_key_prefix_because_it_would_read_another_apps_keys() {
        for (String illegal : new String[] {null, "", "   "}) {
            MydddCacheProperties properties = new MydddCacheProperties();
            properties.setKeyPrefix(illegal);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.cache.key-prefix")
                    // 消息必须给出路：只说"不能为空"会让人去猜该填什么，而不是直接改成退路取值。
                    .hasMessageContaining("myddd.cache.type=caffeine");
        }
    }

    @Test
    void should_refuse_a_redis_ttl_that_never_expires() {
        for (Duration illegal : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            MydddCacheProperties properties = new MydddCacheProperties();
            properties.getRedis().setTtl(illegal);

            // 没有 TTL 的 Redis 缓存＝删掉的数据在重启应用之后还能读回来，这是最贵的一类脏读。
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.cache.redis.ttl");
        }
    }

    @Test
    void should_refuse_a_ttl_override_that_can_never_match_a_cache_name() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.getRedis().getTtlPerCache().put("  ", Duration.ofMinutes(5));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.cache.redis.ttl-per-cache");
    }

    @Test
    void should_refuse_a_ttl_override_that_never_expires() {
        // null 是绑定得到的配法：只写了键名没给时长（myddd.cache.redis.ttl-per-cache[foo]=）就是这个值。
        for (Duration illegal : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
            MydddCacheProperties properties = new MydddCacheProperties();
            properties.getRedis().getTtlPerCache().put("productDetail", illegal);

            // 消息要带上缓存名：让人知道是哪一条覆盖写的，而不是"有某个覆盖非法"。
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.cache.redis.ttl-per-cache.productDetail");
        }
    }

    @Test
    void should_not_check_l2_keys_when_the_form_has_no_l2() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setType(MydddCacheProperties.Type.CAFFEINE);
        properties.setKeyPrefix(null);
        properties.getRedis().setTtl(null);
        properties.getRedis().getTtlPerCache().put("x", Duration.ZERO);

        // caffeine 形态下这三个键没有任何代码读：为一个不生效的键判应用起不来，比它稍后切回 multi 时报错更糟。
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_check_l1_keys_even_in_the_caffeine_only_form() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setType(MydddCacheProperties.Type.CAFFEINE);
        properties.getCaffeine().setExpireAfterWrite(Duration.ZERO);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.cache.caffeine.expire-after-write");
    }

    @Test
    void should_accept_the_smallest_sane_values() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.getCaffeine().setMaxSize(1L);
        properties.getCaffeine().setExpireAfterWrite(Duration.ofMillis(1));
        properties.setKeyPrefix("x");
        properties.getRedis().setTtl(Duration.ofMillis(1));
        properties.getRedis().getTtlPerCache().put("productDetail", Duration.ofMillis(1));

        // 下界两侧各钉一条：写成 <= 1 会拒掉一个合法但很小的配法。
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_skip_validation_when_the_adapter_is_switched_off() {
        MydddCacheProperties properties = new MydddCacheProperties();
        properties.setEnabled(false);
        properties.setType(null);
        properties.getCaffeine().setMaxSize(0L);
        properties.setKeyPrefix("  ");
        properties.getRedis().setTtl(Duration.ZERO);

        // 关掉即"这套配置一概不算数"：校验也要跟着跳过，否则关开关反而让应用起不来。
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}

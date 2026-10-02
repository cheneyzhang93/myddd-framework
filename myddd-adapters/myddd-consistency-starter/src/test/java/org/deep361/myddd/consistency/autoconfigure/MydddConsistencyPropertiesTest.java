package org.deep361.myddd.consistency.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.deep361.myddd.consistency.idempotent.JdbcIdempotentStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一致性能力配置键的交付缺省值与启动期校验。
 *
 * <p>只测"默认交付值是什么"与"哪种配法会让应用起不来"，不测绑定机制（松散绑定、{@code Duration} 换算
 * 是 Boot 的规矩，§0.3-5）。装配面的证据在 {@code MydddConsistencyAutoConfigurationTest}，
 * 真 Redis 的证据在 {@code LockRedisIT}。
 *
 * <p><b>每一段只在自身 {@code enabled=true} 时被查</b>是这组用例的重心：一个"业务已经不用这个能力"的进程
 * 不该因为遗留的非法配置键而起不来（与缓存适配器 {@code type=caffeine} 时不校验 {@code redis.*}
 * 是同一条裁定）。
 *
 * <p><b>另一重心是两段互不遮蔽</b>：锁与幂等共用这份配置，{@code validate()} 因此是一个分发器而不是
 * 一条早退的链。"锁关了 → 直接 return"的形状会让 {@code lock.enabled=false} 的应用再也得不到
 * "幂等前缀写成空白"这句话——两个开关各管一半、错误互相顶掉，正是启动期校验最难自证的一种形状。
 * 所以下面两组用例按<b>两个方向</b>各钉一条。
 */
class MydddConsistencyPropertiesTest {

    @Test
    void should_default_to_an_enabled_lock_with_a_prefixed_key_and_a_thirty_second_watchdog() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getLock().isEnabled()).isTrue();
        // 前缀段是共享 Redis 实例上"哪些锁属于本应用"的唯一凭据，缺省必须给而不是留空让人想起来配。
        assertThat(properties.getLock().getKeyPrefix()).isEqualTo("myddd:lock");
        // 30s 与 Redisson 自身的缺省一致（那个数字在 RedissonClientFactoryTest 里对着 Redisson 读回过）。
        assertThat(properties.getLock().getWatchdogTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_blank_key_prefix_because_bare_lock_names_collide_with_other_applications() {
        for (String prefix : List.of("", "  ", "\t")) {
            MydddConsistencyProperties properties = new MydddConsistencyProperties();
            properties.getLock().setKeyPrefix(prefix);

            // null 走的是同一句话，单独一条断言在下面（List.of 装不了 null）。
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.lock.key-prefix")
                    .hasMessageContaining("myddd.consistency.lock.enabled=false");
        }

        MydddConsistencyProperties nullPrefix = new MydddConsistencyProperties();
        nullPrefix.getLock().setKeyPrefix(null);
        assertThatThrownBy(nullPrefix::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.consistency.lock.key-prefix");
    }

    @Test
    void should_refuse_a_watchdog_cadence_that_never_renews() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            MydddConsistencyProperties properties = new MydddConsistencyProperties();
            properties.getLock().setWatchdogTimeout(timeout);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.lock.watchdog-timeout");
        }

        MydddConsistencyProperties nullTimeout = new MydddConsistencyProperties();
        nullTimeout.getLock().setWatchdogTimeout(null);
        assertThatThrownBy(nullTimeout::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("永不续租");
    }

    @Test
    void should_keep_a_watchdog_cadence_that_is_deliberately_short() {
        // IT 用 3s 来演"续租确实发生了"；校验不许把小于 Redisson 缺省的正值当成非法。
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getLock().setWatchdogTimeout(Duration.ofMillis(500));

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_skip_validation_when_the_capability_is_switched_off() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.setEnabled(false);
        properties.getLock().setKeyPrefix("");
        properties.getLock().setWatchdogTimeout(Duration.ZERO);

        // 关掉整个能力以后为一个不生效的键判应用起不来，比让它稍后在切回 true 时报错更糟。
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_skip_lock_validation_when_only_the_lock_is_switched_off() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getLock().setEnabled(false);
        properties.getLock().setKeyPrefix(null);
        properties.getLock().setWatchdogTimeout(Duration.ZERO);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_validate_every_time_it_is_called_because_it_has_no_state_to_invalidate() {
        // @PostConstruct 只会在 bean 创建时跑一次，但校验方法本身必须是可重入的纯函数：
        // 装配测试里我们会直接调它两次（一次合法、一次非法），不能出现"第二次不报错"。
        MydddConsistencyProperties legal = new MydddConsistencyProperties();
        assertThatCode(legal::validate).doesNotThrowAnyException();
        assertThatCode(legal::validate).doesNotThrowAnyException();

        legal.getLock().setKeyPrefix(" ");
        assertThatThrownBy(legal::validate).isInstanceOf(FrameworkException.class);
        assertThatThrownBy(legal::validate).isInstanceOf(FrameworkException.class);
    }

    @Test
    void should_default_to_an_enabled_idempotent_store_backed_by_redis_with_a_prefixed_key() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();

        assertThat(properties.getIdempotent().isEnabled()).isTrue();
        // 前缀段的理由与锁那一条完全相同，缺省值也必须给：Redis 常是多应用共用的。
        assertThat(properties.getIdempotent().getKeyPrefix()).isEqualTo("myddd:idempotent");
        // 缺省 redis：多数应用已有 Redis，且 SET NX PX 天然带 TTL，不需要额外清理任务。
        assertThat(properties.getIdempotent().getStore())
                .isEqualTo(MydddConsistencyProperties.Idempotent.Store.REDIS);
        // 选了 db 时表名的缺省值就是交付参考 DDL 那张表：两端各有一条用例钉住这个串
        // （JdbcIdempotentStoreTest 的 DQ4 与本模块的装配用例），改表名要三处一起改才会绿。
        assertThat(properties.getIdempotent().getDb().getTable())
                .isEqualTo(JdbcIdempotentStore.DEFAULT_TABLE);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    /**
     * P 组：{@code db.table} 的形状校验<b>不</b>在 {@code validate()} 里，而在存储构造器。
     *
     * <p>与 {@code MydddPersistenceProperties.Outbox.table} 同一条裁定（那边把这条写在字段注释上）：
     * 表名要拼进 SQL 文本，唯一的校验落点就是拼它的那个对象；在配置类里再校验一遍会得出第三种说法，
     * 而且让"选了 redis 的应用"为一个根本用不到的键起不来。
     */
    @Test
    void should_leave_the_db_table_shape_check_to_the_store_that_splices_it_into_sql() {
        for (String illegal : List.of("", "  ", "acme; drop table t")) {
            MydddConsistencyProperties properties = new MydddConsistencyProperties();
            properties.getIdempotent().setStore(MydddConsistencyProperties.Idempotent.Store.DB);
            properties.getIdempotent().getDb().setTable(illegal);

            assertThatCode(properties::validate).as("表名 %s", illegal).doesNotThrowAnyException();
            // 失败点在构造器，而且话术指得到那个配置键：
            assertThatThrownBy(() -> new JdbcIdempotentStore(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(), properties, illegal))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.consistency.idempotent.db.table");
        }
    }

    @Test
    void should_refuse_a_blank_idempotent_key_prefix_because_bare_placeholder_keys_collide() {
        for (String prefix : List.of("", "  ", "\t")) {
            MydddConsistencyProperties properties = new MydddConsistencyProperties();
            properties.getIdempotent().setKeyPrefix(prefix);

            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.idempotent.key-prefix")
                    // 报话要给出去路，而且不能给错：这条开关关的是框架的实现，不是"注解没人读"。
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=false");
        }

        MydddConsistencyProperties nullPrefix = new MydddConsistencyProperties();
        nullPrefix.getIdempotent().setKeyPrefix(null);
        assertThatThrownBy(nullPrefix::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.consistency.idempotent.key-prefix");
    }

    @Test
    void should_refuse_a_null_idempotent_store_because_neither_storage_branch_would_be_assembled() {
        // 枚举绑不上未知值（写 store=mysql 在绑定阶段就失败），但 setter 是公开 API：
        // 直接 new 配置对象的应用能把 store 设成 null，那会让两条链都不装而注解照旧生效。
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setStore(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.consistency.idempotent.store");
    }

    @Test
    void should_skip_idempotent_validation_when_only_the_idempotent_implementation_is_off() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setEnabled(false);
        properties.getIdempotent().setKeyPrefix("");
        properties.getIdempotent().setStore(null);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_not_let_a_disabled_lock_mask_an_illegal_idempotent_prefix() {
        // 分发器的第一个方向：lock.enabled=false 不许把幂等段的错误一起顶掉。
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getLock().setEnabled(false);
        properties.getLock().setKeyPrefix(null);
        properties.getLock().setWatchdogTimeout(Duration.ZERO);
        properties.getIdempotent().setKeyPrefix("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.consistency.idempotent.key-prefix");
    }

    @Test
    void should_not_let_a_disabled_idempotent_mask_an_illegal_lock_prefix() {
        // 第二个方向。两条合起来才说得出"两段各自独立"：只钉一边会得到一个仍然会互相遮蔽的实现，
        // 而那在一遍用例里看不出来。
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setEnabled(false);
        properties.getIdempotent().setKeyPrefix(null);
        properties.getIdempotent().setStore(null);
        properties.getLock().setKeyPrefix("");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.consistency.lock.key-prefix");
    }
}

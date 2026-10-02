package org.deep361.myddd.consistency.autoconfigure;

import java.time.Duration;
import javax.sql.DataSource;
import org.deep361.myddd.consistency.idempotent.JdbcIdempotentStore;
import org.deep361.myddd.consistency.idempotent.RedisIdempotentStore;
import org.deep361.myddd.consistency.idempotent.StubRedisConnectionFactory;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等占位存储的装配面：{@link MydddConsistencyIdempotentAutoConfiguration} 在不在、什么条件下让位、
 * 配置键有没有真的走到行为里。
 *
 * <p>与 {@code RedisIdempotentStoreTest} 的分工同一条：那边用替身模板钉<b>命令形状</b>（一条 SET NX PX、
 * 释放走脚本、不预读），这里只钉<b>条件与注入</b>。真 Redis 的语义证据在 {@code IdempotentRedisIT}。
 *
 * <p><b>happy path 在这里是完整可跑的</b>（与锁侧不同）：{@code StringRedisTemplate} 建出来不连 Redis，
 * 第一条命令才连，而本类一条命令都不发。需要它在场的那个重载 {@link #withTemplate()} 因此挂的是
 * {@link StubRedisConnectionFactory}——一手实测：模板的 {@code afterPropertiesSet()} 会断言连接工厂在场
 * （{@code RedisConnectionFactory is required}），空构造在容器里活不下来。所以缺省分支能真的断言
 * "拿到的就是 {@code RedisIdempotentStore}"，不必像 {@code MydddConsistencyAutoConfigurationTest}
 * 那样只能退而证明"那个 bean 方法消费了哪个接口"。
 */
class MydddConsistencyIdempotentAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddConsistencyIdempotentAutoConfiguration.class));

    private final StubRedisConnectionFactory connectionFactory = new StubRedisConnectionFactory();

    /** 带 {@code StringRedisTemplate} 的形态：Redis 那一支的原料（仍然不连网，见类注释）。 */
    private ApplicationContextRunner withTemplate() {
        return this.runner
                .withBean(RedisConnectionFactory.class, () -> this.connectionFactory)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(this.connectionFactory));
    }

    /** 业务自带的端口：只记录被叫到没有，装配证据要落在"框架让位"上。 */
    private static final class CustomPort implements IdempotentPort {

        private int acquisitions;

        @Override
        public boolean tryAcquire(String key, String token, Duration ttl) {
            this.acquisitions++;
            return true;
        }

        @Override
        public void release(String key, String token) {
            // 让位用例不发命令：端口替身保持沉默即可
        }
    }

    @Test
    void should_assemble_the_redis_store_by_default_when_a_string_redis_template_exists() {
        withTemplate().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotentPort.class);
            assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(RedisIdempotentStore.class);
            // 装配过程一次都没去连网（连了就会撞上替身那句暗号）。
            assertThat(context.getBean(StringRedisTemplate.class).getConnectionFactory())
                    .isSameAs(this.connectionFactory);
        });
    }

    @Test
    void should_say_what_is_missing_when_the_selected_store_has_no_template_to_use() {
        // 缺省就是 store=redis，所以离线（没有 StringRedisTemplate bean）的上下文必须说清缺什么，
        // 而不是留下一个"标了 @Idempotent 却没人存占位"的容器。
        this.runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("StringRedisTemplate")
                    .hasMessageContaining("spring.data.redis")
                    .hasMessageContaining("myddd.consistency.idempotent.store=db")
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=false");
        });
    }

    @Test
    void should_take_the_redis_branch_when_the_store_is_written_in_upper_case() {
        // store 绑到枚举上是宽松绑定（REDIS/redis/Redis 都收），而装配侧的 havingValue 比的是字符串。
        // 两半必须都认：写成 store=REDIS 的应用以为自己在用 Redis，若条件按字面量比大小就成了"谁都不装"。
        withTemplate().withPropertyValues("myddd.consistency.idempotent.store=REDIS").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(RedisIdempotentStore.class);
        });
    }

    @Test
    void should_not_assemble_the_redis_store_when_another_store_is_selected() {
        // store=db 时 Redis 这一支整条退让：端口照旧有，但换成了 JDBC 实现，而且那条 StringRedisTemplate
        // 从头到尾没被消费过（替身连接工厂一旦发出连接就会撞上它自己的暗号，见 StubRedisConnectionFactory）。
        withTemplate().withBean(DataSource.class, MydddConsistencyIdempotentAutoConfigurationTest::offlineDataSource)
                .withPropertyValues("myddd.consistency.idempotent.store=db").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotentPort.class);
                    assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(JdbcIdempotentStore.class);
                });
    }

    // ------------------------------------------------------------ store=db 那一支（Task 27）

    /** 一个离线可用的数据源：H2 内存库，容器建它不连任何外部服务。 */
    private static DataSource offlineDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ac-idempotent;MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        return dataSource;
    }

    /** 两个数据源、无 {@code @Primary}：按类型解析必须报歧义，而不是随机挑一个库写占位。 */
    @Configuration(proxyBeanMethods = false)
    static class TwoPlainDataSources {

        @Bean
        DataSource first() {
            return offlineDataSource();
        }

        @Bean
        DataSource second() {
            return offlineDataSource();
        }
    }

    /** 同上一对，但其中一个标了 {@code @Primary}：装配必须照它挑。 */
    @Configuration(proxyBeanMethods = false)
    static class PrimaryDataSourcePair {

        @Bean
        @Primary
        DataSource chosen() {
            return offlineDataSource();
        }

        @Bean
        DataSource other() {
            return offlineDataSource();
        }
    }

    @Test
    void should_assemble_the_jdbc_store_when_the_db_branch_is_selected() {
        this.runner.withBean(DataSource.class, MydddConsistencyIdempotentAutoConfigurationTest::offlineDataSource)
                .withPropertyValues("myddd.consistency.idempotent.store=db").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(JdbcIdempotentStore.class);
                });
    }

    @Test
    void should_bind_the_configured_table_name_all_the_way_into_the_jdbc_store() {
        this.runner.withBean(DataSource.class, MydddConsistencyIdempotentAutoConfigurationTest::offlineDataSource)
                .withPropertyValues("myddd.consistency.idempotent.store=db",
                        "myddd.consistency.idempotent.db.table=acme_idempotent")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JdbcIdempotentStore store = (JdbcIdempotentStore) context.getBean(IdempotentPort.class);
                    assertThat(store.table()).isEqualTo("acme_idempotent");
                    // 键前缀那一段照样走在物理键里：换存储不改键空间（R2 等价性的存储侧那一半）。
                    assertThat(store.idempotentKey("order:1")).isEqualTo("myddd:idempotent:order:1");
                });
    }

    @Test
    void should_say_what_is_missing_when_the_db_branch_has_no_data_source() {
        // 与 Redis 一侧缺模板同一条裁定：store=db 是显式选择，缺原料就在启动期说清出路，
        // 而不是留下一个"标了 @Idempotent 却没人存占位"的容器把失败推到第一次调用上。
        this.runner.withPropertyValues("myddd.consistency.idempotent.store=db").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("DataSource")
                    .hasMessageContaining("spring.datasource")
                    .hasMessageContaining("myddd.consistency.idempotent.store=redis")
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=false");
        });
    }

    @Test
    void should_say_which_candidate_to_make_primary_when_the_db_branch_sees_two_data_sources() {
        // 分库/多数据源是这套脚手架的目标形态之一。此时"随便挑一个库写占位"是最坏结局：占位写在 A 库、
        // 业务事务在 B 库，幂等窗口的原子性当场失效，而且只有在两个库都恰好有同名键时才现形。
        // 歧义这一类不能用 hasRootCauseInstanceOf(FrameworkException)：Spring 在工厂方法外又包了一层
        // BeanInstantiationException，而我们把 NoUniqueBeanDefinitionException 当作 cause 挂在
        // FrameworkException 上（保留原始判定依据），所以根因是 Spring 的那个。
        this.runner.withPropertyValues("myddd.consistency.idempotent.store=db")
                .withUserConfiguration(TwoPlainDataSources.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure()
                            .hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
                            .hasStackTraceContaining("DataSource 有多个候选且没有 @Primary")
                            // 报话要带上表名与出路：用户手里那两份数据源，得知道该给哪一份标主候选。
                            .hasStackTraceContaining("myddd_idempotent_record")
                            .hasStackTraceContaining("@Primary")
                            .hasStackTraceContaining("myddd.consistency.idempotent.store=redis");
                });
    }

    @Test
    void should_take_the_primary_data_source_when_the_db_branch_sees_several() {
        // 上一条的正面对照：歧义只在"没有主候选"时出现。有 @Primary 却不照它挑，等于把上面那条
        // 报错的话术变成假警报，业务改不动。
        this.runner.withPropertyValues("myddd.consistency.idempotent.store=db")
                .withUserConfiguration(PrimaryDataSourcePair.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotentPort.class);
                    assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(JdbcIdempotentStore.class);
                });
    }

    @Test
    void should_back_off_the_db_branch_when_spring_jdbc_is_not_on_the_classpath() {
        // spring-jdbc 在本模块是 optional 依赖：没带它的应用连这一支都不会被解析，也就不会因为
        // "配了 store=db 却少了个 jar"而起不来。端口缺席时第一次 @Idempotent 调用报出那句话
        // （与 MydddConsistencyIdempotentAopAutoConfigurationTest 里缺端口那条同一个形状）。
        this.runner.withPropertyValues("myddd.consistency.idempotent.store=db")
                .withClassLoader(new FilteredClassLoader(JdbcTemplate.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotentPort.class);
                });
    }

    @Test
    void should_reach_the_jdbc_store_without_redis_on_the_classpath() {
        // 这条钉的是"只要幂等、不要 Redis"的那批应用：RedisTemplate 整条从类路径遮掉，
        // 选了 db 的进程照样拿得到端口（Redis 那一支的类级条件不满足就整块跳过）。
        this.runner.withBean(DataSource.class, MydddConsistencyIdempotentAutoConfigurationTest::offlineDataSource)
                .withPropertyValues("myddd.consistency.idempotent.store=db")
                .withClassLoader(new FilteredClassLoader(StringRedisTemplate.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(JdbcIdempotentStore.class);
                });
    }

    @Test
    void should_back_off_the_db_branch_when_the_application_provides_its_own_port() {
        CustomPort custom = new CustomPort();

        // 让位要退得干净：这里刻意<b>不</b>给 DataSource。@ConditionalOnMissingBean 挂在方法上，
        // 所以自带端口的容器连那句"缺 DataSource"都不该问，否则"我自己实现了端口"会被框架判成配置错。
        this.runner.withBean(IdempotentPort.class, () -> custom)
                .withPropertyValues("myddd.consistency.idempotent.store=db").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IdempotentPort.class)).isSameAs(custom);
                });
    }

    @Test
    void should_skip_the_db_branch_too_when_the_framework_implementation_switch_is_off() {
        this.runner.withBean(DataSource.class, MydddConsistencyIdempotentAutoConfigurationTest::offlineDataSource)
                .withPropertyValues("myddd.consistency.idempotent.store=db",
                        "myddd.consistency.idempotent.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotentPort.class);
                });
    }

    @Test
    void should_back_off_when_the_application_provides_its_own_idempotent_port() {
        CustomPort custom = new CustomPort();

        // 注意这里<b>不</b>给 StringRedisTemplate：让位要退得干净，不能还留着一条要求 Redis 的分支。
        this.runner.withBean(IdempotentPort.class, () -> custom).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotentPort.class);
            assertThat(context.getBean(IdempotentPort.class)).isSameAs(custom);
        });
    }

    @Test
    void should_still_validate_the_configuration_when_the_framework_backs_off() {
        // 让位不等于失声：自带端口却把幂等前缀配成空白，依然在启动期失败（Task 24 的同一条纪律）。
        CustomPort custom = new CustomPort();

        withTemplate().withBean(IdempotentPort.class, () -> custom)
                .withPropertyValues("myddd.consistency.idempotent.key-prefix=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("myddd.consistency.idempotent.key-prefix");
                });
    }

    @Test
    void should_bind_every_documented_key_and_reach_the_store_with_them() {
        withTemplate().withPropertyValues("myddd.consistency.idempotent.key-prefix=acme:idem").run(context -> {
            assertThat(context).hasNotFailed();
            MydddConsistencyProperties properties = context.getBean(MydddConsistencyProperties.class);
            assertThat(properties.getIdempotent().getKeyPrefix()).isEqualTo("acme:idem");
            assertThat(properties.getIdempotent().getStore())
                    .isEqualTo(MydddConsistencyProperties.Idempotent.Store.REDIS);
            // 键不只是被绑上了，还要真的走到行为里：占位键由配置前缀拼出。
            RedisIdempotentStore store = (RedisIdempotentStore) context.getBean(IdempotentPort.class);
            assertThat(store.idempotentKey("order:1")).isEqualTo("acme:idem:order:1");
        });
    }

    @Test
    void should_skip_the_store_when_the_framework_implementation_switch_is_off() {
        // idempotent.enabled=false 关的是"框架交付这份实现"：模板在场也不建端口。
        // 切面照旧在场是另一条用例的责任（见 Aop 那个测试类），两条合起来才是这个开关的完整语义。
        withTemplate().withPropertyValues("myddd.consistency.idempotent.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotentPort.class);
        });
    }

    @Test
    void should_skip_validation_and_the_store_when_the_whole_group_is_off() {
        withTemplate().withPropertyValues("myddd.consistency.enabled=false",
                "myddd.consistency.idempotent.key-prefix=",
                "myddd.consistency.idempotent.store=mysql")
                .run(context -> {
                    // store=mysql 这种非法取值也不报：总开关关掉以后配置 bean 根本不创建，没人去绑它。
                    // 与锁侧 should_skip_everything_when_the_capability_switch_is_off 同一条裁定。
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdempotentPort.class);
                });
    }

    @Test
    void should_back_off_the_store_branch_when_redis_is_not_on_the_classpath() {
        // 嵌套分支的类级 @ConditionalOnClass(StringRedisTemplate.class) 的全部意义：没引 redis 依赖的应用
        // 照样起得来（它本来也不会有 StringRedisTemplate bean，走不到那句报错）。
        this.runner.withClassLoader(new FilteredClassLoader(StringRedisTemplate.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotentPort.class);
        });
    }

    @Test
    void should_reach_the_idempotent_store_without_redisson_on_the_classpath() {
        // 这条钉的是"为什么幂等不寄在 MydddConsistencyAutoConfiguration 里"：那个类的类级条件要
        // RedissonClient 在场。把 Redisson 整个从类路径遮掉，幂等这一支必须照旧产出端口。
        withTemplate().withClassLoader(new FilteredClassLoader(RedissonClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IdempotentPort.class)).isInstanceOf(RedisIdempotentStore.class);
        });
    }
}

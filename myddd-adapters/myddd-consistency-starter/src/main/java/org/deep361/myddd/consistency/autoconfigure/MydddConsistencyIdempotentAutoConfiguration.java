package org.deep361.myddd.consistency.autoconfigure;

import org.deep361.myddd.consistency.idempotent.JdbcIdempotentStore;
import org.deep361.myddd.consistency.idempotent.RedisIdempotentStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 幂等能力（{@link IdempotentPort}）的自动装配：缺省把占位存进 Redis。
 *
 * <p><b>为什么独立成一个顶层装配类，而不是 {@link MydddConsistencyAutoConfiguration} 的第二个嵌套分支</b>：
 * 那个类的类级条件是 {@code @ConditionalOnClass(RedissonClient.class)}——幂等不经过 Redisson，
 * 寄在里面等于让"没带 Redisson 的应用"拿不到幂等实现，而那类应用恰恰是"只要幂等、不要锁"的那批。
 * 声明面（切面）在 {@code MydddConsistencyIdempotentAopAutoConfiguration}，与锁侧同一条"两个面两种条件"的裁定。
 *
 * <p><b>连接参数不在这类里</b>：{@code spring.data.redis.*} 由 Boot 解析，{@code StringRedisTemplate}
 * 是 {@code DataRedisAutoConfiguration} 的产物（一手实测：它的 {@code stringRedisTemplate} bean 只依赖
 * {@code RedisConnectionFactory}）。这里只消费那个 bean，并用 {@link ObjectProvider} 把"缺 bean"
 * 说成一句能照着做的话，而不是 {@code UnsatisfiedDependencyException} 里一串 bean 名。
 *
 * <p><b>两个开关分别挂在类与方法上</b>：{@code myddd.consistency.idempotent.enabled} 管的是"框架要不要
 * 交付这份占位存储"（类级，与 {@code myddd.consistency.lock.enabled} 同一条语义——它<b>不</b>摘切面，
 * 见 {@code MydddConsistencyIdempotentAopAutoConfiguration}），{@code myddd.consistency.idempotent.store}
 * 管存储选择（方法级）。同一个元素上不能叠两个 {@code @ConditionalOnProperty}（它不是可重复注解，
 * 一手实测编译期就拒），而"不要这份实现"与"换一种存储"本来就是一对不同的决定——前者整块退让，
 * 后者只让 Redis 这一支退让、{@link DbIdempotentStoreConfiguration DB 那一支}接手。
 *
 * <p><b>两种存储各一个嵌套分支，各带自己的类级依赖条件</b>（{@code StringRedisTemplate} /
 * {@code JdbcTemplate}）：{@code spring-jdbc} 在本模块 POM 里是 {@code optional}，只用 Redis 的应用
 * 不会被拖进一个 JDBC 依赖，而 {@code store=db} 的应用带了它才会拿到 DB 实现。这一支缺依赖时
 * <b>不静默退让</b>的口径与 Redis 一侧一致：切面照旧拦截，缺端口时第一次调用报出那句话
 * （Task 26 的 AC4 钉的就是这个形状）。
 *
 * <p><b>业务自带 {@code IdempotentPort} 时框架让位</b>（{@link ConditionalOnMissingBean}）：与锁/缓存/持久化侧
 * 同一条纪律。让位不等于失声——{@link MydddConsistencyProperties#validate()} 挂在配置 bean 的
 * {@code @PostConstruct} 上，与这里是否产出 bean 无关，所以"配了空白前缀又自带端口"依然在启动期失败。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myddd.consistency", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddConsistencyProperties.class)
public class MydddConsistencyIdempotentAutoConfiguration {

    /** Redis 占位存储分支：{@code myddd.consistency.idempotent.enabled=false} 时整块退让（切面不受影响）。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "myddd.consistency.idempotent", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class RedisIdempotentStoreConfiguration {

        /**
         * 建 Redis 版幂等端口。<b>方法级条件只认 {@code store=redis}</b>（缺省）：
         * 选了 {@code db} 的进程在这里整条退让，由 {@link DbIdempotentStoreConfiguration} 接上同一个
         * {@link IdempotentPort} 类型，于是"同一能力只有一份自动装配产出"成立（双实现纪律，
         * 见计划文档 §4 的裁定 6）。
         */
        @Bean
        @ConditionalOnMissingBean(IdempotentPort.class)
        @ConditionalOnProperty(prefix = "myddd.consistency.idempotent", name = "store", havingValue = "redis",
                matchIfMissing = true)
        IdempotentPort mydddRedisIdempotentStore(MydddConsistencyProperties properties,
                ObjectProvider<StringRedisTemplate> redisTemplates) {
            StringRedisTemplate template = redisTemplates.getIfAvailable();
            if (template == null) {
                throw new FrameworkException("myddd.consistency.idempotent.store=redis（缺省值）需要一个 "
                        + "StringRedisTemplate bean，容器里没有它。要么引入 Boot 的 redis 自动装配"
                        + "（spring-boot-data-redis）并配 spring.data.redis.*——测试里用 myddd-test-starter 的 "
                        + "MydddRedisContainer，要么换存储：myddd.consistency.idempotent.store=db"
                        + "（框架的 JDBC 实现，需要 spring-jdbc 与容器里的 DataSource，建表脚本见 classpath:"
                        + "db/ddl-reference/V2__myddd_idempotent_record.sql），"
                        + "要么自己提供一个 IdempotentPort，要么关掉框架的这份实现："
                        + "myddd.consistency.idempotent.enabled=false（它关掉的是存储实现，@Idempotent 注解"
                        + "照旧被拦截，缺端口时在调用时报同样的这句话，不会变成放行）");
            }
            return new RedisIdempotentStore(template, properties);
        }
    }

    /**
     * 数据库占位存储分支（{@code myddd.consistency.idempotent.store=db}）。
     *
     * <p>类级条件 {@code @ConditionalOnClass(JdbcTemplate.class)} 与 Redis 一侧的
     * {@code @ConditionalOnClass(StringRedisTemplate.class)} 对称：{@code spring-jdbc} 在本模块是
     * {@code optional} 依赖，没带它的应用（也就是绝大多数只用 Redis 的应用）连这一支都不会被解析。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "myddd.consistency.idempotent", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class DbIdempotentStoreConfiguration {

        /**
         * 建 JDBC 版幂等端口。缺 {@code DataSource} 时快速失败而不是退让成"没有端口"：{@code store=db}
         * 是业务<b>显式</b>写下来的选择，此时"占位无处可写"的结局与 Redis 一侧缺模板同样不可接受
         * （注解照旧拦截，但把失败推到第一次调用上等于让接线错误在生产的某个凌晨才现形）。
         * 与持久化侧 {@code MydddOutboxStoreAutoConfiguration#requireSingle} 同一条裁定。
         */
        @Bean
        @ConditionalOnMissingBean(IdempotentPort.class)
        @ConditionalOnProperty(prefix = "myddd.consistency.idempotent", name = "store", havingValue = "db")
        IdempotentPort mydddJdbcIdempotentStore(MydddConsistencyProperties properties,
                ObjectProvider<DataSource> dataSources) {
            String table = properties.getIdempotent().getDb().getTable();
            return new JdbcIdempotentStore(requireDataSource(dataSources, table), properties, table);
        }

        private static DataSource requireDataSource(ObjectProvider<DataSource> dataSources, String table) {
            DataSource candidate;
            try {
                candidate = dataSources.getIfAvailable();
            } catch (NoUniqueBeanDefinitionException ambiguous) {
                throw new FrameworkException("DataSource 有多个候选且没有 @Primary，幂等占位不知道该往哪个库写"
                        + "（表名 " + table + "）：请给目标 DataSource 标 @Primary，或改用 myddd.consistency.idempotent"
                        + ".store=redis（缺省，占位不进业务库），或自己提供一个 IdempotentPort", ambiguous);
            }
            if (candidate == null) {
                throw new FrameworkException("myddd.consistency.idempotent.store=db 需要容器里有一个 DataSource，"
                        + "当前没有，占位无处可写。出路三选一：① 配 spring.datasource.*（或 myddd-persistence-starter "
                        + "的 myddd.persistence.datasources.*）让数据源存在，并按 classpath:"
                        + "db/ddl-reference/V2__myddd_idempotent_record.sql 建表；② 改回 "
                        + "myddd.consistency.idempotent.store=redis（缺省值）；③ 自己提供一个 IdempotentPort，"
                        + "或关掉框架的这份实现：myddd.consistency.idempotent.enabled=false（关的是实现，"
                        + "@Idempotent 注解照旧被拦截）");
            }
            return candidate;
        }
    }
}

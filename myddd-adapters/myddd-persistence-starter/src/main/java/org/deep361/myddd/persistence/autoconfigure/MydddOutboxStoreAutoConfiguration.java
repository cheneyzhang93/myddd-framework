package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.domain.outbox.OutboxStore;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.outbox.JdbcOutboxStore;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Outbox 的 JDBC 存储装配（{@code myddd.persistence.outbox.*}）。
 *
 * <p><b>类名为什么带 {@code Store} 而不只是 {@code Outbox}</b>：domain-starter 已经有一个
 * {@code org.deep361.myddd.domain.autoconfigure.MydddOutboxAutoConfiguration}（装配投递器那一侧）。两个类
 * 简单名相同不会编译失败，但会让日志、异常栈与 {@code AutoConfiguration.imports} 里出现两个同名装配类，
 * 排查"我的 store 为什么没装上"时极易看错行。本类管的是<b>写侧</b>（存储实现），Task 21 的死信读侧另有装配类。
 *
 * <p><b>为什么要一个独立的装配类</b>：{@link MydddPersistenceAutoConfiguration} 的类级条件是
 * {@code @ConditionalOnClass(MybatisPlusInterceptor)}，而本 bean 只用到 {@code spring-jdbc} 与
 * {@code spring-tx}——MP 插件链与 Outbox 存储是两件事，混在一起会让"只用 JDBC 不用 MyBatis-Plus"的配法
 * 莫名其妙地丢存储。同一个 {@code myddd.persistence.enabled} 总开关照样管住两个类。
 *
 * <p><b>缺依赖时快速失败，不静默退让</b>：这里刻意<b>不</b>用 {@code @ConditionalOnBean(DataSource.class)}。
 * 开关是业务显式打开的（{@code enabled} 默认 false），此时"容器里没有数据源/事务管理器"意味着
 * 领域事件永远不会投递——一个静默的、看不出原因的丢消息路径。因此本类用 {@link ObjectProvider} 取
 * {@link DataSource}，取不到就抛 {@link FrameworkException} 指名出路（配 {@code spring.datasource.*}
 * 或 {@code myddd.persistence.datasources.*}，或关掉本开关）。
 *
 * <p><b>与 {@code @Primary} 的关系</b>：路由模式（Task 17）下框架只产出一个 {@code DataSource} bean——
 * 带 {@code @Primary} 的路由数据源，所以这里按类型注入拿到的就是它；业务若自己再声明多个数据源且无
 * {@code @Primary}，按类型解析会歧义，本类把它翻译成指名 {@code @Primary} 的失败消息，
 * 而不是随机挑一个库写 Outbox。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "myddd.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddPersistenceProperties.class)
public class MydddOutboxStoreAutoConfiguration {

    /**
     * {@link JdbcOutboxStore}：{@code myddd.persistence.outbox.enabled=true} 才装配。
     *
     * <p>{@code @ConditionalOnMissingBean(OutboxStore.class)} 而不是按 {@code JdbcOutboxStore} 查：业务若自己
     * 实现了 SPI（比如换一套认领策略），框架不该再产出一个抢注的存储；查接口才能覆盖"换了实现类"这种退路。
     */
    @Bean
    @ConditionalOnProperty(prefix = "myddd.persistence.outbox", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(OutboxStore.class)
    public JdbcOutboxStore mydddOutboxStore(ObjectProvider<DataSource> dataSources,
            ObjectProvider<PlatformTransactionManager> transactionManagers, MydddPersistenceProperties properties) {
        String table = properties.getOutbox().getTable();
        return new JdbcOutboxStore(requireSingle(dataSources, "DataSource", table),
                new TransactionTemplate(requireSingle(transactionManagers, "PlatformTransactionManager", table)),
                table);
    }

    /**
     * 按类型取唯一候选；没有则报"开关开了但依赖不在"，多于一个且无 {@code @Primary} 则报歧义。
     * 两条消息都指名本能力的键，避免业务去翻三方文档猜是谁在用这个 bean。
     */
    private static <T> T requireSingle(ObjectProvider<T> provider, String kind, String table) {
        T candidate;
        try {
            candidate = provider.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ambiguous) {
            throw new FrameworkException(kind + " 有多个候选且没有 @Primary，Outbox 存储不知道该往哪个库写"
                    + "（表名 " + table + "）：请给目标 " + kind + " 标 @Primary，"
                    + "或关闭 myddd.persistence.outbox.enabled 改为自己装配 OutboxStore 实现", ambiguous);
        }
        if (candidate == null) {
            throw new FrameworkException("myddd.persistence.outbox.enabled=true 需要容器里有一个 " + kind
                    + "，当前没有，Outbox 记录无处可写。出路二选一："
                    + "① 配 spring.datasource.* 或 myddd.persistence.datasources.* 让数据源存在，"
                    + "并由 Boot 的 " + DataSourceTransactionManager.class.getSimpleName() + " 自动装配出事务管理器；"
                    + "② 关闭 myddd.persistence.outbox.enabled（默认即 false），此时领域事件不会经 Outbox 投递。");
        }
        return candidate;
    }
}

package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.outbox.OutboxDeadLetterStore;
import org.deep361.myddd.persistence.outbox.JdbcOutboxDeadLetterStore;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Outbox 死信的读侧装配（{@code myddd.persistence.outbox.dead-letter.*}）。
 *
 * <p><b>为什么不塞进 {@link MydddOutboxStoreAutoConfiguration}</b>：写侧与读侧的必选依赖不同。写侧缺
 * {@code PlatformTransactionManager} 是真的不能工作（认领必须原子）；读侧的重放是一条单语句
 * {@code update}、查询只是两次只读 SELECT 想同快照，缺事务管理器时退化成自动提交也不会写错数据，只是
 * 翻页可能短暂不一致。合成一个类就只能按"两边都必需的交集"装配，或者在读侧的 bean 里为写侧的缺失陪绑。
 *
 * <p><b>同一个开关管两侧</b>：{@code myddd.persistence.outbox.enabled} 是"这张表在用"的声明，
 * 没有它就没有死信，读侧也就没有意义。因此本类的类级条件与写侧完全相同（外加 {@code myddd.persistence.enabled}
 * 总开关，由 {@link MydddOutboxStoreAutoConfiguration} 那条同名的类级条件一致的写法保证）。
 *
 * <p><b>依赖缺失仍快速失败</b>：理由与写侧同一条——开关是业务显式打开的，此时"没有数据源"的表现本该是
 * 启动失败，而不是"运维界面永远查空"。这里的消息换成读侧的后果，不复用写侧那条"Outbox 记录无处可写"，
 * 因为出路不同：读侧可以只查不投（业务自己实现 {@link OutboxDeadLetterStore} 换一套查询）。
 *
 * <p>本类是 {@code AutoConfiguration.imports} 的第 4 行。漏登记会被
 * {@code MydddPersistenceAutoConfigurationRegistrationTest} 的 R1 立刻抓到（Task 19 偏差 D-10 补的那道守卫）。
 */
@AutoConfiguration(after = MydddOutboxStoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "myddd.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddPersistenceProperties.class)
public class MydddOutboxDeadLetterAutoConfiguration {

    /**
     * {@link OutboxDeadLetterStore}：{@code myddd.persistence.outbox.enabled=true} 才装配。
     *
     * <p>{@code @ConditionalOnMissingBean} 查接口 {@link OutboxDeadLetterStore}：业务若自己实现了一套查询
     * （比如走 ES 或另一张归档表），框架不再产出抢注的那一个。
     */
    @Bean
    @ConditionalOnProperty(prefix = "myddd.persistence.outbox", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(OutboxDeadLetterStore.class)
    public JdbcOutboxDeadLetterStore mydddOutboxDeadLetterStore(ObjectProvider<DataSource> dataSources,
            ObjectProvider<PlatformTransactionManager> transactionManagers, MydddPersistenceProperties properties) {
        MydddPersistenceProperties.Outbox outbox = properties.getOutbox();
        String table = outbox.getTable();
        return new JdbcOutboxDeadLetterStore(requireSingle(dataSources, "DataSource", table),
                new TransactionTemplate(requireSingle(transactionManagers, "PlatformTransactionManager", table)),
                table, outbox.getDeadLetter().getQueryLimit());
    }

    /**
     * 按类型取唯一候选；没有则报"开关开了但依赖不在"，多于一个且无 {@code @Primary} 则报歧义。
     *
     * <p>与 {@link MydddOutboxStoreAutoConfiguration} 里的同名私有助手形状相同、<b>消息不同</b>：两处的后果与
     * 出路本来就不是一回事（写侧是"事件永不投递"，读侧是"死信查不到"）。为此抽一个公共类会把两条消息压成一条
     * 中性的，反而谁都看不懂——这里宁可留两份。
     */
    private static <T> T requireSingle(ObjectProvider<T> provider, String kind, String table) {
        T candidate;
        try {
            candidate = provider.getIfAvailable();
        } catch (NoUniqueBeanDefinitionException ambiguous) {
            throw new FrameworkException(kind + " 有多个候选且没有 @Primary，死信读侧不知道该查哪个库"
                    + "（表名 " + table + "）：请给目标 " + kind + " 标 @Primary，"
                    + "或关闭 myddd.persistence.outbox.enabled 改为自己装配 OutboxDeadLetterStore 实现", ambiguous);
        }
        if (candidate == null) {
            throw new FrameworkException("myddd.persistence.outbox.enabled=true 需要容器里有一个 " + kind
                    + "，当前没有，死信列表无从查起。出路二选一："
                    + "① 配 spring.datasource.* 或 myddd.persistence.datasources.* 让数据源存在；"
                    + "② 关闭 myddd.persistence.outbox.enabled（默认即 false），此时不装配死信读侧。");
        }
        return candidate;
    }
}

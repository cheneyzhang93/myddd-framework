package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.deep361.myddd.persistence.outbox.AggregateTypeUnwrapperBeanPostProcessor;
import org.deep361.myddd.persistence.mybatis.AuditMetaObjectHandler;
import org.deep361.myddd.persistence.mybatis.PrincipalProvider;
import org.springframework.beans.factory.ObjectProvider;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import java.time.Clock;

/**
 * MyBatis-Plus 插件链装配：框架按 {@code myddd.persistence.interceptor.*} 产出内置拦截器，并把容器里
 * <b>全部</b> {@link InnerInterceptor}（含业务自己声明的）按顺序装进 {@link MybatisPlusInterceptor}。
 *
 * <p><b>顺序声明是必需的，不是风格</b>：MP 的
 * {@code MybatisPlusInnerInterceptorAutoConfiguration} 带 {@code @ConditionalOnMissingBean}，而它自己是普通
 * {@code @Configuration}（无 before/after 元数据），Boot 在无提示时按类名字母序排——{@code com.baomidou} 排在
 * {@code org.deep361} 之前，因此不写 {@code before} 就是 MP 先产出 bean、我们整体退让，表现是"配置键全都不生效"。
 * 该竞争由测试
 * {@code 链子的拥有者是框架的装配类而不是mybatisplus自己的} 以 bean 名的形式钉住。
 *
 * <p><b>与数据源无关</b>：本装配类不创建 {@code DataSource}、不检查它的存在性（那是 MP 的
 * {@code MybatisPlusAutoConfiguration} 用 {@code @ConditionalOnSingleCandidate} 管的事），所以只引本模块而不配
 * 数据源的应用照样能装配出这条链，直到 MP 建 {@code SqlSessionFactory} 时才按 MP 的规则失败。
 */
@AutoConfiguration(before = MybatisPlusInnerInterceptorAutoConfiguration.class)
@ConditionalOnClass(MybatisPlusInterceptor.class)
@ConditionalOnProperty(prefix = "myddd.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MydddPersistenceProperties.class)
public class MydddPersistenceAutoConfiguration {

    /** 框架三个拦截器的链内位置：留 10 的间隔，业务想插在中间用 11、12……（见设计要点 4）。 */
    static final int PAGINATION_ORDER = 10;
    static final int OPTIMISTIC_LOCK_ORDER = 20;
    static final int BLOCK_ATTACK_ORDER = 30;

    @Bean
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mydddMybatisPlusInterceptor(ObjectProvider<InnerInterceptor> innerInterceptors) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.setInterceptors(innerInterceptors.orderedStream().toList());
        return interceptor;
    }

    /**
     * 分页拦截器。
     *
     * <p>{@code dbType} 缺省为 {@code null}，也就是<b>不预设方言</b>：字节码实测 MyBatis-Plus 的
     * {@code PaginationInnerInterceptor.findIDialect} 在 {@code dbType == null} 时按当前连接认库
     * （{@code DialectFactory.getDialect(JdbcUtils.getDbType(executor))}），配了值才固定用它；而
     * {@code (DbType)} 那个构造器对 null 没有任何校验。缺省写死某个库，等于替用户宣布这套脚手架只支持那一个库。
     *
     * <p>{@code max-limit=0} 时<b>不调</b> {@code setMaxLimit}：MP 的 {@code maxLimit} 字段默认 {@code null}
     * 才是"不限"，传 0 会变成"每页 0 条"并静默返回空列表（设计要点 5）。
     */
    @Bean
    @Order(PAGINATION_ORDER)
    @ConditionalOnProperty(prefix = "myddd.persistence.interceptor.pagination", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public PaginationInnerInterceptor mydddPaginationInnerInterceptor(MydddPersistenceProperties properties) {
        MydddPersistenceProperties.Pagination config = properties.getInterceptor().getPagination();
        PaginationInnerInterceptor interceptor = new PaginationInnerInterceptor(config.getDbType());
        if (config.getMaxLimit() > 0) {
            interceptor.setMaxLimit(config.getMaxLimit());
        }
        return interceptor;
    }

    /** 乐观锁拦截器：只对标了 {@code @Version} 的实体起作用。 */
    @Bean
    @Order(OPTIMISTIC_LOCK_ORDER)
    @ConditionalOnProperty(prefix = "myddd.persistence.interceptor.optimistic-lock", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public OptimisticLockerInnerInterceptor mydddOptimisticLockerInnerInterceptor() {
        return new OptimisticLockerInnerInterceptor();
    }

    /** 防全表更新/删除：没有 where 的 update/delete 直接失败，这是脚手架默认必须开着的一道保险。 */
    @Bean
    @Order(BLOCK_ATTACK_ORDER)
    @ConditionalOnProperty(prefix = "myddd.persistence.interceptor.block-attack", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public BlockAttackInnerInterceptor mydddBlockAttackInnerInterceptor() {
        return new BlockAttackInnerInterceptor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.persistence.audit", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean
    public PrincipalProvider mydddAuditPrincipalProvider() {
        return PrincipalProvider.none();
    }

    @Bean
    @ConditionalOnProperty(prefix = "myddd.persistence.audit", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    public AuditMetaObjectHandler mydddAuditMetaObjectHandler(PrincipalProvider principalProvider,
            MydddPersistenceProperties properties) {
        return new AuditMetaObjectHandler(principalProvider, Clock.systemUTC(),
                properties.getAudit().getUnknownPrincipal());
    }


    /**
     * 代理聚合根的坐标修正：把容器里的领域事件发布器换成 {@link AggregateTypeUnwrapper}（解包语义见该类）。
     *
     * <p><b>必须是 {@code static} 方法</b>：{@code BeanPostProcessor} 类型的 bean 在容器早期就要实例化，
     * 非静态的 {@code @Bean} 方法会连带强制实例化它的声明类，从而让本类的 {@code @AutoConfiguration} 条件求值与
     * {@code @EnableConfigurationProperties} 的装配次序变形（Spring 官方的 BPP 声明纪律）。本方法不读实例状态，
     * 静态化零成本。
     *
     * <p><b>不加 {@code @ConditionalOnMissingBean}</b>：条件注解查的是"容器里有没有该类型的 bean"，而
     * BPP 自身的注册时机早于大多数 bean 的创建，这个条件在 BPP 上属于 Boot 文档明确警告过的"求值结果不可靠"
     * 一类。业务若要自己的解包方案，正确做法是让自己的发布器 bean 已经带正确坐标（装饰器对
     * {@link AggregateTypeUnwrapper} 实例不重复包装，见 BPP 的判定），而不是顶掉这个 BPP。
     *
     * <p><b>无新增配置键</b>：R1 是正确性修复，不提供开关。要整体关掉装饰，唯一路径是不引入
     * {@code myddd-persistence-starter}（本装配类由 {@code @ConditionalOnClass(MybatisPlusInterceptor)} 与
     * {@code myddd.persistence.enabled} 把关，Task 13 要点 7 已裁定这两道的语义）。
     */
    @Bean
    static AggregateTypeUnwrapperBeanPostProcessor aggregateTypeUnwrapperBeanPostProcessor() {
        return new AggregateTypeUnwrapperBeanPostProcessor();
    }
}

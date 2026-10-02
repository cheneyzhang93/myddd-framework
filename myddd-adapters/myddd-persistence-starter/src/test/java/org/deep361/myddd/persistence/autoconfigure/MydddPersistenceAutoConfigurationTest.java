package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusInnerInterceptorAutoConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拦截器链装配语义：链里有什么、按什么顺序、谁拥有 {@code MybatisPlusInterceptor} 这个 bean。
 *
 * <p>runner 里刻意同时放入 MP 真实的 {@link MybatisPlusInnerInterceptorAutoConfiguration}：它带
 * {@code @ConditionalOnMissingBean}，是"框架的装配有没有赢"的直接对手。把它放进来看 bean 名，是本文件
 * 唯一一条对三方装配类有依赖的用例——去掉它，测试 2 就退化成"我们自己没被顶掉自己"的空断言。
 */
class MydddPersistenceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddPersistenceAutoConfiguration.class,
                    MybatisPlusInnerInterceptorAutoConfiguration.class));

    /** 链子的可比较形态：按类型简单名读，失败信息里能直接看出顺序。 */
    private static List<String> chainOf(ApplicationContext context) {
        return context.getBean(MybatisPlusInterceptor.class).getInterceptors().stream()
                .map(inner -> inner.getClass().getSimpleName())
                .toList();
    }

    @Test
    void should_assemble_three_interceptors_in_pagination_lock_block_order() {
        this.runner.run(context -> assertThat(chainOf(context)).containsExactly(
                PaginationInnerInterceptor.class.getSimpleName(),
                OptimisticLockerInnerInterceptor.class.getSimpleName(),
                BlockAttackInnerInterceptor.class.getSimpleName()));
    }

    @Test
    void should_own_the_mbatisplus_interceptor_bean_ourselves() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusInterceptor.class);
            assertThat(context.getBeanNamesForType(MybatisPlusInterceptor.class))
                    .containsExactly("mydddMybatisPlusInterceptor");
        });
    }

    @Test
    void should_keep_only_lock_and_block_when_pagination_off() {
        this.runner.withPropertyValues("myddd.persistence.interceptor.pagination.enabled=false")
                .run(context -> assertThat(chainOf(context)).containsExactly(
                        OptimisticLockerInnerInterceptor.class.getSimpleName(),
                        BlockAttackInnerInterceptor.class.getSimpleName()));
    }

    @Test
    void should_keep_only_pagination_and_block_when_lock_off() {
        this.runner.withPropertyValues("myddd.persistence.interceptor.optimistic-lock.enabled=false")
                .run(context -> assertThat(chainOf(context)).containsExactly(
                        PaginationInnerInterceptor.class.getSimpleName(),
                        BlockAttackInnerInterceptor.class.getSimpleName()));
    }

    @Test
    void should_keep_only_pagination_and_lock_when_block_off() {
        this.runner.withPropertyValues("myddd.persistence.interceptor.block-attack.enabled=false")
                .run(context -> assertThat(chainOf(context)).containsExactly(
                        PaginationInnerInterceptor.class.getSimpleName(),
                        OptimisticLockerInnerInterceptor.class.getSimpleName()));
    }
    @Test
    void should_keep_business_interceptor_ordered_after_framework_ones() {
        this.runner.withBean(BusinessInnerInterceptor.class, BusinessInnerInterceptor::new)
                .run(context -> assertThat(chainOf(context)).containsExactly(
                        PaginationInnerInterceptor.class.getSimpleName(),
                        OptimisticLockerInnerInterceptor.class.getSimpleName(),
                        BlockAttackInnerInterceptor.class.getSimpleName(),
                        BusinessInnerInterceptor.class.getSimpleName()));
    }

    @Test
    void should_let_business_interceptor_with_order_zero_join_first() {
        this.runner.withBean(FirstInChainInnerInterceptor.class, FirstInChainInnerInterceptor::new)
                .run(context -> assertThat(chainOf(context)).containsExactly(
                        FirstInChainInnerInterceptor.class.getSimpleName(),
                        PaginationInnerInterceptor.class.getSimpleName(),
                        OptimisticLockerInnerInterceptor.class.getSimpleName(),
                        BlockAttackInnerInterceptor.class.getSimpleName()));
    }

    @Test
    void should_pass_max_limit_verbatim_and_omit_it_when_zero() {
        this.runner.withPropertyValues("myddd.persistence.interceptor.pagination.max-limit=0")
                .run(context -> assertThat(context.getBean(PaginationInnerInterceptor.class).getMaxLimit()).isNull());

        this.runner.withPropertyValues("myddd.persistence.interceptor.pagination.max-limit=50")
                .run(context -> assertThat(context.getBean(PaginationInnerInterceptor.class).getMaxLimit())
                        .isEqualTo(50L));
    }

    @Test
    void should_assemble_neither_chain_nor_interceptors_when_switch_off() {
        this.runner.withPropertyValues("myddd.persistence.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            // MP 的默认装配要 @ConditionalOnBean(InnerInterceptor)：我们没有产出任何拦截器，它也就不会补位
            assertThat(context).doesNotHaveBean(MybatisPlusInterceptor.class);
            assertThat(context.getBeansOfType(InnerInterceptor.class)).isEmpty();
        });
    }

    @Test
    void should_fail_startup_when_db_type_is_not_a_known_enum() {
        // 绑定层失败即可，不必我们自己校验（设计要点 6）；这条用例钉的是"配错不会静默按默认值跑下去"
        this.runner.withPropertyValues("myddd.persistence.interceptor.pagination.db-type=MYSQL_PLUS")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_back_off_on_the_chain_yet_still_offer_three_interceptors() {
        MybatisPlusInterceptor business = new MybatisPlusInterceptor();
        business.addInnerInterceptor(new BusinessInnerInterceptor());

        this.runner.withBean("businessInterceptor", MybatisPlusInterceptor.class, () -> business).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MybatisPlusInterceptor.class);
            assertThat(context.getBean(MybatisPlusInterceptor.class)).isSameAs(business);
            // 让位只发生在"链子"上：三个框架拦截器 bean 仍然产出。业务自己 addInnerInterceptor 的那个
            // 不是容器 bean（它活在 business 的链子里），所以这里恰好是 3 个（要点 1 的取舍边界）
            assertThat(context.getBeansOfType(InnerInterceptor.class)).hasSize(3)
                    .containsOnlyKeys("mydddPaginationInnerInterceptor", "mydddOptimisticLockerInnerInterceptor",
                            "mydddBlockAttackInnerInterceptor");
        });
    }

    /** 无 {@code @Order}：{@code orderedStream()} 按最低优先级处理，故排在框架之后（设计要点 4）。 */
    static class BusinessInnerInterceptor implements InnerInterceptor {
    }

    /** 显式 {@code @Order(0)}：证明业务可以前插，框架的固定顺序不是不可逾越的。 */
    @Order(0)
    static class FirstInChainInnerInterceptor implements InnerInterceptor {
    }
}

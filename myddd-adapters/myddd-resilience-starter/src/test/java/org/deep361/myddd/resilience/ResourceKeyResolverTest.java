package org.deep361.myddd.resilience;

import java.lang.reflect.Method;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.CircuitBreaker;
import org.deep361.myddd.kernel.port.resilience.RateLimit;
import org.deep361.myddd.resiliencefixture.GuardedService;
import org.deep361.myddd.resiliencefixture.RecordingRateLimitPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 资源键单点（{@code RS1}–{@code RS10}）：反射面上的解析与求值，不建上下文也不建代理。
 *
 * <p>"真代理上资源名长什么样"由 {@code RL13}/{@code RL15}/{@code RL16} 钉（那里才有 CGLIB 与切点门），
 * 本类钉的是解析这一层的四条判据：缺省名的主人在谁、两级注解谁赢、SpEL 的三条失败诊断说什么、
 * 空白求值结果该由谁拒绝。
 */
class ResourceKeyResolverTest {

    private static final ResourceKeyResolver RATE_LIMIT = new ResourceKeyResolver("RateLimit");
    private static final ResourceKeyResolver CIRCUIT_BREAKER = new ResourceKeyResolver("CircuitBreaker");

    /** 类型级 + 方法级并存的那一格，以及一个"自己没标注解"的子类（{@code RS7} 用）。 */
    @RateLimit(key = "classLevel", timeout = 8)
    static class ClassLevelFixture {

        String plainMethod() {
            return "ran";
        }

        @RateLimit(key = "methodWins")
        String bothLevels() {
            return "ran";
        }
    }

    static class ChildOfClassLevel extends ClassLevelFixture {
    }

    /** 注解与实现都写在父类：{@code RS3}（缺省名的主人）与 {@code RS6}（跨方法读注解）的用武之地。 */
    static class DeclaringBase {

        @RateLimit
        String inheritedDefault() {
            return "ran";
        }

        @RateLimit(key = "parentMethod")
        String inheritedDeclared() {
            return "ran";
        }
    }

    static class InheritingChild extends DeclaringBase {
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) {
        return org.springframework.util.ReflectionUtils.findMethod(owner, name, types);
    }

    /** {@code RS1}：{@code key} 留空时缺省名是 {@code 简单类名.方法名}，逐字含点号、不含 {@code #}。 */
    @Test
    void should_use_the_simple_class_and_method_name_when_the_key_is_blank() {
        Method guarded = method(GuardedService.class, "defaultKey");
        assertThat(RATE_LIMIT.resourceText("", guarded, new Object[] {"x"}, new GuardedService(),
                GuardedService.class)).isEqualTo("GuardedService.defaultKey");
        // 两个 resolver 各算一次：缺省名与注解名无关，但这条只有在"熔断侧也算了一遍"时才成立。
        assertThat(CIRCUIT_BREAKER.resourceText("", guarded, new Object[] {"x"}, new GuardedService(),
                GuardedService.class)).isEqualTo("GuardedService.defaultKey");
    }

    /** {@code RS2}：纯空白 {@code key} 与留空同义，都走缺省名而不是交给 SpEL。 */
    @Test
    void should_treat_a_whitespace_key_exactly_like_a_blank_one() {
        Method guarded = method(GuardedService.class, "defaultKey");
        assertThat(RATE_LIMIT.resourceText(" ", guarded, new Object[] {"x"}, new GuardedService(),
                GuardedService.class)).isEqualTo("GuardedService.defaultKey");
    }

    /**
     * {@code RS3}：缺省名的主人是<b>被装配的那个 bean</b>（目标类），不是字节码上的声明类。
     *
     * <p>"代理类名不渗进资源名"那一半不在这里——本类没有代理，那一半由 {@code RL13} 在真 CGLIB 上钉。
     */
    @Test
    void should_take_the_default_name_owner_from_the_target_class_not_the_declaring_class() {
        Method inherited = method(DeclaringBase.class, "inheritedDefault");
        assertThat(inherited.getDeclaringClass()).isEqualTo(DeclaringBase.class);
        assertThat(RATE_LIMIT.resourceText("", inherited, new Object[0], new InheritingChild(),
                InheritingChild.class)).isEqualTo("InheritingChild.inheritedDefault");
    }

    /** {@code RS4}：方法级压过类型级，且压的是<b>整份</b>注解（探针 U2 的读数固化）。 */
    @Test
    void should_prefer_the_method_level_declaration_over_the_class_level_one() {
        Method bothLevels = method(ClassLevelFixture.class, "bothLevels");
        RateLimit declared = RATE_LIMIT.declared(bothLevels, ClassLevelFixture.class, RateLimit.class);
        assertThat(declared.key()).isEqualTo("methodWins");
        // 整份让位而不是逐字段合并：方法级没写 timeout，读到的就是注解缺省 0 而不是类级的 8。
        assertThat(declared.timeout()).isZero();
        // 类型过滤是真的在过滤：同一格上问 @CircuitBreaker 只能得到 null（拿错注解类型=拿错一套阈值）。
        assertThat(CIRCUIT_BREAKER.declared(bothLevels, ClassLevelFixture.class, CircuitBreaker.class))
                .isNull();
    }

    /** {@code RS5}：方法没有声明时退回类型级那一份，原文读得出。 */
    @Test
    void should_fall_back_to_the_class_level_declaration_when_the_method_has_none() {
        RateLimit declared = RATE_LIMIT.declared(method(ClassLevelFixture.class, "plainMethod"),
                ClassLevelFixture.class, RateLimit.class);
        assertThat(declared.key()).isEqualTo("classLevel");
        assertThat(declared.timeout()).isEqualTo(8);
    }

    /** {@code RS6}：注解声明在父类方法上，折算到子类调用时仍读得到。 */
    @Test
    void should_read_a_method_level_declaration_declared_on_a_super_class() {
        assertThat(RATE_LIMIT.declared(method(DeclaringBase.class, "inheritedDeclared"),
                InheritingChild.class, RateLimit.class).key()).isEqualTo("parentMethod");
    }

    /** {@code RS7}：类型级注解声明在父类、调用发生在子类 → 拿父类那一份（探针 U7 的读数）。 */
    @Test
    void should_read_a_class_level_declaration_declared_on_a_super_class() {
        assertThat(RATE_LIMIT.declared(method(ChildOfClassLevel.class, "plainMethod"),
                ChildOfClassLevel.class, RateLimit.class).key()).isEqualTo("classLevel");
    }

    /**
     * {@code RS8}：每次调用新建求值上下文——缓存的是语法树，实参泄漏出去就是跨调用串数据。
     *
     * <p>第三次传 {@code null} 实参：它必须落到"求值得到 null"那句诊断上，而不是复用第一次的 {@code A}。
     */
    @Test
    void should_build_a_fresh_evaluation_context_per_call_so_no_argument_leaks() {
        Method byArgument = method(GuardedService.class, "byArgument", String.class);
        GuardedService target = new GuardedService();
        assertThat(RATE_LIMIT.resourceText("#orderNo", byArgument, new Object[] {"A"}, target,
                GuardedService.class)).isEqualTo("A");
        assertThat(RATE_LIMIT.resourceText("#orderNo", byArgument, new Object[] {"B"}, target,
                GuardedService.class)).isEqualTo("B");
        assertThatThrownBy(() -> RATE_LIMIT.resourceText("#orderNo", byArgument, new Object[] {null}, target,
                GuardedService.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("null");
    }

    /**
     * {@code RS9}：空白求值结果原样放行，拒绝它的是<b>端口</b>。
     *
     * <p>断的是异常类型：{@code IllegalArgumentException} 出自替身端口那句实现契约检查，切面自己拒会变成
     * {@code FrameworkException}——同一件事两处各说一半正是本框架要避免的形状。
     */
    @Test
    void should_pass_a_blank_evaluation_result_to_the_port_instead_of_rejecting_it_here() {
        Method literal = method(GuardedService.class, "literalSpel");
        String resolved = RATE_LIMIT.resourceText("''", literal, new Object[0], new GuardedService(),
                GuardedService.class);
        assertThat(resolved).isEmpty();
        RecordingRateLimitPort port = new RecordingRateLimitPort();
        assertThrows(IllegalArgumentException.class, () -> port.tryAcquire(resolved, 1));
    }

    /**
     * {@code RS10}：三条失败诊断各含注解名与 {@code 类全名#方法名}，且两个 resolver 各说各自那个注解名。
     *
     * <p>把 {@code @CircuitBreaker} 的错报成 {@code @RateLimit} 是本类最容易犯又最难发现的文案错——
     * 注解名是构造参数，不是从注解类型推出来的，所以这一条必须有。
     */
    @Test
    void should_name_the_annotation_and_the_method_in_all_three_failure_diagnostics() {
        Method byArgument = method(GuardedService.class, "byArgument", String.class);
        GuardedService target = new GuardedService();
        String where = "org.deep361.myddd.resiliencefixture.GuardedService#byArgument";

        FrameworkException parse = assertThrows(FrameworkException.class,
                () -> RATE_LIMIT.resourceText("#{", byArgument, new Object[] {"A"}, target,
                        GuardedService.class));
        assertThat(parse).hasMessageContaining("@RateLimit").hasMessageContaining(where);

        FrameworkException evaluation = assertThrows(FrameworkException.class,
                () -> RATE_LIMIT.resourceText("#noSuchProperty", byArgument, new Object[] {"A"}, target,
                        GuardedService.class));
        assertThat(evaluation).hasMessageContaining("@RateLimit").hasMessageContaining(where);

        FrameworkException nullish = assertThrows(FrameworkException.class,
                () -> CIRCUIT_BREAKER.resourceText("#orderNo", byArgument, new Object[] {null}, target,
                        GuardedService.class));
        assertThat(nullish).hasMessageContaining("@CircuitBreaker").hasMessageContaining(where);
        // 三个 resolver 实例只持一份注解名：熔断那次的诊断里不许出现 @RateLimit。
        assertThat(nullish).hasMessageNotContaining("@RateLimit");
    }
}

package org.deep361.myddd.auth.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Proxy;
import java.util.List;

import org.deep361.myddd.authfixture.AuthHarness;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.RequiresPermission;
import org.deep361.myddd.resilience.RateLimitAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code AT} 组：{@link PermissionCheckAspect} 的执行侧。
 *
 * <p>一律走<b>真</b> Spring 代理上下文（{@link AuthHarness}）：CGLIB 是 Boot 缺省形态、JDK 接口代理
 * 是业务会走到的另一形态，切点门开不开是容器侧的事实，mock 装配证不了。判据读
 * {@code FakeAuthPort} 的调用计数与顺序，以及拒绝异常的身份（401/403）与注解文案的逐字形状。
 */
class PermissionCheckAspectTest {

    private final AuthHarness harness = new AuthHarness();

    @AfterEach
    void closeContexts() {
        this.harness.closeAll();
    }

    /** 两种代理形态共用的业务接口（JDK 形态按接口取 bean）。 */
    public interface SecuredApi {

        String both();

        String anyOf();

        String single();

        String carriedForbidden();

        String carriedUnauthorized();
    }

    public static class SecuredService implements SecuredApi {

        @Override
        @RequiresPermission({ "read", "write" })
        public String both() {
            return "both";
        }

        @Override
        @RequiresPermission(value = { "quick", "slow" }, logical = RequiresPermission.Logical.ANY_OF)
        public String anyOf() {
            return "anyOf";
        }

        @Override
        @RequiresPermission("single:ok")
        public String single() {
            return "single";
        }

        @Override
        @RequiresPermission(value = "finance:report", message = "  请先完成实名认证，再查看这张报表  ")
        public String carriedForbidden() {
            return "carriedForbidden";
        }

        @Override
        @RequiresPermission(value = "finance:report", message = "登录已过期，请重新登录")
        public String carriedUnauthorized() {
            return "carriedUnauthorized";
        }
    }

    @RequiresPermission("class:read")
    public static class ClassAnnotatedService {

        public String plain() {
            return "plain";
        }
    }

    @RequiresPermission("a")
    public static class MixedLevelService {

        @RequiresPermission("b")
        public String bothWays() {
            return "mixed";
        }
    }

    /** 组合注解：业务写法只出现元注解，方法级与类级各钉一格（{@code AT9}）。 */
    @RequiresPermission(value = "admin:read", message = "该报表需要管理员只读角色")
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.METHOD, ElementType.TYPE })
    public @interface AdminRead {
    }

    public static class ComposedMethodService {

        @AdminRead
        public String report() {
            return "method";
        }
    }

    @AdminRead
    public static class ComposedClassService {

        public String report() {
            return "class";
        }
    }

    public static class EmptyDeclarationService {

        @RequiresPermission({})
        public String bad() {
            return "bad";
        }
    }

    public static class MethodAnnotatedParent {

        @RequiresPermission("parent:read")
        public String marked() {
            return "parent";
        }
    }

    public static class ChildInherits extends MethodAnnotatedParent {

        public String own() {
            return "own";
        }
    }

    public static class ChildOverrides extends MethodAnnotatedParent {

        @Override
        public String marked() {
            return "child";
        }
    }

    public interface IfaceMethodAnnotated {

        @RequiresPermission("iface:method")
        String run();
    }

    public static class IfaceMethodAnnotatedImpl implements IfaceMethodAnnotated {

        @Override
        public String run() {
            return "ran";
        }
    }

    @RequiresPermission("iface:type")
    public interface IfaceTypeAnnotated {

        String run();
    }

    public static class IfaceTypeAnnotatedImpl implements IfaceTypeAnnotated {

        @Override
        public String run() {
            return "ran";
        }
    }

    /**
     * {@code AT1}：方法级声明在两种代理形态下都放行，且权限按声明顺序逐一问端口。
     * CGLIB 形态钉代理类名含 {@code $$SpringCGLIB$$}，JDK 形态钉 {@code Proxy.isProxyClass}——
     * 两种形态若哪天退化成同一种，这两句会先炸。
     */
    @Test
    void should_proceed_and_ask_permissions_in_declaration_order_under_both_proxy_flavors() {
        this.harness.port.grant("read", "write");
        this.harness.port.loggedInAs("u1");
        AnnotationConfigApplicationContext cglib = this.harness.cglibContext(SecuredService.class);
        SecuredApi viaCglib = cglib.getBean(SecuredApi.class);
        assertThat(viaCglib.both()).isEqualTo("both");
        assertThat(viaCglib.getClass().getName()).contains("$$SpringCGLIB$$");
        assertThat(this.harness.port.permissionCalls).containsExactly("read", "write");
        this.harness.port.permissionCalls.clear();
        AnnotationConfigApplicationContext jdk = this.harness.jdkContext(SecuredService.class);
        SecuredApi viaJdk = jdk.getBean(SecuredApi.class);
        assertThat(Proxy.isProxyClass(viaJdk.getClass())).as("JDK 形态下 bean 应是 java.lang.reflect.Proxy").isTrue();
        assertThat(viaJdk.both()).isEqualTo("both");
        assertThat(this.harness.port.permissionCalls).containsExactly("read", "write");
    }

    /**
     * {@code AT2}：ALL_OF 缺一项 ⇒ 403，文案是 {@code FORBIDDEN} 的统一缺省文案，一个字节都不改。
     * 两种代理形态各跑一遍。
     */
    @Test
    void should_refuse_with_forbidden_default_text_under_both_proxy_flavors() {
        this.harness.port.grant("read");
        this.harness.port.loggedInAs("u1");
        List<AnnotationConfigApplicationContext> contexts = List.of(this.harness.cglibContext(SecuredService.class),
                this.harness.jdkContext(SecuredService.class));
        for (AnnotationConfigApplicationContext context : contexts) {
            BizException refused = assertThrows(BizException.class, () -> context.getBean(SecuredApi.class).both());
            assertThat(refused.getErrorCode()).isSameAs(CommonErrorCode.FORBIDDEN);
            assertThat(refused.getMessage()).isEqualTo("无权访问该资源");
        }
    }

    /** {@code AT3}：ANY_OF 命中第一项即短路——第二项一次都不该被问。 */
    @Test
    void should_short_circuit_the_any_of_combination_on_the_first_hit() {
        this.harness.port.grant("quick");
        this.harness.port.loggedInAs("u1");
        SecuredApi api = this.harness.cglibContext(SecuredService.class).getBean(SecuredApi.class);
        assertThat(api.anyOf()).isEqualTo("anyOf");
        assertThat(this.harness.port.permissionCalls).containsExactly("quick");
    }

    /**
     * {@code AT4}：未登录 ⇒ 401 而不是 403，且端口一次都没被问权限——
     * 分流只认 {@code currentUser()}，这一条顺序就是"客户端会不会跳登录页"的分界。
     */
    @Test
    void should_refuse_with_unauthorized_and_never_ask_permissions_when_nobody_is_logged_in() {
        SecuredApi api = this.harness.cglibContext(SecuredService.class).getBean(SecuredApi.class);
        BizException refused = assertThrows(BizException.class, api::both);
        assertThat(refused.getErrorCode()).isSameAs(CommonErrorCode.UNAUTHORIZED);
        assertThat(refused.getMessage()).isEqualTo("未认证或登录已过期");
        assertThat(this.harness.port.permissionCalls).isEmpty();
    }

    /** {@code AT5}：登进来了但没被授权 ⇒ 403（不是 401），权限被问过恰好一次。 */
    @Test
    void should_refuse_with_forbidden_not_unauthorized_when_the_user_is_present_but_ungranted() {
        this.harness.port.loggedInAs("u1");
        SecuredApi api = this.harness.cglibContext(SecuredService.class).getBean(SecuredApi.class);
        BizException refused = assertThrows(BizException.class, api::single);
        assertThat(refused.getErrorCode()).isSameAs(CommonErrorCode.FORBIDDEN);
        assertThat(this.harness.port.permissionCalls).containsExactly("single:ok");
    }

    /**
     * {@code AT6}：注解自带文案在 401 与 403 两条分支都逐字进 {@code getMessage()}——含首尾空格、
     * 不做渲染、不加包装；错误身份与参数位仍归错误码（R12"只换文案、不换身份"）。
     */
    @Test
    void should_carry_the_annotation_message_verbatim_in_both_the_401_and_the_403_branch() {
        this.harness.port.loggedInAs("u1");
        SecuredApi api = this.harness.cglibContext(SecuredService.class).getBean(SecuredApi.class);
        BizException forbidden = assertThrows(BizException.class, api::carriedForbidden);
        assertThat(forbidden.getMessage()).isEqualTo("  请先完成实名认证，再查看这张报表  ");
        assertThat(forbidden.getErrorCode()).isSameAs(CommonErrorCode.FORBIDDEN);
        assertThat(forbidden.getMessageArgs()).isEmpty();
        this.harness.port.loggedOut();
        BizException unauthorized = assertThrows(BizException.class, api::carriedUnauthorized);
        assertThat(unauthorized.getMessage()).isEqualTo("登录已过期，请重新登录");
        assertThat(unauthorized.getErrorCode()).isSameAs(CommonErrorCode.UNAUTHORIZED);
        assertThat(unauthorized.getMessageArgs()).isEmpty();
    }

    /** {@code AT7}：只有类级声明的方法照样被拦（{@code @within} 那一格），声明顺序只剩类上那一条。 */
    @Test
    void should_intercept_methods_of_a_class_level_declaration() {
        this.harness.port.grant("class:read");
        this.harness.port.loggedInAs("u1");
        ClassAnnotatedService service =
                this.harness.cglibContext(ClassAnnotatedService.class).getBean(ClassAnnotatedService.class);
        assertThat(service.plain()).isEqualTo("plain");
        assertThat(this.harness.port.permissionCalls).containsExactly("class:read");
    }

    /** {@code AT8}：方法级与类级同时在，方法级赢——类上的 "a" 一次都不该被问。 */
    @Test
    void should_prefer_the_method_level_declaration_over_the_class_level_one() {
        this.harness.port.grant("b");
        this.harness.port.loggedInAs("u1");
        MixedLevelService service = this.harness.cglibContext(MixedLevelService.class).getBean(MixedLevelService.class);
        assertThat(service.bothWays()).isEqualTo("mixed");
        assertThat(this.harness.port.permissionCalls).containsExactly("b");
    }

    /**
     * {@code AT9}：组合注解<b>不</b>被拦——切点是 AspectJ 表达式，它不认元注解（一手实测，见下）。
     * 这一格由启动期校验器报错（{@code RS11}），不在这里兜。
     *
     * <p><b>原断言是错的，公开撤回</b>：这一条原本按计划 48f 写成"方法级与类级各拦一次、端口被问
     * {@code admin:read} 两回"。实测把它推翻了：真代理四格（探针 {@code ProbeAspectjMetaTest}，
     * {@code AspectJProxyFactory} + 同一个 {@code @Around} 表达式）读到的结果是
     * 方法级组合注解<b>不触发</b>、类级组合注解<b>不触发</b>、直接标注<b>触发</b>、无标注不触发；
     * 而 {@code MergedAnnotations.from(element, DIRECT).isPresent(RequiresPermission)} 对前两者给
     * {@code true}、{@code element.isAnnotationPresent(...)} 给 {@code false}——"解析读得到、切点门不开"
     * 的悬空形态确实存在。
     *
     * <p><b>为什么把切点换成 {@code AnnotationMatchingPointcut} 不成立</b>：它对组合注解开门（实测
     * {@code matches=true}），但 {@code checkInherited=true} 同时会给接口侧那一格开门，而
     * {@code AT12}/{@code AT13} 钉死的恰恰是"被覆盖的父类侧与接口侧都不拦"（内核 javadoc：本注解未标
     * {@code @Inherited}）。两条语义不能拿一条去换另一条 ⇒ 门维持现状，洞由启动期报错堵死，
     * 判据与措辞见 {@link PermissionDeclarationValidator}。
     */
    @Test
    void should_not_intercept_a_composed_annotation_because_the_aspectj_pointcut_ignores_meta_annotations() {
        this.harness.port.loggedInAs("u1");
        AnnotationConfigApplicationContext context =
                this.harness.cglibContext(ComposedMethodService.class, ComposedClassService.class);
        assertThat(context.getBean(ComposedMethodService.class).report()).isEqualTo("method");
        assertThat(context.getBean(ComposedClassService.class).report()).isEqualTo("class");
        assertThat(this.harness.port.permissionCalls).isEmpty();
    }

    /**
     * {@code AT10}：坏声明（空数组）先于端口被解析——构造即校验发生在 {@code require()} 之前，
     * 容器里有没有 provider 都不改变"注解写错"这件事（AM 锚：消息指名类与方法）。
     */
    @Test
    void should_reject_an_empty_declaration_before_the_port_is_ever_queried() {
        EmptyDeclarationService service =
                this.harness.cglibContext(EmptyDeclarationService.class).getBean(EmptyDeclarationService.class);
        assertThatThrownBy(service::bad)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("EmptyDeclarationService")
                .hasMessageContaining("bad")
                .hasMessageContaining("没写任何权限");
        assertThat(this.harness.port.permissionCalls).isEmpty();
    }

    /**
     * {@code AT11}：注解只在父类方法上、子类<b>没覆盖</b>——执行的就是父类声明的那一格，
     * 切点门开、三级链的第三级（声明类）读到（探针 48e 的开格形态）。
     */
    @Test
    void should_intercept_an_inherited_but_not_overridden_method_declared_on_the_parent() {
        this.harness.port.grant("parent:read");
        this.harness.port.loggedInAs("u1");
        ChildInherits child = this.harness.cglibContext(ChildInherits.class).getBean(ChildInherits.class);
        assertThat(child.marked()).isEqualTo("parent");
        assertThat(this.harness.port.permissionCalls).containsExactly("parent:read");
    }

    /**
     * {@code AT12}：子类覆盖了父类被声明的那一格——门根本不开（探针 48e 的关格形态），
     * 这一次调用与"没写注解"完全一样：端口零调用、方法原样执行。这一格由启动期校验器负责报错（RS6）。
     */
    @Test
    void should_stay_out_entirely_when_the_child_overrode_the_declared_method() {
        this.harness.port.grant("parent:read");
        this.harness.port.loggedInAs("u1");
        ChildOverrides child = this.harness.cglibContext(ChildOverrides.class).getBean(ChildOverrides.class);
        assertThat(child.marked()).isEqualTo("child");
        assertThat(this.harness.port.permissionCalls).isEmpty();
    }

    /**
     * {@code AT13}：声明写在接口那一侧（方法级与类型级两格）——切点都不开，两个实现各跑一次
     * 都是零拦截（探针 U3/U4；启动期由校验器报"永远不会生效"，RS7）。
     */
    @Test
    void should_not_intercept_declarations_written_on_the_interface_side() {
        this.harness.port.grant("iface:method", "iface:type");
        this.harness.port.loggedInAs("u1");
        AnnotationConfigApplicationContext context =
                this.harness.jdkContext(IfaceMethodAnnotatedImpl.class, IfaceTypeAnnotatedImpl.class);
        assertThat(context.getBean(IfaceMethodAnnotated.class).run()).isEqualTo("ran");
        assertThat(context.getBean(IfaceTypeAnnotated.class).run()).isEqualTo("ran");
        assertThat(this.harness.port.permissionCalls).isEmpty();
    }

    /**
     * {@code AT14}：顺序判据用数值严格小于表达——鉴权在限流外面（值小者在外），
     * 一次权限拒绝不该消耗一次限流许可（与 Task 44 的 OR1 同一手法，不做时序用例）。
     */
    @Test
    void should_order_the_permission_check_strictly_outside_the_rate_limit() {
        assertThat(PermissionCheckAspect.ASPECT_ORDER)
                .as("鉴权必须在限流外面（值小者在外）")
                .isLessThan(RateLimitAspect.ASPECT_ORDER);
    }
}

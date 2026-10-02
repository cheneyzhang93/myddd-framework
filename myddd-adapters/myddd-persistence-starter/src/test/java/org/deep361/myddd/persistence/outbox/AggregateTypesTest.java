package org.deep361.myddd.persistence.outbox;

import org.aopalliance.intercept.MethodInterceptor;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.ddd.event.AbstractDomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventSource;
import org.deep361.myddd.kernel.ddd.model.AggregateRoot;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.TargetSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解包工具的四件事：认出代理类、上溯到真实聚合类、给出二进制类名、必要时换回真实实例。
 *
 * <p>代理形态全部用**真实生成器**造，不用 mock：Spring 的 {@link ProxyFactory}（CGLIB 与 JDK 两种形态）、
 * MyBatis 自带的 javassist 副本（持久层懒加载代理的真实出处）。Hibernate 形态用同名声明类做夹具
 * （见类 javadoc 要点 4：判定只需要"含各家标记 + 名字以父类名开头"，不需要复刻其命名公式）。
 */
class AggregateTypesTest {

    static class OrderCreated extends AbstractDomainEvent {
    }

    static class Order extends AggregateRoot<Long> {

        Order(Long id) {
            super(id);
        }

        void fire() {
            registerEvent(new OrderCreated());
        }

        /** 名字形如 {@code …$Order$Draft} 的合法嵌套子类：与 Hibernate 代理同前缀形状（U2）。 */
        static class Draft extends Order {

            Draft() {
                super(1L);
            }
        }
    }

    /** 名字恰好以父类名开头的**合法**子类：解包不许动它（U2）。 */
    static class OrderDraft extends Order {

        OrderDraft() {
            super(1L);
        }
    }

    /** 手工声明的 Hibernate 形态：二进制名 = 父类名 + {@code $HibernateProxy$…}，父类是聚合。 */
    static class Order$HibernateProxy$ab12cd extends Order {

        Order$HibernateProxy$ab12cd() {
            super(2L);
        }
    }

    /** CGLIB 形态的名字形状（真实代理是 {@code …$$SpringCGLIB$$0}，这里钉"再套一层"时的循环）。 */
    static class Order$$SpringCGLIB$$9 extends Order {

        Order$$SpringCGLIB$$9() {
            super(3L);
        }
    }

    static class Order$$SpringCGLIB$$9$$SpringCGLIB$$8 extends Order$$SpringCGLIB$$9 {

        Order$$SpringCGLIB$$9$$SpringCGLIB$$8() {
            super();
        }
    }

    interface Auditable {

        String tag();
    }

    static class AuditedOrder extends Order implements Auditable {

        AuditedOrder() {
            super(4L);
        }

        @Override
        public String tag() {
            return "audited";
        }
    }

    /** 拿不到目标实例的 target source（动态/池化代理），用来钉"快速失败而不是退回代理"（U13）。 */
    static final class UnavailableTargetSource implements TargetSource {

        @Override
        public Class<?> getTargetClass() {
            return Order.class;
        }

        @Override
        public boolean isStatic() {
            return false;
        }

        @Override
        public Object getTarget() {
            throw new IllegalStateException("target 取不到（池化 target source 已耗尽）");
        }

        @Override
        public void releaseTarget(Object target) {
        }
    }

    private static Object cglib(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }

    private static Object jdkProxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(false);
        factory.addInterface(Auditable.class);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        return factory.getProxy();
    }
    @Test
    void should_return_the_class_itself_when_it_is_not_proxy_shaped() {
        assertThat(AggregateTypes.unwrap(Order.class)).isEqualTo(Order.class);
        assertThat(AggregateTypes.unwrap(OrderCreated.class)).isEqualTo(OrderCreated.class);
    }

    @Test
    void should_not_unwrap_a_real_subclass_whose_name_merely_starts_with_the_superclass_name() {
        // OrderDraft 的名字以 "…$Order" 开头但余下部分是 "Draft"；Order$Draft 余下部分是 "$Draft"，
        // 与 Hibernate 的 "$HibernateProxy$…" 只差标记本身。两条都是业务真实子类：解包掉就等于让两个
        // 不同聚合类型共用一个通道，比代理污染更难查。
        assertThat(AggregateTypes.unwrap(OrderDraft.class)).isEqualTo(OrderDraft.class);
        assertThat(AggregateTypes.unwrap(Order.Draft.class)).isEqualTo(Order.Draft.class);
    }
    @Test
    void should_walk_out_of_a_spring_cglib_proxy_created_by_the_real_proxy_factory() {
        Object proxy = cglib(new Order(42L));

        // 先确认夹具本身仍是 Spring 的真实代理形态（形态一变，下面的断言就变成空断言）。
        assertThat(proxy.getClass().getName()).contains("$$SpringCGLIB$$").startsWith(Order.class.getName());
        assertThat(AggregateTypes.unwrap(proxy.getClass())).isEqualTo(Order.class);
        assertThat(AggregateTypes.name(proxy.getClass())).isEqualTo(Order.class.getName());
    }

    @Test
    void should_walk_out_of_a_javassist_proxy_generated_by_mybatis_own_javassist_copy() throws Exception {
        Class<?> proxyClass = javassistProxyClass();

        // MyBatis 3.5.19（MP 3.5.17 的传递依赖）内嵌 javassist，懒加载代理名形如 …_$$_jvst5ff_0。
        assertThat(proxyClass.getName()).contains("_$$_jvst").startsWith(Order.class.getName());
        assertThat(proxyClass.getSuperclass()).isEqualTo(Order.class);
        assertThat(AggregateTypes.unwrap(proxyClass)).isEqualTo(Order.class);
    }

    /** 用 MyBatis 自带的那份 javassist 真造一个代理类；只在测试里反射调用，生产代码不依赖它。 */
    private static Class<?> javassistProxyClass() throws Exception {
        ClassLoader loader = AggregateTypesTest.class.getClassLoader();
        Class<?> factoryType = loader.loadClass("org.apache.ibatis.javassist.util.proxy.ProxyFactory");
        Class<?> filterType = loader.loadClass("org.apache.ibatis.javassist.util.proxy.MethodFilter");
        Object factory = factoryType.getDeclaredConstructor().newInstance();
        factoryType.getMethod("setSuperclass", Class.class).invoke(factory, Order.class);
        Object filter = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{filterType},
                (proxy, method, args) -> !"getId".equals(((java.lang.reflect.Method) args[0]).getName()));
        factoryType.getMethod("setFilter", filterType).invoke(factory, filter);
        return (Class<?>) factoryType.getMethod("createClass").invoke(factory);
    }

    @Test
    void should_walk_out_of_the_hibernate_proxy_name_shape() {
        Class<?> proxyClass = Order$HibernateProxy$ab12cd.class;

        assertThat(proxyClass.getName()).contains("HibernateProxy").startsWith(Order.class.getName());
        assertThat(AggregateTypes.unwrap(proxyClass)).isEqualTo(Order.class);
    }

    @Test
    void should_keep_walking_through_a_doubly_proxy_shaped_chain() {
        // 实测单层 CGLIB 不会套两层（代理的代理仍直接挂在用户类下），但"回溯到第一个非代理类"必须是循环
        // 而不是"看一眼父类"：这里用两层的声明类钉住循环本身。
        Class<?> twice = Order$$SpringCGLIB$$9$$SpringCGLIB$$8.class;

        assertThat(twice.getSuperclass()).isEqualTo(Order$$SpringCGLIB$$9.class);
        assertThat(AggregateTypes.unwrap(twice)).isEqualTo(Order.class);
    }

    @Test
    void should_fail_fast_for_a_jdk_dynamic_proxy_class() {
        Object proxy = jdkProxy(new AuditedOrder());

        assertThat(proxy.getClass().getSuperclass()).isEqualTo(java.lang.reflect.Proxy.class);
        assertThat(proxy instanceof Order).isFalse();
        assertThatThrownBy(() -> AggregateTypes.unwrap(proxy.getClass()))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.reflect.Proxy")
                .hasMessageContaining("publish(event, aggregateType, aggregateId)")
                .hasMessageContaining("proxyTargetClass");
    }
    @Test
    void should_fail_fast_when_given_object_or_the_proxy_base_class_itself() {
        assertThatThrownBy(() -> AggregateTypes.unwrap(Object.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.Object");
        assertThatThrownBy(() -> AggregateTypes.unwrap(java.lang.reflect.Proxy.class))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.lang.reflect.Proxy");
    }

    @Test
    void should_name_the_unwrapped_class_with_its_binary_name() {
        assertThat(AggregateTypes.name(Order.class)).isEqualTo(Order.class.getName());
        assertThat(AggregateTypes.name(cglib(new Order(42L)).getClass())).isEqualTo(Order.class.getName());
        // 嵌套类的二进制名保留 Outer$Inner：与 kernel 的 getClass().getName() 口径一致，不改成简名。
        assertThat(AggregateTypes.name(OrderDraft.class)).endsWith("$OrderDraft");
    }

    @Test
    void should_reject_a_null_class_or_object() {
        assertThatThrownBy(() -> AggregateTypes.unwrap(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("待解包的类型不能为 null");
        assertThatThrownBy(() -> AggregateTypes.name((Class<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("待解包的类型不能为 null");
        assertThatThrownBy(() -> AggregateTypes.target(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("聚合实例不能为 null");
        assertThatThrownBy(() -> AggregateTypes.name((Object) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("聚合实例不能为 null");
    }

    @Test
    void should_return_the_target_instance_of_a_spring_proxy_and_the_same_object_otherwise() {
        Order target = new Order(42L);
        Object proxy = cglib(target);

        assertThat(AggregateTypes.target(proxy)).isSameAs(target);
        assertThat(AggregateTypes.target(target)).isSameAs(target);
    }

    @Test
    void should_name_the_target_class_when_the_aggregate_arrives_as_a_proxy() {
        assertThat(AggregateTypes.name(cglib(new AuditedOrder()))).isEqualTo(AuditedOrder.class.getName());
        // JDK 动态代理形态：AopUtils 能拿到目标类，所以对象入口救得回来（类入口救不回，见 U7）。
        Object jdk = jdkProxy(new AuditedOrder());
        assertThat(AggregateTypes.name(jdk)).isEqualTo(AuditedOrder.class.getName());
    }

    @Test
    void should_fail_fast_when_a_proxy_cannot_supply_its_target() {
        ProxyFactory factory = new ProxyFactory();
        factory.setTargetSource(new UnavailableTargetSource());
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed());
        Object proxy = factory.getProxy();

        assertThatThrownBy(() -> AggregateTypes.target(proxy))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("取不到被代理的目标聚合")
                .hasMessageContaining(proxy.getClass().getName())
                .cause().isInstanceOf(IllegalStateException.class)
                .hasMessage("target 取不到（池化 target source 已耗尽）");
    }
}

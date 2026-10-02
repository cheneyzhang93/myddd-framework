package org.deep361.myddd.authfixture;

import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.auth.config.AuthPortGuard;
import org.deep361.myddd.auth.permission.PermissionCheckAspect;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 一次用例一套：<b>真</b> Spring 代理上下文 + {@link FakeAuthPort} 替身，切面拿的是真
 * {@link AuthPortGuard}（内部装容器的 {@code ObjectProvider}，与 {@code 48f} 夹具纪律一致）。
 *
 * <p>{@code @EnableAspectJAutoProxy} 只出现在这里：框架自己从不加这个注解（一手：
 * {@code MydddConsistencyLockAopAutoConfigurationTest:57}），所以壳层测试必须自己加，
 * 否则切面根本不织入，"该拦的没拦"会读成实现正确。
 *
 * <p>{@code AT1}/{@code AT2} 要在同一条用例里跑 CGLIB 与 JDK 接口代理两种形态，因此这里提供
 * 两个 flavors；业务夹具由调用方传入（{@code fixture-} 前缀注册的纪律与
 * {@code ResilienceHarness} 一致：避免与 {@code @Bean} 方法名撞出幻影 bean）。
 */
public final class AuthHarness {

    public final FakeAuthPort port = new FakeAuthPort();

    private final List<AnnotationConfigApplicationContext> opened = new ArrayList<>();

    /** CGLIB 形态（Boot 缺省）：目标类无接口时代理类名含 {@code $$SpringCGLIB$$}。 */
    public AnnotationConfigApplicationContext cglibContext(Class<?>... services) {
        return open(CglibConfig.class, services);
    }

    /** JDK 接口代理形态：有接口时代理是 {@code java.lang.reflect.Proxy}，切点命中的是实现类上的注解。 */
    public AnnotationConfigApplicationContext jdkContext(Class<?>... services) {
        return open(JdkConfig.class, services);
    }

    /** 上下文里不注册任何 AuthPort：装配层与守卫的"零端口"形态用这一种。 */
    public AnnotationConfigApplicationContext contextWithoutPort(Class<?>... services) {
        return open(PortlessConfig.class, services);
    }

    public void closeAll() {
        this.opened.forEach(AnnotationConfigApplicationContext::close);
        this.opened.clear();
    }

    private AnnotationConfigApplicationContext open(Class<?> config, Class<?>... services) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("harness", this);
        context.register(config);
        for (Class<?> service : services) {
            context.registerBean("fixture-" + service.getSimpleName(), service);
        }
        context.refresh();
        this.opened.add(context);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class CglibConfig {

        @Bean
        AuthPort authPort(AuthHarness harness) {
            return harness.port;
        }

        @Bean
        PermissionCheckAspect permissionCheckAspect(ApplicationContext context) {
            return new PermissionCheckAspect(new AuthPortGuard(context.getBeanProvider(AuthPort.class)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class JdkConfig {

        @Bean
        AuthPort authPort(AuthHarness harness) {
            return harness.port;
        }

        @Bean
        PermissionCheckAspect permissionCheckAspect(ApplicationContext context) {
            return new PermissionCheckAspect(new AuthPortGuard(context.getBeanProvider(AuthPort.class)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class PortlessConfig {

        @Bean
        PermissionCheckAspect permissionCheckAspect(ApplicationContext context) {
            return new PermissionCheckAspect(new AuthPortGuard(context.getBeanProvider(AuthPort.class)));
        }
    }
}

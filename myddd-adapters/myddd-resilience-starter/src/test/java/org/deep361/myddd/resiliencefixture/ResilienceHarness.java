package org.deep361.myddd.resiliencefixture;

import org.deep361.myddd.resilience.CircuitBreakerAspect;
import org.deep361.myddd.resilience.PollSleeper;
import org.deep361.myddd.resilience.RateLimitAspect;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/** 一次用例一套：端口替身、节拍替身、护栏毫秒数，以及<b>真</b> Spring 代理上下文。 */
public final class ResilienceHarness {

    public final RecordingRateLimitPort port = new RecordingRateLimitPort();
    public final RecordingCircuitBreakerPort breakerPort = new RecordingCircuitBreakerPort();
    public final RecordingPollSleeper sleeper = new RecordingPollSleeper();
    public long ceilingMillis = 5_000L;
    public String fallbackPrefix = "";

    /**
     * 装限流切面的上下文；CGLIB 形态（Boot 缺省）。
     *
     * <p>{@code extraBeans} 用 {@code fixture-} 前缀注册，而不是让 Spring 按类名生成 bean 名：后者会在
     * "同一个类既写在 {@code @Bean} 里又出现在 extraBeans"时与配置类的方法名撞上，覆盖顺序取决于
     * 定义解析时机，读起来就是一次幻影 bean 事故（Plan 3 已登记过同族坑）。前缀让撞名变成不可能；
     * 代价是同类型出现两个 bean 时按类型注入会歧义，所以约定：<b>extraBeans 与下面 {@code @Bean} 声明过的类不许重叠</b>。
     */
    public AnnotationConfigApplicationContext rateLimitContext(Class<?>... extraBeans) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("harness", this);
        context.register(RateLimitConfig.class);
        registerExtras(context, extraBeans);
        context.refresh();
        return context;
    }

    private static void registerExtras(AnnotationConfigApplicationContext context, Class<?>... extraBeans) {
        for (Class<?> extra : extraBeans) {
            context.registerBean("fixture-" + extra.getSimpleName(), extra);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class RateLimitConfig {

        @Bean
        RecordingRateLimitPort rateLimitPort(ResilienceHarness harness) {
            return harness.port;
        }

        @Bean
        PollSleeper pollSleeper(ResilienceHarness harness) {
            return harness.sleeper;
        }

        @Bean
        GuardedService guardedService() {
            return new GuardedService();
        }

        @Bean
        ClassLevelGuardedService classLevelGuardedService() {
            return new ClassLevelGuardedService();
        }

        @Bean
        IfaceDeclaredServiceImpl ifaceDeclaredService() {
            return new IfaceDeclaredServiceImpl();
        }

        @Bean
        RateLimitAspect rateLimitAspect(ApplicationContext context, ResilienceHarness harness) {
            return new RateLimitAspect(context.getBeanProvider(RateLimitPort.class), harness.ceilingMillis,
                    harness.sleeper);
        }
    }

    /**
     * 熔断切面上下文；端口替身与 fallback 前缀都取自 harness 当前值。
     *
     * <p>该上下文里<b>两个切面都在</b>：{@code CB11} 的判据就是"降级这一次没有走进限流切面"，
     * 少装限流那一格就没有可观察的第二次命中。其余 {@code CB} 格子不读限流计数。
     */
    public AnnotationConfigApplicationContext circuitBreakerContext(Class<?>... extraBeans) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("harness", this);
        context.register(CircuitBreakerConfig.class);
        registerExtras(context, extraBeans);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class CircuitBreakerConfig {

        @Bean
        RecordingCircuitBreakerPort circuitBreakerPort(ResilienceHarness harness) {
            return harness.breakerPort;
        }

        /** 限流端口与限流切面<b>一起</b>装：{@code CB11} 的判据就是"降级这一次没有走进限流切面"。 */
        @Bean
        RecordingRateLimitPort rateLimitPort(ResilienceHarness harness) {
            return harness.port;
        }

        @Bean
        PollSleeper pollSleeper(ResilienceHarness harness) {
            return harness.sleeper;
        }

        @Bean
        RateLimitAspect rateLimitAspect(ApplicationContext context, ResilienceHarness harness) {
            return new RateLimitAspect(context.getBeanProvider(RateLimitPort.class), harness.ceilingMillis,
                    harness.sleeper);
        }

        @Bean
        ResilienceFixtures.BreakerService breakerService() {
            return new ResilienceFixtures.BreakerService();
        }

        @Bean
        CircuitBreakerAspect circuitBreakerAspect(ApplicationContext context, ResilienceHarness harness) {
            return new CircuitBreakerAspect(context.getBeanProvider(CircuitBreakerPort.class),
                    harness.fallbackPrefix);
        }
    }

    /**
     * 两个切面同时在场；`OR` 组的四条判据都取这里的两个端口计数。
     *
     * <p>不注册任何业务 bean 以外的切面、也不写 {@code @Order}：顺序是<b>两个切面类上的注解</b>决定的，
     * {@code OR3} 直读那两个常量，本上下文只负责让"并存"这件事是真的。
     */
    public AnnotationConfigApplicationContext bothAspectContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("harness", this);
        context.register(BothConfig.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class BothConfig {

        @Bean
        RecordingRateLimitPort rateLimitPort(ResilienceHarness harness) {
            return harness.port;
        }

        @Bean
        RecordingCircuitBreakerPort circuitBreakerPort(ResilienceHarness harness) {
            return harness.breakerPort;
        }

        @Bean
        ResilienceFixtures.CombinedService combinedService() {
            return new ResilienceFixtures.CombinedService();
        }

        @Bean
        RateLimitAspect rateLimitAspect(ApplicationContext context, ResilienceHarness harness) {
            return new RateLimitAspect(context.getBeanProvider(RateLimitPort.class), harness.ceilingMillis,
                    harness.sleeper);
        }

        @Bean
        CircuitBreakerAspect circuitBreakerAspect(ApplicationContext context, ResilienceHarness harness) {
            return new CircuitBreakerAspect(context.getBeanProvider(CircuitBreakerPort.class),
                    harness.fallbackPrefix);
        }
    }

    /**
     * 与上面同形，但<b>不注册</b> {@code rateLimitPort} 那个 bean：`RL14` 要的正是"容器里没有端口时，
     * 切面照样建得起来、注解照样被拦，报错发生在第一次调用而不是启动期"。
     * 这依赖 Step 6 的装配用 {@code ObjectProvider} 收端口（与 {@code IdempotentAspect} 同一条理由）。
     */
    public AnnotationConfigApplicationContext rateLimitContextWithoutPort(Class<?>... extraBeans) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("harness", this);
        context.register(PortlessConfig.class);
        for (Class<?> extra : extraBeans) {
            context.registerBean(extra);
        }
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class PortlessConfig {

        @Bean
        PollSleeper pollSleeper(ResilienceHarness harness) {
            return harness.sleeper;
        }

        @Bean
        GuardedService guardedService() {
            return new GuardedService();
        }

        @Bean
        RateLimitAspect rateLimitAspect(ApplicationContext context, ResilienceHarness harness) {
            return new RateLimitAspect(context.getBeanProvider(RateLimitPort.class), harness.ceilingMillis,
                    harness.sleeper);
        }
    }
}

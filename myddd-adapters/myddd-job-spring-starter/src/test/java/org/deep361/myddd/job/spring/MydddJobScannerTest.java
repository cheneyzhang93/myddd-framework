package org.deep361.myddd.job.spring;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.job.JobContext;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.MydddJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MydddJobScanner} 的扫描面（{@code SG} 组）。夹具全部走 bean 定义，与真实应用的形态一致。 */
class MydddJobScannerTest {

    /** 夹具的观察点：哪个 handler 被派发过、哪些夹具类被构造过。 */
    private static final List<String> observed = new ArrayList<>();
    private static final AtomicInteger CREATED = new AtomicInteger();

    /** 探针 S4 读出的形态：手工注册（无 bean 定义）的 JDK 代理压根不会进命中列表，所以夹具一律用定义。 */
    static class TimedJob implements JobHandler {
        TimedJob() {
            CREATED.incrementAndGet();
        }

        @Override
        public void execute(JobContext context) {
            observed.add(getClass().getSimpleName());
        }
    }

    @MydddJob(name = "nightly-settle", cron = "0 0 3 * * ?")
    static class SettleJob extends TimedJob {
    }

    /** SG4 用：注解在父类，子类不带任何注解。 */
    @MydddJob(name = "inherited-job", cron = "0 0 6 * * ?")
    static class AnnotatedParent extends TimedJob {
    }

    static class ChildOfAnnotated extends AnnotatedParent {
    }

    /** SG6 用：定义声明 lazy-init，扫描不许把它拉起来。 */
    @MydddJob(name = "lazy-job", cron = "0 0 7 * * ?")
    static class LazyJob extends TimedJob {
    }

    private DefaultListableBeanFactory beanFactory;
    private RecordingCronTaskScheduler scheduler;
    private SpringJobPort port;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        scheduler = new RecordingCronTaskScheduler();
        port = new SpringJobPort(scheduler);
        observed.clear();
        CREATED.set(0);
    }

    private void scan() {
        new MydddJobScanner(beanFactory, port).afterSingletonsInstantiated();
    }

    private void registerSingletonBean(String beanName, Class<?> type) {
        beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(type));
    }

    /** 定义声明目标类、实例却是代理——真实 AOP 代理 bean 的形状（S4-23/S4-18 证明这种定义照样命中）。 */
    private void registerProxiedBean(String beanName, Class<?> type, boolean jdkProxy) {
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setInstanceSupplier(() -> {
            Object target = createTarget(type);
            // 必须按目标实例建：ProxyFactory 没有"只给接口"的重载，写成 new ProxyFactory(JobHandler.class)
            // 是把 Class 对象当成了被代理目标，产出的 JDK 代理只实现 java.lang.Class 的那几个接口
            //（实测 bean.getClass()=jdk.proxy2.$Proxy47、instanceof JobHandler 为 false）。
            // proxyTargetClass=false 且目标有用户接口（JobHandler）时 DefaultAopProxyFactory 才给 JDK 代理，
            // 所以 SG2 与 SG3 确实是两种形态——两种形态各由那两条"代理形状"断言钉住。
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(!jdkProxy);
            return factory.getProxy(MydddJobScannerTest.class.getClassLoader());
        });
        beanFactory.registerBeanDefinition(beanName, definition);
    }

    private static Object createTarget(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("夹具类构造失败：" + type.getName(), failure);
        }
    }

    /** SG1：普通 bean 被扫到并逐字注册。 */
    @Test
    void should_register_a_bean_annotated_with_myddd_job() {
        registerSingletonBean("settle", SettleJob.class);
        scan();
        assertTrue(port.registeredJobs().contains("nightly-settle"),
                "扫描后端口里没有该任务：" + port.registeredJobs());
        assertEquals("0 0 3 * * ?", scheduler.cronOf("nightly-settle"));
    }

    /**
     * SG2：CGLIB 子类代理（代理类上找得到注解，但我们统一走穿透）。
     *
     * <p>先断"确实是 CGLIB"：这个断言不在，SG2 与 SG3 就可能在测同一种形态而两条都绿。
     */
    @Test
    void should_register_a_cglib_proxied_job_bean() {
        registerProxiedBean("settle", SettleJob.class, false);
        assertTrue(beanFactory.getBean("settle").getClass().getName().contains("$$SpringCGLIB$$"),
                "夹具给的不是 CGLIB 子类，这一格就与 SG1 同形");
        scan();
        assertEquals("0 0 3 * * ?", scheduler.cronOf("nightly-settle"));
    }

    /** SG3：JDK 动态代理——代理类上注解彻底看不见（S4-8/S4-10），只有穿透找得到。 */
    @Test
    void should_register_a_jdk_proxied_job_bean() {
        registerProxiedBean("settle", SettleJob.class, true);
        assertTrue(java.lang.reflect.Proxy.isProxyClass(beanFactory.getBean("settle").getClass()),
                "夹具给的不是 JDK 代理，这一格就退化成 SG2");
        scan();
        assertEquals("0 0 3 * * ?", scheduler.cronOf("nightly-settle"));
        scheduler.fire("nightly-settle");
        assertEquals(List.of("SettleJob"), observed, "派发到的不是目标类那个实例");
    }

    /** SG4：注解在父类上（@MydddJob 没有 @Inherited，S4-29）。 */
    @Test
    void should_find_the_annotation_on_a_superclass() {
        registerSingletonBean("child", ChildOfAnnotated.class);
        scan();
        assertEquals("0 0 6 * * ?", scheduler.cronOf("inherited-job"));
    }

    /** SG5：注册次序由任务名决定，与 bean 定义的先后无关（同名冲突时"谁占住名字"由 AC8 在真容器上钉）。 */
    @Test
    void should_register_in_job_name_order_regardless_of_bean_definition_order() {
        registerSingletonBean("bean-c", ThirdJob.class);
        registerSingletonBean("bean-a", AlphaJob.class);
        registerSingletonBean("bean-b", BetaJob.class);
        scan();
        assertEquals(List.of("alpha-job", "beta-job", "third-job"), scheduler.scheduleOrder());
    }

    /** SG6：懒加载的 job bean 不被实例化、不被注册（Step 5 的 isLazy 过滤）。 */
    @Test
    void should_not_force_instantiation_of_a_lazy_job_bean() {
        RootBeanDefinition definition = new RootBeanDefinition(LazyJob.class);
        definition.setLazyInit(true);
        beanFactory.registerBeanDefinition("lazy", definition);
        scan();
        assertEquals(0, CREATED.get(), "扫描把 @Lazy 的 bean 提前创建了——那是替用户否决 @Lazy（S4-2 对 S4-5）");
        assertTrue(port.registeredJobs().isEmpty(), "懒加载的 job 不该被注册：" + port.registeredJobs());
    }

    /** SG7：注解在、但没实现 JobHandler ⇒ 启动期就崩，且诊断四段齐；不许退化成静默跳过。 */
    @Test
    void should_fail_fast_when_an_annotated_bean_is_not_a_job_handler() {
        registerSingletonBean("not-a-handler", DeclaredOnlyJob.class);
        FrameworkException failure = assertThrows(FrameworkException.class, this::scan);
        String message = failure.getMessage();
        assertTrue(message.contains("not-a-handler"), "诊断缺 bean 名：" + message);
        assertTrue(message.contains(DeclaredOnlyJob.class.getName()), "诊断缺实际类名：" + message);
        assertTrue(message.contains("declared-only-job"), "诊断缺任务名：" + message);
        assertTrue(message.contains("org.deep361.myddd.kernel.port.job.JobHandler"),
                "诊断必须给出要 implements 哪个接口的全限定名，否则用户得去猜：" + message);
        assertTrue(port.registeredJobs().isEmpty(), "报错之前不许有任何注册落进端口：" + port.registeredJobs());
    }

    /** SG5 的补充夹具：三个只用于次序断言的任务。 */
    @MydddJob(name = "third-job", cron = "0 0 1 * * ?")
    static class ThirdJob extends TimedJob {
    }

    @MydddJob(name = "alpha-job", cron = "0 0 1 * * ?")
    static class AlphaJob extends TimedJob {
    }

    @MydddJob(name = "beta-job", cron = "0 0 1 * * ?")
    static class BetaJob extends TimedJob {
    }

    /** SG7 用：带注解但没有实现 JobHandler（@MydddJob 是 @Target(TYPE)，注解本身拦不住这种写法）。 */
    @MydddJob(name = "declared-only-job", cron = "0 0 2 * * ?")
    static class DeclaredOnlyJob {
    }
}

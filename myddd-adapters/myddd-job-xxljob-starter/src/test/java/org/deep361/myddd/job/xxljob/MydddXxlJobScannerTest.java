package org.deep361.myddd.job.xxljob;

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

/** {@link MydddXxlJobScanner} 的扫描面（{@code SG} 组）：与 spring 侧同形，观察点换成事件序列。 */
class MydddXxlJobScannerTest {

    private static final List<String> observed = new ArrayList<>();
    private static final AtomicInteger CREATED = new AtomicInteger();

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

    @MydddJob(name = "inherited-job", cron = "0 0 6 * * ?")
    static class AnnotatedParent extends TimedJob {
    }

    static class ChildOfAnnotated extends AnnotatedParent {
    }

    @MydddJob(name = "lazy-job", cron = "0 0 7 * * ?")
    static class LazyJob extends TimedJob {
    }

    @MydddJob(name = "third-job", cron = "0 0 1 * * ?")
    static class ThirdJob extends TimedJob {
    }

    @MydddJob(name = "alpha-job", cron = "0 0 1 * * ?")
    static class AlphaJob extends TimedJob {
    }

    @MydddJob(name = "beta-job", cron = "0 0 1 * * ?")
    static class BetaJob extends TimedJob {
    }

    /** SG7 用：带注解但没实现 JobHandler。 */
    @MydddJob(name = "not-a-handler", cron = "0 0 8 * * ?")
    static class DeclaredOnlyJob {
    }

    private DefaultListableBeanFactory beanFactory;
    private RecordingXxlJobRuntime runtime;
    private XxlJobPort port;

    @BeforeEach
    void setUp() {
        beanFactory = new DefaultListableBeanFactory();
        runtime = new RecordingXxlJobRuntime();
        port = new XxlJobPort(runtime);
        observed.clear();
        CREATED.set(0);
    }

    private void scan() {
        new MydddXxlJobScanner(beanFactory, port, runtime).afterSingletonsInstantiated();
    }

    private void registerSingletonBean(String beanName, Class<?> type) {
        beanFactory.registerBeanDefinition(beanName, new RootBeanDefinition(type));
    }

    /** 定义声明目标类、实例却是代理：真实 AOP 代理 bean 的形状（S4-23/S4-18 证明这种定义照样命中）。 */
    private void registerProxiedBean(String beanName, Class<?> type, boolean jdkProxy) {
        RootBeanDefinition definition = new RootBeanDefinition(type);
        definition.setInstanceSupplier(() -> {
            Object target = createTarget(type);
            // 必须按目标实例建：ProxyFactory 没有"只给接口"的重载，写成 new ProxyFactory(JobHandler.class)
            // 是把 Class 对象当成了被代理目标，产出的 JDK 代理只实现 java.lang.Class 的那几个接口
            //（实测 bean.getClass()=jdk.proxy2.$Proxy50 且 instanceof JobHandler 为 false）。
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(!jdkProxy);
            return factory.getProxy(MydddXxlJobScannerTest.class.getClassLoader());
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

    /** SG1：扫到就注册，并且启动排在注册之后。 */
    @Test
    void should_register_an_annotated_bean_and_start_afterwards() {
        registerSingletonBean("settle", SettleJob.class);
        scan();
        assertTrue(port.registeredJobs().contains("nightly-settle"),
                "扫描后端口里没有该任务：" + port.registeredJobs());
        assertEquals(List.of("bind:nightly-settle", "start"), runtime.events(),
                "注册与启动的次序不是 [bind, start]：调度中心可能在绑定之前就把触发打过来");
    }

    /** SG2：CGLIB 子类代理。 */
    @Test
    void should_register_a_cglib_proxied_job_bean() {
        registerProxiedBean("settle", SettleJob.class, false);
        assertTrue(beanFactory.getBean("settle").getClass().getName().contains("$$SpringCGLIB$$"),
                "夹具给的不是 CGLIB 子类，这一格就与 SG1 同形");
        scan();
        assertTrue(port.registeredJobs().contains("nightly-settle"));
    }

    /** SG3：JDK 动态代理——代理类上注解不可见，只有穿透找得到；派发必须落到目标类实例。 */
    @Test
    void should_register_a_jdk_proxied_job_bean() {
        registerProxiedBean("settle", SettleJob.class, true);
        assertTrue(java.lang.reflect.Proxy.isProxyClass(beanFactory.getBean("settle").getClass()),
                "夹具给的不是 JDK 代理，这一格就退化成 SG2");
        scan();
        assertTrue(port.registeredJobs().contains("nightly-settle"));
        port.triggerNow("nightly-settle", "");
        assertEquals(List.of("SettleJob"), observed, "派发到的不是目标类那个实例");
    }

    /** SG4：注解在父类上。 */
    @Test
    void should_find_the_annotation_on_a_superclass() {
        registerSingletonBean("child", ChildOfAnnotated.class);
        scan();
        assertTrue(port.registeredJobs().contains("inherited-job"), port.registeredJobs().toString());
    }

    /** SG5：绑定次序由任务名决定，与 bean 定义先后无关。 */
    @Test
    void should_register_in_job_name_order_regardless_of_bean_definition_order() {
        registerSingletonBean("bean-c", ThirdJob.class);
        registerSingletonBean("bean-a", AlphaJob.class);
        registerSingletonBean("bean-b", BetaJob.class);
        scan();
        assertEquals(List.of("bind:alpha-job", "bind:beta-job", "bind:third-job", "start"), runtime.events());
    }

    /** SG6：懒加载的 job bean 不许被扫描拉起来。 */
    @Test
    void should_not_force_instantiation_of_a_lazy_job_bean() {
        RootBeanDefinition definition = new RootBeanDefinition(LazyJob.class);
        definition.setLazyInit(true);
        beanFactory.registerBeanDefinition("lazy", definition);
        scan();
        assertEquals(0, CREATED.get(), "扫描把 @Lazy 的 bean 提前创建了——那是替用户否决 @Lazy（S4-2 对 S4-5）");
        assertTrue(port.registeredJobs().isEmpty(), "懒加载的 job 不该被注册：" + port.registeredJobs());
        assertTrue(runtime.events().isEmpty(),
                "一个任务都没有却启动了执行器：那等于在装配面之外多要一个真端口，而 admin 里只会多一个选不到作业的分组");
    }

    /** SG7：带注解却没实现 JobHandler ⇒ 快速失败，消息四段齐。 */
    @Test
    void should_fail_fast_when_an_annotated_bean_is_not_a_job_handler() {
        registerSingletonBean("broken", DeclaredOnlyJob.class);
        FrameworkException failure = assertThrows(FrameworkException.class, this::scan);
        assertTrue(failure.getMessage().contains("broken"), failure.getMessage());
        assertTrue(failure.getMessage().contains(DeclaredOnlyJob.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("not-a-handler"), failure.getMessage());
        assertTrue(failure.getMessage().contains("org.deep361.myddd.kernel.port.job.JobHandler"),
                failure.getMessage());
    }
}

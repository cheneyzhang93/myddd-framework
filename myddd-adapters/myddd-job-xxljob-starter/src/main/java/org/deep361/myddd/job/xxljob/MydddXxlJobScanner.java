package org.deep361.myddd.job.xxljob;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.job.JobDefinition;
import org.deep361.myddd.kernel.port.job.JobHandler;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.deep361.myddd.kernel.port.job.MydddJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 启动期把容器里所有 {@code @MydddJob} bean 注册进 {@link JobPort}，注册全部完成后才启动执行器。
 *
 * <p><b>与 spring 侧那份的关系</b>：本类与 {@code org.deep361.myddd.job.spring.MydddJobScanner} 同形，
 * 这是 Job 没有装配壳（R1）的明文代价。两份代码不许互相依赖（§0.3-11），<b>行为</b>一致性由 Task 43 的
 * {@code EQ} 组钉；改任何一侧的扫描判据时必须同时改另一侧，两侧的 {@code SG} 组编号一一对应。
 *
 * <p><b>最后那句启动是本侧独有的</b>，而且必须在注册循环<b>之后</b>：调度中心在执行器注册上之后随时可以
 * 下发触发，而 {@code JobThread} 按名字从执行器表取 handler——先启动后注册之间那段窗口里到达的触发会直接
 * 失败。{@code AB9} 钉"每一次 {@code bind} 都早于 {@code start}"，{@code SG1}/{@code SG5} 钉事件序列的收尾。
 *
 * <p><b>一个任务都没有时不启动</b>：启动换来的是"admin 里多一个永远选不到作业的执行器分组"，代价是一个
 * 真监听端口加两组线程（Step 0 第 6 条：本模块的单测一条都不真启动；装配面的用例因此可以在零配置下跑出
 * 我们的 {@code DefaultXxlJobRuntime} 而不出网）。真要一台空执行器在线的人自己贡献一个 {@code XxlJobRuntime}
 * 并在自己的生命周期里启动它——那正是 {@code AB7} 开着的那扇门。本模块不引 {@code XxlJobSpringExecutor}，
 * 所以厂商的 {@code @XxlJob} 方法本来就不会被注册，"空任务但仍有厂商作业"这一形态在本装配下不存在。
 */
public class MydddXxlJobScanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MydddXxlJobScanner.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final JobPort port;
    private final XxlJobRuntime runtime;

    public MydddXxlJobScanner(ConfigurableListableBeanFactory beanFactory, JobPort port, XxlJobRuntime runtime) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory 不能为 null");
        this.port = Objects.requireNonNull(port, "port 不能为 null");
        this.runtime = Objects.requireNonNull(runtime, "runtime 不能为 null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Discovered> discovered = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForAnnotation(MydddJob.class)) {
            if (isLazy(beanFactory, beanName)) {
                log.warn("带 @MydddJob 的 bean 声明了 lazy-init，不会为了注册任务而把它提前实例化：bean="
                        + beanName + "——要么去掉 @Lazy，要么该任务确实不该跑");
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            MydddJob annotation = AnnotatedElementUtils.findMergedAnnotation(
                    AopProxyUtils.ultimateTargetClass(bean), MydddJob.class);
            if (annotation == null) {
                log.warn("命中了 @MydddJob 但穿透代理后在真实类上找不到它，已跳过：bean=" + beanName
                        + "，实例类=" + bean.getClass().getName());
                continue;
            }
            if (!(bean instanceof JobHandler handler)) {
                throw new FrameworkException("带 @MydddJob 的 bean 必须实现 JobHandler："
                        + "bean=" + beanName + "，class=" + bean.getClass().getName()
                        + "，任务名=" + annotation.name()
                        + "（期望：implements org.deep361.myddd.kernel.port.job.JobHandler）");
            }
            discovered.add(new Discovered(beanName,
                    new JobDefinition(annotation.name(), annotation.cron(), annotation.description()), handler));
        }
        discovered.sort(Comparator.comparing((Discovered each) -> each.portableKey())
                .thenComparing(Discovered::beanName));
        for (Discovered each : discovered) {
            port.register(each.definition(), each.handler());
            log.info("已注册定时任务：job={}，cron={}，bean={}（XXL-Job 侧 cron 由调度中心解释）",
                    each.definition().name(), each.definition().cron(), each.beanName());
        }
        if (!discovered.isEmpty()) {
            runtime.start();
        }
    }

    private static boolean isLazy(ConfigurableListableBeanFactory bf, String beanName) {
        return bf.containsBeanDefinition(beanName) && bf.getBeanDefinition(beanName).isLazyInit();
    }

    /** 排序主键（任务名）与 bean 名一起进字典序，保证同名不同 bean 的次序也确定。 */
    private record Discovered(String beanName, JobDefinition definition, JobHandler handler) {

        String portableKey() {
            return definition.name();
        }
    }
}

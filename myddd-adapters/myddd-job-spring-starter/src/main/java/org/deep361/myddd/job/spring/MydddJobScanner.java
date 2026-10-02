package org.deep361.myddd.job.spring;

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
 * 启动期把容器里所有 {@code @MydddJob} bean 注册进 {@link JobPort}。
 *
 * <p><b>时机</b>：{@link SmartInitializingSingleton#afterSingletonsInstantiated()}——全部单例就绪之后
 * 才扫，业务 bean 里对 {@code @MydddJob} 的代理与继承关系都已经定型（与 Task 34 的
 * {@code MessageListenerRegistrar} 同一时机，Plan 3 的既有裁定）。
 *
 * <p><b>两个必须走对的方法</b>（照 {@code persistence-starter} 的既有形状）：
 * {@link AopProxyUtils#ultimateTargetClass(Object)} 取真实类（JDK 动态代理下 {@code bean.getClass()}
 * 是 {@code $ProxyN}，注解看不见，{@code SG3} 钉这条）；{@link AnnotatedElementUtils#findMergedAnnotation}
 * 沿继承层次找（{@code @MydddJob} 没有 {@code @Inherited}，CGLIB 子类上 {@code getAnnotation} 返回 null，
 * {@code SG2} 钉这条）。
 *
 * <p><b>顺序确定</b>：注册次序按<b>任务名</b>字典序排（{@code SG5}）。理由是同名冲突时的报错必须与 bean
 * 定义次序无关——否则"哪个名字先注册成功"随扫描次序漂移，两份完全相同的配置文件在不同构建里报出不同的冲突。
 */
public class MydddJobScanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MydddJobScanner.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final JobPort port;

    /**
     * @param port 注册目标。类型是端口而不是 {@code SpringJobPort}：用户自己贡献端口时（{@code AC5}）
     *             我们的实现让位，扫描器必须跟着注到用户那个 bean 上；形参写成实现类会让上下文<b>启动失败</b>。
     */
    public MydddJobScanner(ConfigurableListableBeanFactory beanFactory, JobPort port) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory 不能为 null");
        this.port = Objects.requireNonNull(port, "port 不能为 null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Discovered> discovered = new ArrayList<>();
        // 唯一入口：getBeanNamesForAnnotation 只读定义上的声明类、一条都不实例化（S4-1 对 S4-2）；
        // 换成 getBeansWithAnnotation 会把懒加载 bean 提前拉起（S4-5 的 4 → 6），代价由 SG6 钉住。
        for (String beanName : beanFactory.getBeanNamesForAnnotation(MydddJob.class)) {
            if (isLazy(beanFactory, beanName)) {
                log.warn("带 @MydddJob 的 bean 声明了 lazy-init，本框架不会为了注册任务而把它提前实例化："
                        + "bean=" + beanName + "——要么去掉 @Lazy，要么该任务确实不该跑");
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            MydddJob annotation = AnnotatedElementUtils.findMergedAnnotation(
                    AopProxyUtils.ultimateTargetClass(bean), MydddJob.class);
            if (annotation == null) {
                // 不是死代码：手工注册（无定义）的 JDK 代理不进命中列表（S4-1），而"定义声明目标类、
                // 实例却是用户自己 Proxy.newProxyInstance 出来的、不带 Advised 信息"的 bean 会命中却
                // 穿透不到（S4-8/S4-10：JDK 代理类上注解不可见）——跳过并留 WARN，比静默不跑或崩都好。
                log.warn("命中了 @MydddJob 但穿透代理后在真实类上找不到它，已跳过：bean=" + beanName
                        + "，实例类=" + bean.getClass().getName());
                continue;
            }
            if (!(bean instanceof JobHandler handler)) {
                // 静默跳过等于用户的任务永远不跑而启动日志一片绿（SG7/JM19），必须启动期就崩。
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
            log.info("已注册定时任务：job={}，cron={}，bean={}", each.definition().name(),
                    each.definition().cron(), each.beanName());
        }
    }

    /** 排序主键（任务名）与 bean 名一起进字典序，保证同名不同 bean 的次序也确定。嵌套而不独立成文件：它的存在只服务于这一次排序。 */
    private record Discovered(String beanName, JobDefinition definition, JobHandler handler) {

        String portableKey() {
            return definition.name();
        }
    }

    /**
     * 该 bean 的定义是否声明了 lazy-init。手工注册的单例（{@code registerSingleton}，没有 bean 定义）
     * 早已实例化，直接返回 {@code false}——它不需要也不会被"提前拉起"。
     */
    private static boolean isLazy(ConfigurableListableBeanFactory beanFactory, String beanName) {
        return beanFactory.containsBeanDefinition(beanName)
                && beanFactory.getBeanDefinition(beanName).isLazyInit();
    }
}

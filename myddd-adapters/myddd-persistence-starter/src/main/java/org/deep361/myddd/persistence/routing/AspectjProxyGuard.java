package org.deep361.myddd.persistence.routing;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 路由模式的收尾自检：确认 {@link DataSourceRoutingAspect} 真的会被应用。
 *
 * <p>{@code @Aspect} 只有在容器里有 {@link AnnotationAwareAspectJAutoProxyCreator} 时才被解析；
 * {@code @EnableTransactionManagement} 单独注册的是 {@code InfrastructureAdvisorAutoProxyCreator}，
 * 它只认 {@code Advisor} bean，会让本切面<b>静默不生效</b>——于是所有请求都打在默认数据源上，不报错、
 * 不留日志（实测见 Task 17 设计要点 3）。Boot 的 {@code AopAutoConfiguration} 在
 * {@code org.aspectj.weaver.Advice} 存在时默认开启 AspectJ 自动代理，本模块把它列为 compile 依赖，
 * 因此正常应用不会触发这条；触发它的是 {@code spring.aop.auto=false} 或脱离 Boot 自动配置的上下文。
 *
 * <p>选择 {@link SmartInitializingSingleton} 而不是 {@code BeanFactoryPostProcessor}：判定要看的
 * 代理创建器此时已经确定存在（它是 bean 后置处理器，早于单例实例化阶段注册完毕），而这条检查不需要
 * 干预任何 bean 定义。
 */
public class AspectjProxyGuard implements SmartInitializingSingleton {

    private final ConfigurableListableBeanFactory beanFactory;

    public AspectjProxyGuard(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (beanFactory.getBeanNamesForType(AnnotationAwareAspectJAutoProxyCreator.class).length == 0) {
            throw new FrameworkException("路由模式已启用，但容器里没有 "
                    + AnnotationAwareAspectJAutoProxyCreator.class.getSimpleName()
                    + "：@UseDataSource 切面不会被应用，每个请求都会静默落到 myddd.persistence.default-datasource"
                    + " 上。请恢复 Spring Boot 的 AOP 自动代理（检查 spring.aop.auto 是否为 false）"
                    + "，或自行声明 @EnableAspectJAutoProxy(proxyTargetClass = true)");
        }
    }
}

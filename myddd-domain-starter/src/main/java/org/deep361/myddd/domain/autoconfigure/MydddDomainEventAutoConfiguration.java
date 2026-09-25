package org.deep361.myddd.domain.autoconfigure;

import org.deep361.myddd.domain.context.DomainModuleRegistry;
import org.deep361.myddd.domain.event.DomainEventDispatcher;
import org.deep361.myddd.domain.event.DomainEventListenerRegistrar;
import org.deep361.myddd.domain.event.SpringDomainEventPublisher;
import org.deep361.myddd.domain.event.TransactionStageExecutor;
import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 领域事件自动配置：装配进程内事件总线（监听器注册器、事务阶段执行器、分发器、发布器、异步线程池）。
 *
 * <p>依赖 {@link MydddDomainAutoConfiguration} 提供的 {@link DomainModuleRegistry}：监听器 bean 必须落在某个
 * {@code @Domain} 领域模块的扫描包内，否则启动失败。
 *
 * <p>每个 bean 都是 {@code @ConditionalOnMissingBean}：应用可整体替换任一环节（例如换成 Outbox 发布器）。
 *
 * <p>配置校验在<b>本配置类构造期</b>完成（不挂在可被应用替换的线程池 bean 上）：无论应用是否自定义异步
 * 线程池，非法的 {@code myddd.domain.event.async.*} 都会让启动失败，不会被静默忽略。
 */
@AutoConfiguration(after = MydddDomainAutoConfiguration.class)
@EnableConfigurationProperties(MydddDomainEventProperties.class)
public class MydddDomainEventAutoConfiguration {

    /** 异步监听器线程池的 bean 名（应用可声明同名 bean 整体替换）。 */
    public static final String ASYNC_EXECUTOR_BEAN_NAME = "mydddDomainEventAsyncExecutor";

    public MydddDomainEventAutoConfiguration(MydddDomainEventProperties properties) {
        properties.getAsync().validate();
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventListenerRegistrar domainEventListenerRegistrar(ListableBeanFactory beanFactory,
                                                                   DomainModuleRegistry moduleRegistry) {
        return new DomainEventListenerRegistrar(beanFactory, moduleRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionStageExecutor domainEventStageExecutor() {
        return new TransactionStageExecutor();
    }

    /**
     * 异步监听器线程池：有界队列 + 关闭时等待在飞任务（配置合法性已在本配置类构造期校验）。
     *
     * <p>{@code waitForTasksToCompleteOnShutdown} 与 {@code awaitTerminationSeconds} 两项都置上：前者覆盖直接
     * {@code destroy()} 的路径（Spring 7 容器关闭走"早停机 lifecycle"，本身不依赖该开关），后者给出等待上限。
     */
    @Bean(name = ASYNC_EXECUTOR_BEAN_NAME)
    @ConditionalOnMissingBean(name = ASYNC_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor mydddDomainEventAsyncExecutor(MydddDomainEventProperties properties) {
        MydddDomainEventProperties.Async async = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.getCorePoolSize());
        executor.setMaxPoolSize(async.getMaxPoolSize());
        executor.setQueueCapacity(async.getQueueCapacity());
        executor.setKeepAliveSeconds((int) async.getKeepAlive().toSeconds());
        executor.setThreadNamePrefix("myddd-domain-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) async.getShutdownTimeout().toSeconds());
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventDispatcher domainEventDispatcher(DomainEventListenerRegistrar registrar,
                                                       TransactionStageExecutor stageExecutor,
                                                       @Qualifier(ASYNC_EXECUTOR_BEAN_NAME) Executor asyncExecutor) {
        return new DomainEventDispatcher(registrar, stageExecutor, asyncExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventPublisher domainEventPublisher(DomainEventDispatcher dispatcher) {
        return new SpringDomainEventPublisher(dispatcher);
    }
}

package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.job.spring.CronTaskScheduler;
import org.deep361.myddd.job.spring.DefaultCronTaskScheduler;
import org.deep361.myddd.job.spring.MydddJobScanner;
import org.deep361.myddd.job.spring.SpringJobPort;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Job 能力（spring 本地调度侧）的唯一一份自动配置（§0.3-6）。
 *
 * <p>两个 bean 的分工决定"谁来发现任务"：端口只负责注册与失败记录，{@link MydddJobScanner}
 * 负责把 {@code @MydddJob} 找出来喂给它。把扫描独立成 bean 而不是写进端口的构造，是因为端口
 * 要在契约测试里被直接 new（{@code JOB-C} 六条不经过容器）。
 */
@AutoConfiguration
@Conditional(MydddJobProviderCondition.class)
@EnableConfigurationProperties(MydddJobSpringProperties.class)
public class MydddJobSpringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JobPort.class)
    public SpringJobPort mydddJobPort(MydddJobSpringProperties properties) {
        properties.validate();
        CronTaskScheduler scheduler =
                new DefaultCronTaskScheduler(properties.getSpring().getPoolSize());
        return new SpringJobPort(scheduler);
    }

    @Bean
    public MydddJobScanner mydddJobScanner(ConfigurableListableBeanFactory beanFactory,
                                           JobPort mydddJobPort) {
        return new MydddJobScanner(beanFactory, mydddJobPort);
    }
}

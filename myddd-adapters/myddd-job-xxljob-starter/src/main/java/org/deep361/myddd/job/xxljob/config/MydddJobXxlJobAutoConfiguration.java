package org.deep361.myddd.job.xxljob.config;

import org.deep361.myddd.job.xxljob.DefaultXxlJobRuntime;
import org.deep361.myddd.job.xxljob.MydddXxlJobScanner;
import org.deep361.myddd.job.xxljob.XxlJobPort;
import org.deep361.myddd.job.xxljob.XxlJobRuntime;
import org.deep361.myddd.kernel.port.job.JobPort;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Job 能力（XXL-Job 侧）的唯一一份自动配置（§0.3-6）。
 *
 * <p>三个 bean 各自回答一个问题：{@link #mydddXxlJobRuntime} 回答"执行器归谁管"（生命周期由本模块自持，
 * 见 {@code 42c} 的 {@code DefaultXxlJobRuntime}），{@link #mydddJobPort} 回答"任务注册到哪"，
 * {@link #mydddJobScanner} 回答"任务从哪来"。拆成三个而不是合成一个，是因为三者各有让位需求：
 * 前两个要能让用户换掉，第三个不让（它注入的是<b>当时在场的那个</b> {@code JobPort} 与 {@code XxlJobRuntime}）。
 */
@AutoConfiguration
@Conditional(MydddJobXxlJobProviderCondition.class)
@EnableConfigurationProperties(MydddJobXxlJobProperties.class)
public class MydddJobXxlJobAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(XxlJobRuntime.class)
    public XxlJobRuntime mydddXxlJobRuntime(MydddJobXxlJobProperties properties) {
        properties.validate();
        MydddJobXxlJobProperties.Xxljob section = properties.getXxljob();
        return new DefaultXxlJobRuntime(
                section.getAdminAddresses(), section.getExecutorAppName(),
                section.getAccessToken(), section.getExecutorPort());
    }

    @Bean
    @ConditionalOnMissingBean(JobPort.class)
    public XxlJobPort mydddJobPort(XxlJobRuntime runtime) {
        return new XxlJobPort(runtime);
    }

    @Bean
    public MydddXxlJobScanner mydddJobScanner(ConfigurableListableBeanFactory beanFactory,
                                              JobPort mydddJobPort, XxlJobRuntime runtime) {
        return new MydddXxlJobScanner(beanFactory, mydddJobPort, runtime);
    }
}

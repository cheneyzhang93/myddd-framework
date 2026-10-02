package org.deep361.myddd.feign.autoconfigure;

import org.deep361.myddd.feign.error.MydddErrorDecoder;
import org.deep361.myddd.feign.trace.TracePropagationInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 出站调用装配：traceId 透传拦截器 + 远程错误体还原解码器。
 *
 * <p>两级开关：类上的 {@code myddd.feign.enabled} 是总闸（关掉后本类两个 bean 都不存在），
 * {@code myddd.feign.trace.enabled} 与 {@code myddd.feign.error-decoder.enabled} 各管一个能力。
 * 方法上的条件同时带总开关（{@code name = {"enabled", "…"}}）是刻意的冗余：单个 {@code @ConditionalOnProperty}
 * 要求列出的每个键都非 false，这样"只关掉一个能力"与"关掉整层"两种配法互不干扰，也便于将来的装配测试
 * 单独跑子开关而不必重复声明总开关。
 *
 * <p><b>两个 bean 的"可否被顶掉"是相反取向</b>，理由不同：
 * <ul>
 *   <li>{@link TracePropagationInterceptor} <b>不写</b> {@code @ConditionalOnMissingBean}：请求拦截器是
 *       多方共同贡献的形态（Feign 收集容器里全部 {@code RequestInterceptor}），业务自己加了拦截器不构成
 *       "不要让框架加它的"的意思——顶掉它等于悄悄丢掉链路。</li>
 *   <li>{@link MydddErrorDecoder} <b>写</b> {@code @ConditionalOnMissingBean(ErrorDecoder.class)}：Feign 每个
 *       客户端只认一个 {@code ErrorDecoder}，业务自己声明一个就是"这个位置归我"，两处并存时框架让位。</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnClass} 用字符串形式：两个 bean 类型都直接实现 feign 接口，feign 缺席
 * （业务排掉了 {@code spring-cloud-starter-openfeign}）时本装配整体静默失效，而不是在反射读取时
 * 抛 {@code NoClassDefFoundError}。
 */
@AutoConfiguration
@EnableConfigurationProperties(MydddFeignProperties.class)
@ConditionalOnClass(name = {"feign.RequestInterceptor", "feign.codec.ErrorDecoder"})
@ConditionalOnProperty(prefix = "myddd.feign", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddFeignAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "myddd.feign", name = {"enabled", "trace.enabled"},
            havingValue = "true", matchIfMissing = true)
    public TracePropagationInterceptor mydddTracePropagationInterceptor() {
        return new TracePropagationInterceptor();
    }

    /**
     * 优先复用容器里的 {@link JsonMapper}（与 web 层出口同一份配置），没有时自建一个。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入：本 starter 不依赖 web-starter，批处理/MQ 消费侧的
     * 应用完全可能没有 {@code JsonMapper} bean，直接注入会让这些应用启动失败。多个 {@code JsonMapper}
     * 且无 {@code @Primary} 时按 Spring 语义抛 {@code NoUniqueBeanDefinitionException}——那是业务自己的
     * 装配歧义，框架不去猜哪一个。
     */
    @Bean
    @ConditionalOnMissingBean(feign.codec.ErrorDecoder.class)
    @ConditionalOnProperty(prefix = "myddd.feign", name = {"enabled", "error-decoder.enabled"},
            havingValue = "true", matchIfMissing = true)
    public MydddErrorDecoder mydddErrorDecoder(ObjectProvider<JsonMapper> jsonMapper) {
        return new MydddErrorDecoder(jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }
}

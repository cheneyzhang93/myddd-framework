package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.web.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * traceId 链路边界装配。
 *
 * <p>注册为普通 {@code Filter} bean（而不是 {@code FilterRegistrationBean}）：Boot 会把任何 {@code Filter}
 * bean 自动注册到 {@code /*} 并按类上的 {@code @Order} 排序，少一层装配样板。
 */
@AutoConfiguration(after = MydddWebAutoConfiguration.class)
@ConditionalOnProperty(prefix = "myddd.web", name = {"enabled", "trace.enabled"},
        havingValue = "true", matchIfMissing = true)
public class MydddWebTraceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TraceIdFilter mydddTraceIdFilter() {
        return new TraceIdFilter();
    }
}

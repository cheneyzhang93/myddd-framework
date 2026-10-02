package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.web.openapi.OpenApiInfoCustomizer;
import org.deep361.myddd.web.openapi.ResponseEnvelopeCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * OpenAPI 文档装配：元信息（{@code myddd.web.openapi.*} 三个键）与统一响应信封的补齐。
 *
 * <p><b>开关语义</b>：{@code myddd.web.openapi.enabled=false} 只让框架不再贡献这两处定制，
 * {@code /v3/api-docs} 与 swagger-ui 仍由 springdoc 提供——那是三方键 {@code springdoc.api-docs.enabled}
 * 与 {@code springdoc.swagger-ui.*} 的地盘（§0.3-6 禁止我们重钉或代管）。它不是"关掉接口文档"的开关。
 *
 * <p>两个 bean 都不写 {@code @ConditionalOnMissingBean}：定制器是多方共同贡献的形态，业务的定制器与框架的
 * 定制器并存（与 {@code JsonMapperBuilderCustomizer} 的取向一致）。三者改动面互不相交，因此也不依赖回放顺序。
 */
@AutoConfiguration(after = MydddWebAutoConfiguration.class)
@ConditionalOnClass(name = "org.springdoc.core.customizers.GlobalOpenApiCustomizer")
@ConditionalOnProperty(prefix = "myddd.web", name = {"enabled", "openapi.enabled"},
        havingValue = "true", matchIfMissing = true)
public class MydddWebOpenApiAutoConfiguration {

    @Bean
    public OpenApiInfoCustomizer mydddOpenApiInfoCustomizer(MydddWebProperties properties) {
        return new OpenApiInfoCustomizer(properties);
    }

    @Bean
    public ResponseEnvelopeCustomizer mydddResponseEnvelopeCustomizer() {
        return new ResponseEnvelopeCustomizer();
    }
}

package org.deep361.myddd.web.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Web 适配层总装配锚点：绑定 {@link MydddWebProperties} 并承担总开关。
 *
 * <p>本类刻意不产出任何业务 bean——traceId 过滤器、JSON 约定、全局异常处理、i18n 渲染与 OpenAPI 分别由
 * {@code MydddWeb{Trace,Json,Error,I18n,OpenApi}AutoConfiguration} 提供，并统一以
 * {@code @AutoConfiguration(after = MydddWebAutoConfiguration.class)} 挂在它之后：这样
 * {@code myddd.web.enabled=false} 一处配置就能整体关掉适配层，而每个子能力还能各自单独关。
 */
@AutoConfiguration
@EnableConfigurationProperties(MydddWebProperties.class)
@ConditionalOnProperty(prefix = "myddd.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddWebAutoConfiguration {
}

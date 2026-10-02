package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.web.json.JsonConventions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * 全局 JSON 约定装配：只通过 Boot 的 customizer 通道叠加约定，不替换 {@code ObjectMapper} bean。
 *
 * <p>不写 {@code @ConditionalOnMissingBean}：customizer 是"多方共同贡献"的形态，业务的 customizer 与
 * 框架的约定应当共存（都生效），而不是二选一。
 */
@AutoConfiguration(after = MydddWebAutoConfiguration.class)
@ConditionalOnClass(JsonMapperBuilderCustomizer.class)
@ConditionalOnProperty(prefix = "myddd.web", name = {"enabled", "json.enabled"},
        havingValue = "true", matchIfMissing = true)
public class MydddWebJsonAutoConfiguration {

    @Bean
    public JsonMapperBuilderCustomizer mydddJsonConventions() {
        return JsonConventions::apply;
    }
}

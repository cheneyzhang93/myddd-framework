package org.deep361.myddd.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 约定通过 Boot 的 customizer 通道进入上下文里的 ObjectMapper，两条开关各自能切断这条通道。
 *
 * <p>断言一律走"可观测行为"而不是按类型查 bean：Boot 的 {@code JacksonAutoConfiguration} 自身也会贡献
 * {@code JsonMapperBuilderCustomizer}，按类型断言会把"框架约定缺席"误判成存在。判据选 fluent 访问器——
 * {@link MomentProbe} 没有 {@code get} 前缀，约定缺席时序列化成 {@code {}}。
 */
class MydddWebJsonAutoConfigurationTest {

    private static final String MOMENT_JSON = "{\"occurredAt\":\"2026-09-26T02:03:04Z\"}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                    MydddWebAutoConfiguration.class, MydddWebJsonAutoConfiguration.class));

    @Test
    void should_apply_framework_conventions_to_context_mapper() {
        this.runner.run(context -> assertThat(bean(context).writeValueAsString(moment()))
                .isEqualTo(MOMENT_JSON));
    }

    @Test
    void should_stop_contributing_conventions_when_json_switch_off() {
        this.runner.withPropertyValues("myddd.web.json.enabled=false")
                .run(context -> assertThat(bean(context).writeValueAsString(moment())).isEqualTo("{}"));
    }

    @Test
    void should_stop_contributing_conventions_when_master_switch_off() {
        this.runner.withPropertyValues("myddd.web.enabled=false")
                .run(context -> assertThat(bean(context).writeValueAsString(moment())).isEqualTo("{}"));
    }

    @Test
    void should_let_business_customizer_coexist_with_framework() {
        // 业务只叠加"根包装"这一条框架完全不碰的约定：两条约定是否都在，与 customizer 的先后顺序无关
        this.runner.withBean(JsonMapperBuilderCustomizer.class,
                        () -> builder -> builder.enable(SerializationFeature.WRAP_ROOT_VALUE))
                .run(context -> {
                    Map<String, Object> written = bean(context)
                            .readValue(bean(context).writeValueAsString(moment()),
                                    new TypeReference<Map<String, Object>>() {
                                    });

                    assertThat(written).hasSize(1);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> occurred = (Map<String, Object>) written.values().iterator().next();
                    assertThat(occurred).containsEntry("occurredAt", "2026-09-26T02:03:04Z");
                });
    }

    private static ObjectMapper bean(org.springframework.context.ApplicationContext context) {
        return context.getBean(ObjectMapper.class);
    }

    private static MomentProbe moment() {
        return new MomentProbe(Instant.parse("2026-09-26T02:03:04Z"));
    }

    /** 只有 fluent 访问器的探针：框架约定缺席时它序列化成 {@code {}}。 */
    static final class MomentProbe {

        private final Instant occurredAt;

        MomentProbe(Instant occurredAt) {
            this.occurredAt = occurredAt;
        }

        public Instant occurredAt() {
            return occurredAt;
        }
    }
}

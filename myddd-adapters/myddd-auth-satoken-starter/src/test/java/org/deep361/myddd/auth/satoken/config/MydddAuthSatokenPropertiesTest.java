package org.deep361.myddd.auth.satoken.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code myddd.auth.satoken.enabled} 的键面与绑定（{@code IQ} 组）：只有这一个键，且解析完全交 Boot。 */
class MydddAuthSatokenPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsOnly.class);

    /** IQ1：缺省为 true，且字段面恰好一个（这条是"不翻译 sa-token.*"那句纪律的机读形态）。 */
    @Test
    void should_default_to_enabled_and_declare_exactly_one_key() {
        this.runner.run(context -> {
            MydddAuthSatokenProperties properties = context.getBean(MydddAuthSatokenProperties.class);
            assertThat(properties.isEnabled()).isTrue();
        });
        Set<String> fields = Arrays.stream(MydddAuthSatokenProperties.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactly("enabled");
    }

    /** IQ2：四种"关掉"的写法都接得住，说明布尔解析没有被本类手写接管。 */
    @Test
    void should_bind_every_relaxed_false_spelling() {
        for (String spelling : new String[] {"false", "off", "no", "0"}) {
            this.runner.withPropertyValues("myddd.auth.satoken.enabled=" + spelling).run(context -> {
                MydddAuthSatokenProperties properties = context.getBean(MydddAuthSatokenProperties.class);
                assertThat(properties.isEnabled())
                        .as("%s 必须被宽松绑定接成 false", spelling)
                        .isFalse();
            });
        }
        this.runner.withPropertyValues("myddd.auth.satoken.enabled=true").run(context -> assertThat(
                context.getBean(MydddAuthSatokenProperties.class).isEnabled()).isTrue());
    }

    /** IQ3：prefix 逐字等于 myddd.auth.satoken（读注解对象，防"改了常量忘了注解"）。 */
    @Test
    void should_anchor_the_prefix_to_the_provider_namespace() {
        ConfigurationProperties annotation = MydddAuthSatokenProperties.class.getAnnotation(
                ConfigurationProperties.class);
        // 存在性先判：注解被删时 getAnnotation 给 null，而 assertThat(null).isEqualTo("...") 的读数
        // 分不清"注解没了"与"prefix 写错了"，这条与 Task 48 的 IQ5 同形。
        assertThat(annotation).as("@ConfigurationProperties 必须还在 MydddAuthSatokenProperties 上").isNotNull();
        assertThat(annotation.prefix()).isEqualTo("myddd.auth.satoken");
        assertThat(MydddAuthSatokenProperties.PREFIX).isEqualTo("myddd.auth.satoken");
    }

    /** 只绑属性、不做任何条件判断的测试配置：{@code enabled=false} 时真装配整体不解析（{@code AC2}）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MydddAuthSatokenProperties.class)
    static class PropsOnly {
    }
}

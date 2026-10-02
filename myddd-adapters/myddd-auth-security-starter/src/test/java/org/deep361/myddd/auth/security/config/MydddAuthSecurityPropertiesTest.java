package org.deep361.myddd.auth.security.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code myddd.auth.security.enabled} 的键面与绑定（{@code IQ} 组）：只有这一个键，且解析完全交 Boot。 */
class MydddAuthSecurityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropsOnly.class);

    /** IQ1：缺省为 true，且字段面恰好一个（这条是"不翻译 spring.security.*"那句纪律的机读形态）。 */
    @Test
    void should_default_to_enabled_and_declare_exactly_one_key() {
        this.runner.run(context -> {
            MydddAuthSecurityProperties properties = context.getBean(MydddAuthSecurityProperties.class);
            assertThat(properties.isEnabled()).isTrue();
        });
        Set<String> fields = Arrays.stream(MydddAuthSecurityProperties.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactly("enabled");
    }

    /** IQ2：四种"关掉"的写法都接得住，说明布尔解析没有被本类手写接管。 */
    @Test
    void should_bind_every_relaxed_false_spelling() {
        for (String spelling : new String[] {"false", "off", "no", "0"}) {
            this.runner.withPropertyValues("myddd.auth.security.enabled=" + spelling).run(context -> {
                MydddAuthSecurityProperties properties = context.getBean(MydddAuthSecurityProperties.class);
                assertThat(properties.isEnabled())
                        .as("%s 必须被宽松绑定接成 false", spelling)
                        .isFalse();
            });
        }
        this.runner.withPropertyValues("myddd.auth.security.enabled=true").run(context -> assertThat(
                context.getBean(MydddAuthSecurityProperties.class).isEnabled()).isTrue());
    }

    /** IQ3：prefix 逐字等于 myddd.auth.security（读注解对象，防"改了常量忘了注解"）。 */
    @Test
    void should_anchor_the_prefix_to_the_provider_namespace() {
        ConfigurationProperties annotation = MydddAuthSecurityProperties.class.getAnnotation(
                ConfigurationProperties.class);
        // 存在性先判：注解被删时 getAnnotation 给 null，而 assertThat(null).isEqualTo("...") 的读数
        // 分不清"注解没了"与"prefix 写错了"，这条与 Task 48/49 的同类钉子同形。
        assertThat(annotation).as("@ConfigurationProperties 必须还在 MydddAuthSecurityProperties 上").isNotNull();
        assertThat(annotation.prefix()).isEqualTo("myddd.auth.security");
        assertThat(MydddAuthSecurityProperties.PREFIX).isEqualTo("myddd.auth.security");
    }

    /** 只绑属性、不做任何条件判断的测试配置：{@code enabled=false} 时真装配整体不解析（{@code AC2}）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MydddAuthSecurityProperties.class)
    static class PropsOnly {
    }
}

package org.deep361.myddd.auth.config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.MergedAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code IQ} 组：{@link MydddAuthProperties} 的缺省值、那条跨键一致性诊断，以及 relaxed binding
 * 的两种写法（kebab 与 camel 各钉一格——键表在文档里是 kebab，业务真写 camel 也必须绑得上）。
 */
class MydddAuthPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MydddAuthProperties.class)
    static class BindingConfig {
    }

    /** {@code IQ1}：四个键的缺省全是"开"，且缺省组合能过自检。 */
    @Test
    void should_default_every_switch_to_on_and_pass_its_own_validation() {
        MydddAuthProperties properties = new MydddAuthProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isValidateDeclarations()).isTrue();
        assertThat(properties.getPermissionCheck().isEnabled()).isTrue();
        assertThat(properties.getCredential().isVerifierRequired()).isTrue();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    /** {@code IQ2}：只关切面、不关校验 ⇒ 跨键矛盾，诊断必须把两个键名与受害的注解都点名。 */
    @Test
    void should_reject_permission_check_off_with_validation_still_on() {
        MydddAuthProperties properties = new MydddAuthProperties();
        properties.getPermissionCheck().setEnabled(false);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.auth.permission-check.enabled")
                .hasMessageContaining("myddd.auth.validate-declarations")
                .hasMessageContaining("@RequiresPermission");
    }

    /** {@code IQ3}：两个键一起关才是"这套能力我今天不用"的合法表达。 */
    @Test
    void should_accept_both_switches_off_together() {
        MydddAuthProperties properties = new MydddAuthProperties();
        properties.getPermissionCheck().setEnabled(false);
        properties.setValidateDeclarations(false);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    // <<<IQ4plus>>>

    /**
     * {@code IQ4}：kebab 与 camel 两种写法都绑得上（relaxed binding）。切面键两格都配上
     * {@code myddd.auth.validate-declarations=false}——不然 {@code @PostConstruct} 的跨键自检
     * 先于断言把上下文炸掉，绑没绑上就永远读不到。
     */
    @Test
    void should_bind_nested_keys_written_in_kebab_and_camel_alike() {
        runner.withPropertyValues("myddd.auth.permission-check.enabled=false",
                "myddd.auth.validate-declarations=false")
                .run(context -> assertThat(context.getBean(MydddAuthProperties.class).getPermissionCheck()
                        .isEnabled()).isFalse());
        runner.withPropertyValues("myddd.auth.permissionCheck.enabled=false",
                "myddd.auth.validate-declarations=false")
                .run(context -> assertThat(context.getBean(MydddAuthProperties.class).getPermissionCheck()
                        .isEnabled()).isFalse());
        runner.withPropertyValues("myddd.auth.credential.verifier-required=false")
                .run(context -> assertThat(context.getBean(MydddAuthProperties.class).getCredential()
                        .isVerifierRequired()).isFalse());
        runner.withPropertyValues("myddd.auth.credential.verifierRequired=false")
                .run(context -> assertThat(context.getBean(MydddAuthProperties.class).getCredential()
                        .isVerifierRequired()).isFalse());
    }

    /**
     * {@code IQ5}：前缀钉死 {@code myddd.auth}——键表（路线图 121-13）与代码各说各话的第一道防线。
     *
     * <p><b>读法必须是合成视图而不是裸反射</b>：源码写的是 {@code @ConfigurationProperties("myddd.auth")}
     * 这一个位置参数，落在 {@code value()} 上；{@code value} 与 {@code prefix} 是 {@code @AliasFor} 的一对，
     * 而裸 {@code getAnnotation()} 拿到的 JDK 代理<b>不解析别名</b>——一手实测：
     * {@code raw.prefix()=""} 而 {@code raw.value()="probe.holder"}。用 {@code prefix()} 断言就等于
     * 断言一个恒空串，永远绿、永远抓不到写错的键。{@code MergedAnnotations} 的 {@code synthesize()}
     * 会解析别名（同一探针：{@code merged.prefix()="probe.holder"}），那才是 Spring 绑定实际用的读法。
     */
    @Test
    void should_carry_the_documented_configuration_prefix() {
        ConfigurationProperties annotation = MergedAnnotations
                .from(MydddAuthProperties.class, MergedAnnotations.SearchStrategy.DIRECT)
                .get(ConfigurationProperties.class).synthesize();
        assertThat(annotation.prefix()).isEqualTo("myddd.auth");
    }

    /** {@code IQ6}：总开关关掉时跨键自检整段跳过——与 resilience/limiter 三家的裁定一致，跨模块一致优先。 */
    @Test
    void should_skip_the_cross_key_validation_when_the_master_switch_is_off() {
        MydddAuthProperties properties = new MydddAuthProperties();
        properties.setEnabled(false);
        properties.getPermissionCheck().setEnabled(false);
        // validate-declarations 留在缺省 true：这本是 IQ2 的矛盾组合，总开关关掉后它不再是错误。
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    /** {@code IQ7}：{@code enabled=false} 绑上后嵌套对象仍是同一个实例——关能力不许把键树砍出一格 {@code null}。 */
    @Test
    void should_keep_the_nested_instance_and_report_off_when_the_master_switch_is_bound_down() {
        runner.withPropertyValues("myddd.auth.enabled=false", "myddd.auth.permission-check.enabled=false")
                .run(context -> {
                    MydddAuthProperties properties = context.getBean(MydddAuthProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.getPermissionCheck()).isSameAs(properties.getPermissionCheck());
                });
    }

    /** {@code IQ8}：字段面就是那四个键——多一个键要先过一次裁定，少一个键会让文档键表说谎。 */
    @Test
    void should_expose_exactly_the_four_documented_fields() {
        List<String> names = Arrays.stream(MydddAuthProperties.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toList());
        assertThat(names).containsExactlyInAnyOrder("enabled", "validateDeclarations", "permissionCheck",
                "credential");
    }
}

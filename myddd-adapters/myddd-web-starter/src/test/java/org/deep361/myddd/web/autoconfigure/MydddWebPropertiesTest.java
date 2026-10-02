package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code myddd.web.*} 的缺省值、绑定与启动期校验。
 */
class MydddWebPropertiesTest {

    @Test
    void should_ship_documented_defaults() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTrace().isEnabled()).isTrue();
        assertThat(properties.getJson().isEnabled()).isTrue();
        assertThat(properties.getI18n().isEnabled()).isTrue();
        assertThat(properties.getI18n().getDefaultLocale()).isEqualTo("zh-CN");
        assertThat(properties.getOpenapi().isEnabled()).isTrue();
        assertThat(properties.getOpenapi().getTitle()).isEqualTo("myddd 应用");
        assertThat(properties.getOpenapi().getDescription()).isEmpty();
        assertThat(properties.getOpenapi().getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void should_bind_every_documented_key() {
        MydddWebProperties properties = bind(Map.of(
                "myddd.web.enabled", false,
                "myddd.web.trace.enabled", false,
                "myddd.web.json.enabled", false,
                "myddd.web.i18n.enabled", false,
                "myddd.web.i18n.default-locale", "en-US",
                "myddd.web.openapi.enabled", false,
                "myddd.web.openapi.title", "订单中心",
                "myddd.web.openapi.description", "订单域对外 API",
                "myddd.web.openapi.version", "2.3.1"));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getTrace().isEnabled()).isFalse();
        assertThat(properties.getJson().isEnabled()).isFalse();
        assertThat(properties.getI18n().isEnabled()).isFalse();
        assertThat(properties.getI18n().defaultLocale()).isEqualTo(Locale.US);
        assertThat(properties.getOpenapi().isEnabled()).isFalse();
        assertThat(properties.getOpenapi().getTitle()).isEqualTo("订单中心");
        assertThat(properties.getOpenapi().getDescription()).isEqualTo("订单域对外 API");
        assertThat(properties.getOpenapi().getVersion()).isEqualTo("2.3.1");
    }

    @Test
    void should_parse_valid_default_locale_into_locale() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getI18n().setDefaultLocale("zh_TW");
        properties.validate();

        assertThat(properties.getI18n().defaultLocale()).isEqualTo(Locale.TRADITIONAL_CHINESE);
    }

    @Test
    void should_fail_startup_on_unparsable_default_locale_naming_the_key() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getI18n().setDefaultLocale("不是语言标签");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.web.i18n.default-locale")
                .hasMessageContaining("不是语言标签");
    }

    @Test
    void should_fail_startup_on_blank_default_locale_instead_of_falling_back() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getI18n().setDefaultLocale("   ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.web.i18n.default-locale");
    }

    @Test
    void should_reject_blank_openapi_title_when_openapi_enabled() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getOpenapi().setTitle("  ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.web.openapi.title");
    }

    @Test
    void should_skip_title_check_when_openapi_disabled() {
        MydddWebProperties properties = new MydddWebProperties();
        properties.getOpenapi().setEnabled(false);
        properties.getOpenapi().setTitle("");

        properties.validate();
        assertThat(properties.getOpenapi().isEnabled()).isFalse();
    }

    @Test
    void should_allow_nested_switches_off_without_touching_master_switch() {
        MydddWebProperties properties = bind(Map.of("myddd.web.trace.enabled", false));
        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTrace().isEnabled()).isFalse();
        assertThat(properties.getI18n().isEnabled()).isTrue();
    }

    @Test
    void should_report_an_absent_locale_as_a_named_property_error() {
        // YAML 里写了键却没给值（`default-locale:`）时，Spring 绑进来的是 null 而不是空串：
        // 这条路径必须照样说清楚是哪个属性，否则用户只看到一个裸 NPE。
        MydddWebProperties properties = new MydddWebProperties();
        properties.getI18n().setDefaultLocale(null);

        assertThatThrownBy(properties::validate).isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.web.i18n.default-locale");
    }

    /** 用 Spring 的 Binder 走真实的宽松绑定（kebab-case → 驼峰），不手工 set。 */
    private static MydddWebProperties bind(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return Binder.get(environment).bind("myddd.web", MydddWebProperties.class).get();
    }
}

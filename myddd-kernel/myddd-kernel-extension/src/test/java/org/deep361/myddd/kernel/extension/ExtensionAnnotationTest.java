package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionAnnotationTest {

    @Test
    void extension_point_should_be_runtime_visible_and_type_scoped() {
        assertThat(ExtensionPoint.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(ExtensionPoint.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);
        assertThat(ExtensionPoint.class.isAnnotationPresent(Documented.class)).isTrue();
    }

    @Test
    void extension_point_name_should_default_to_empty_string() throws Exception {
        assertThat(ExtensionPoint.class.getDeclaredMethod("value").getDefaultValue()).isEqualTo("");
    }

    @Test
    void extension_should_default_to_inferred_point_default_scenario_and_priority_100() throws Exception {
        assertThat(Extension.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Extension.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);
        assertThat(Extension.class.isAnnotationPresent(Documented.class)).isTrue();
        assertThat(Extension.class.getDeclaredMethod("point").getDefaultValue()).isEqualTo(void.class);
        assertThat(Extension.class.getDeclaredMethod("scenario").getDefaultValue()).isEqualTo("");
        assertThat(Extension.class.getDeclaredMethod("priority").getDefaultValue()).isEqualTo(100);
    }

    @Test
    void annotations_should_be_readable_from_fixture_types() {
        assertThat(ExtensionFixtures.RiskCheck.class.getAnnotation(ExtensionPoint.class)).isNotNull();
        assertThat(ExtensionFixtures.VipRiskCheck.class.getAnnotation(Extension.class).scenario()).isEqualTo("vip");
        assertThat(ExtensionFixtures.VipRiskCheck.class.getAnnotation(Extension.class).priority()).isEqualTo(10);
    }
}

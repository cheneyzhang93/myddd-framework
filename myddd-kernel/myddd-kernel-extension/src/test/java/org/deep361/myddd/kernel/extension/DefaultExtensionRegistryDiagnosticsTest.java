package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class DefaultExtensionRegistryDiagnosticsTest {

    @Test
    void should_expose_empty_diagnostics_for_fresh_registry() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder().build();

        assertThat(registry.size()).isZero();
        assertThat(registry.registeredExtensionPoints()).isEmpty();
        assertThat(registry.extensionsOf(ExtensionFixtures.RiskCheck.class)).isEmpty();
        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class)).isEmpty();
    }

    @Test
    void should_describe_registrations_scenario_major_then_priority() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThat(registry.size()).isEqualTo(4);
        assertThat(registry.registeredExtensionPoints())
                .containsExactly(ExtensionFixtures.RiskCheck.class, ExtensionFixtures.NotifyChannel.class);
        assertThat(registry.extensionsOf(ExtensionFixtures.RiskCheck.class))
                .extracting(ExtensionMeta::scenario, ExtensionMeta::priority, ExtensionMeta::implementation)
                .containsExactly(
                        tuple("", 100, ExtensionFixtures.DefaultRiskCheck.class),
                        tuple("vip", 10, ExtensionFixtures.VipRiskCheck.class),
                        tuple("vip", 200, ExtensionFixtures.BackupRiskCheck.class));
        assertThat(registry.extensionsOf(ExtensionFixtures.RiskCheck.class))
                .extracting(ExtensionMeta::point, ExtensionMeta::pointName)
                .containsOnly(tuple(ExtensionFixtures.RiskCheck.class, "RiskCheck"));
        assertThat(registry.extensionsOf(ExtensionFixtures.NotifyChannel.class))
                .extracting(ExtensionMeta::pointName, ExtensionMeta::scenario)
                .containsExactly(tuple("notify", "sms"));
    }

    @Test
    void should_list_all_scenarios_ordered_by_priority() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class))
                .extracting(extension -> extension.check("u1"))
                .containsExactly("vip:u1", "default:u1", "backup:u1");
    }

    @Test
    void should_fall_back_to_simple_class_name_when_point_name_is_blank() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.WhitespacePointCheck())
                .build();

        assertThat(registry.extensionsOf(ExtensionFixtures.WhitespacePoint.class))
                .extracting(ExtensionMeta::pointName)
                .containsExactly("WhitespacePoint");
    }

    @Test
    void should_order_same_priority_implementations_by_implementation_class_name() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "tie-a", 100, new ExtensionFixtures.VipRiskCheck())
                .register(ExtensionFixtures.RiskCheck.class, "tie-b", 100, new ExtensionFixtures.BackupRiskCheck())
                .build();

        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class))
                .extracting(extension -> extension.getClass().getName())
                .containsExactly(ExtensionFixtures.BackupRiskCheck.class.getName(),
                        ExtensionFixtures.VipRiskCheck.class.getName());
        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "tie-a"))
                .extracting(extension -> extension.getClass().getName())
                .containsExactly(ExtensionFixtures.VipRiskCheck.class.getName());
    }

    @Test
    void should_return_immutable_diagnostic_views() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThatThrownBy(() -> registry.registeredExtensionPoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.extensionsOf(ExtensionFixtures.RiskCheck.class).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "vip").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_return_immutable_views_for_empty_branches() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder().build();

        assertThatThrownBy(() -> registry.registeredExtensionPoints().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.extensionsOf(ExtensionFixtures.RiskCheck.class).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "vip").clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_null_extension_meta_components() {
        assertThatThrownBy(() -> new ExtensionMeta(null, "name", "", 100, Object.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
        assertThatThrownBy(() -> new ExtensionMeta(Object.class, null, "", 100, Object.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("pointName 不能为 null");
        assertThatThrownBy(() -> new ExtensionMeta(Object.class, "name", null, 100, Object.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("scenario 不能为 null");
        assertThatThrownBy(() -> new ExtensionMeta(Object.class, "name", "", 100, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("implementation 不能为 null");
    }
}

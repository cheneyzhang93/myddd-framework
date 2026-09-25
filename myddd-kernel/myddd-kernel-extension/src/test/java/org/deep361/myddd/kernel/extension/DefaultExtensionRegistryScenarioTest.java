package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultExtensionRegistryScenarioTest {

    @Test
    void should_fall_back_to_default_scenario_when_exact_scenario_absent() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, "svip"))
                .isInstanceOf(ExtensionFixtures.DefaultRiskCheck.class);
        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, "svip").check("u1"))
                .isEqualTo("default:u1");
    }

    @Test
    void should_prefer_exact_scenario_over_default_scenario() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, "vip"))
                .isInstanceOf(ExtensionFixtures.VipRiskCheck.class);
    }

    @Test
    void should_not_fall_back_when_default_scenario_requested_explicitly() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.VipRiskCheck())
                .build();

        assertThat(registry.findExtension(ExtensionFixtures.RiskCheck.class, "")).isEmpty();
        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "")).isEmpty();
    }

    @Test
    void should_fall_back_for_all_default_implementations_in_priority_order() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "", 300, new ExtensionFixtures.BackupRiskCheck())
                .register(ExtensionFixtures.RiskCheck.class, "", 100, new ExtensionFixtures.DefaultRiskCheck())
                .build();

        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "unknown"))
                .extracting(extension -> extension.check("u1"))
                .containsExactly("default:u1", "backup:u1");
    }

    @Test
    void should_fail_with_registered_scenarios_when_neither_exact_nor_default_exists() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.VipRiskCheck())
                .build();

        assertThatThrownBy(() -> registry.getExtension(ExtensionFixtures.RiskCheck.class, "svip"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("svip")
                .hasMessageContaining("已注册场景=[vip]");
    }

    @Test
    void should_fail_for_point_without_registrations_and_report_empty_scenarios() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder().build();

        assertThatThrownBy(() -> registry.getExtension(ExtensionFixtures.NotifyChannel.class, "sms"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("已注册场景=[]");
        assertThat(registry.findExtension(ExtensionFixtures.NotifyChannel.class, "sms")).isEmpty();
    }

    @Test
    void should_return_immutable_extension_list() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();
        List<ExtensionFixtures.RiskCheck> extensions =
                registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "vip");

        assertThatThrownBy(() -> extensions.add(new ExtensionFixtures.VipRiskCheck()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_null_scenario_on_lookup() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario");
        assertThatThrownBy(() -> registry.findExtension(ExtensionFixtures.RiskCheck.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario");
    }
}

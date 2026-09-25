package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultExtensionRegistryBuildTest {

    @Test
    void should_register_annotated_extension_under_inferred_point() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.DefaultRiskCheck())
                .build();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.registeredExtensionPoints()).containsExactly(ExtensionFixtures.RiskCheck.class);
        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, ""))
                .isInstanceOf(ExtensionFixtures.DefaultRiskCheck.class);
    }

    @Test
    void should_register_extension_with_explicit_point_and_scenario() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.SmsNotifyChannel())
                .build();

        assertThat(registry.extensionsOf(ExtensionFixtures.NotifyChannel.class))
                .containsExactly(new ExtensionMeta(ExtensionFixtures.NotifyChannel.class, "notify", "sms", 100,
                        ExtensionFixtures.SmsNotifyChannel.class));
        assertThat(registry.getExtensionList(ExtensionFixtures.NotifyChannel.class, "sms"))
                .extracting(ExtensionFixtures.NotifyChannel::channel)
                .containsExactly("sms");
    }

    @Test
    void should_register_multi_point_implementation_under_explicit_point_only() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.ExplicitPointCheck())
                .build();

        assertThat(registry.registeredExtensionPoints()).containsExactly(ExtensionFixtures.NotifyChannel.class);
        assertThat(registry.getExtension(ExtensionFixtures.NotifyChannel.class, "multi"))
                .isInstanceOf(ExtensionFixtures.ExplicitPointCheck.class);
    }

    @Test
    void should_pick_lowest_priority_value_within_same_scenario() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "vip", 200, new ExtensionFixtures.BackupRiskCheck())
                .register(ExtensionFixtures.RiskCheck.class, "vip", 10, new ExtensionFixtures.VipRiskCheck())
                .build();

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, "vip"))
                .isInstanceOf(ExtensionFixtures.VipRiskCheck.class);
        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "vip"))
                .extracting(extension -> extension.check("u1"))
                .containsExactly("vip:u1", "backup:u1");
    }

    @Test
    void should_reject_extension_without_annotation() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.UnannotatedRiskCheck()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@Extension")
                .hasMessage(ExtensionFixtures.UnannotatedRiskCheck.class.getName() + " 缺少 @Extension 注解");
    }

    @Test
    void should_reject_implementation_of_multiple_points_without_explicit_point() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.MultiPointCheck()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法推断扩展点")
                .hasMessage(ExtensionFixtures.MultiPointCheck.class.getName()
                        + " 无法推断扩展点：实现 2 个 @ExtensionPoint 接口，请在 @Extension( point = ... ) 中显式指定");
    }

    @Test
    void should_reject_implementation_without_any_extension_point_interface() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.NoPointCheck()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法推断扩展点")
                .hasMessage(ExtensionFixtures.NoPointCheck.class.getName()
                        + " 无法推断扩展点：实现 0 个 @ExtensionPoint 接口，请在 @Extension( point = ... ) 中显式指定");
    }

    @Test
    void should_infer_extension_point_from_super_interface() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.CompositeRiskCheckImpl())
                .build();

        assertThat(registry.registeredExtensionPoints()).containsExactly(ExtensionFixtures.RiskCheck.class);
        assertThat(registry.getExtension(ExtensionFixtures.RiskCheck.class, ""))
                .isInstanceOf(ExtensionFixtures.CompositeRiskCheckImpl.class);
        assertThat(registry.extensionsOf(ExtensionFixtures.RiskCheck.class))
                .containsExactly(new ExtensionMeta(ExtensionFixtures.RiskCheck.class, "RiskCheck", "", 100,
                        ExtensionFixtures.CompositeRiskCheckImpl.class));
    }

    @Test
    void should_reject_point_without_extension_point_annotation() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.PlainService.class, "", 100, new ExtensionFixtures.PlainServiceImpl()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@ExtensionPoint")
                .hasMessage(ExtensionFixtures.PlainService.class.getName() + " 缺少 @ExtensionPoint 注解，不能作为扩展点");
    }

    @Test
    void should_reject_point_that_is_not_interface() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.AnnotatedClass.class, "", 100, new ExtensionFixtures.AnnotatedClass()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是接口")
                .hasMessage(ExtensionFixtures.AnnotatedClass.class.getName() + " 不是接口，@ExtensionPoint 只能标注接口");
    }

    @Test
    void should_reject_extension_not_implementing_declared_point() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.NotifyChannel.class, "sms", 100, new ExtensionFixtures.DefaultRiskCheck()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未实现扩展点")
                .hasMessage(ExtensionFixtures.DefaultRiskCheck.class.getName() + " 未实现扩展点 "
                        + ExtensionFixtures.NotifyChannel.class.getName());
    }

    @Test
    void should_reject_negative_priority() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "", -1, new ExtensionFixtures.DefaultRiskCheck()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priority")
                .hasMessage("priority 不能为负数：-1");
    }

    @Test
    void should_reject_null_scenario_and_null_extension() {
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, null, 100, new ExtensionFixtures.DefaultRiskCheck()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario")
                .hasMessage("scenario 不能为 null，默认场景请传空字符串");
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "", 100, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("扩展实现不能为 null")
                .hasMessage("扩展实现不能为 null");
        assertThatThrownBy(() -> DefaultExtensionRegistry.builder().register((Object) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("扩展实现不能为 null")
                .hasMessage("扩展实现不能为 null");
    }

    @Test
    void should_reject_null_scenario_on_lookup() {
        DefaultExtensionRegistry registry = ExtensionFixtures.standardRegistry();

        assertThatThrownBy(() -> registry.getExtension(ExtensionFixtures.RiskCheck.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scenario")
                .hasMessage("scenario 不能为 null，默认场景请传空字符串");
        assertThatThrownBy(() -> registry.getExtensionList(ExtensionFixtures.RiskCheck.class, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("scenario 不能为 null，默认场景请传空字符串");
    }

    @Test
    void should_reject_same_scenario_with_same_priority_at_build() {
        DefaultExtensionRegistry.Builder builder = DefaultExtensionRegistry.builder()
                .register(ExtensionFixtures.RiskCheck.class, "vip", 10, new ExtensionFixtures.VipRiskCheck())
                .register(ExtensionFixtures.RiskCheck.class, "vip", 10, new ExtensionFixtures.BackupRiskCheck());

        String expected = "扩展点 " + ExtensionFixtures.RiskCheck.class.getName() + " 场景 \"vip\" 存在同优先级(10)的多个实现："
                + ExtensionFixtures.VipRiskCheck.class.getName() + ", " + ExtensionFixtures.BackupRiskCheck.class.getName()
                + "，请调整 priority 或改用不同场景";

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vip")
                .hasMessageContaining("10")
                .hasMessage(expected);
        // 失败的构建不破坏构建器状态：重复构建得到同样的失败结果
        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expected);
    }

    @Test
    void should_not_share_state_between_registries() {
        DefaultExtensionRegistry.Builder builder = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.DefaultRiskCheck());
        DefaultExtensionRegistry first = builder.build();
        builder.register(new ExtensionFixtures.VipRiskCheck());
        DefaultExtensionRegistry second = builder.build();

        assertThat(first.size()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
        assertThat(first).isNotSameAs(second);
        // 先构建的注册表是冻结快照，不受构建器后续登记影响
        assertThat(first.getExtensionList(ExtensionFixtures.RiskCheck.class))
                .extracting(extension -> extension.getClass().getName())
                .containsExactly(ExtensionFixtures.DefaultRiskCheck.class.getName());
        assertThat(second.getExtensionList(ExtensionFixtures.RiskCheck.class))
                .extracting(extension -> extension.getClass().getName())
                .containsExactly(ExtensionFixtures.VipRiskCheck.class.getName(),
                        ExtensionFixtures.DefaultRiskCheck.class.getName());
    }

    @Test
    void should_keep_builder_clean_and_usable_after_rejected_registration() {
        DefaultExtensionRegistry.Builder builder = DefaultExtensionRegistry.builder();

        assertThatThrownBy(() -> builder.register(ExtensionFixtures.PlainService.class, "", 100,
                new ExtensionFixtures.PlainServiceImpl()))
                .isInstanceOf(IllegalArgumentException.class);

        // 非法登记不留痕：构建器仍为空且可继续正常登记
        assertThat(builder.build().size()).isZero();
        assertThat(builder.register(new ExtensionFixtures.DefaultRiskCheck()).build().size()).isEqualTo(1);
    }

    @Test
    void should_report_registered_scenarios_when_nothing_matches() {
        DefaultExtensionRegistry registry = DefaultExtensionRegistry.builder()
                .register(new ExtensionFixtures.VipRiskCheck())
                .build();

        assertThatThrownBy(() -> registry.getExtension(ExtensionFixtures.RiskCheck.class, "unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("point=" + ExtensionFixtures.RiskCheck.class.getName())
                .hasMessageContaining("scenario=\"unknown\"")
                .hasMessageContaining("已注册场景=[vip]");
        assertThat(registry.findExtension(ExtensionFixtures.RiskCheck.class, "unknown")).isEmpty();
        assertThat(registry.getExtensionList(ExtensionFixtures.RiskCheck.class, "unknown")).isEmpty();
        assertThat(registry.findExtension(ExtensionFixtures.RiskCheck.class, "vip"))
                .containsInstanceOf(ExtensionFixtures.VipRiskCheck.class);
        // 完全未登记的扩展点：报错并明确"已注册场景为空"
        assertThatThrownBy(() -> registry.getExtension(ExtensionFixtures.NotifyChannel.class, "sms"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("point=" + ExtensionFixtures.NotifyChannel.class.getName())
                .hasMessageContaining("已注册场景=[]");
    }
}

package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查找入口的扩展点参数校验：五个入口共用 {@code requireExtensionPoint}，非法扩展点必须一致快速失败。
 *
 * <p>登记路径的同类校验见 {@link DefaultExtensionRegistryBuildTest}。
 */
class DefaultExtensionRegistryLookupTest {

    private static final DefaultExtensionRegistry REGISTRY = ExtensionFixtures.standardRegistry();

    @Test
    void should_reject_non_interface_point_on_every_lookup_entry() {
        String expected = Object.class.getName() + " 不是接口，@ExtensionPoint 只能标注接口";

        assertThatThrownBy(() -> REGISTRY.getExtension(Object.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.findExtension(Object.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.getExtensionList(Object.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.getExtensionList(Object.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.extensionsOf(Object.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        // 已标注 @ExtensionPoint 的类同样非法：接口校验优先于注解校验
        assertThatThrownBy(() -> REGISTRY.extensionsOf(ExtensionFixtures.AnnotatedClass.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(ExtensionFixtures.AnnotatedClass.class.getName() + " 不是接口，@ExtensionPoint 只能标注接口");
    }

    @Test
    void should_reject_unannotated_interface_point_on_every_lookup_entry() {
        String expected = ExtensionFixtures.PlainService.class.getName() + " 缺少 @ExtensionPoint 注解，不能作为扩展点";

        assertThatThrownBy(() -> REGISTRY.getExtension(ExtensionFixtures.PlainService.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.findExtension(ExtensionFixtures.PlainService.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.getExtensionList(ExtensionFixtures.PlainService.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.getExtensionList(ExtensionFixtures.PlainService.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
        assertThatThrownBy(() -> REGISTRY.extensionsOf(ExtensionFixtures.PlainService.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
    }

    @Test
    void should_reject_null_point_on_every_lookup_entry() {
        assertThatThrownBy(() -> REGISTRY.getExtension(null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
        assertThatThrownBy(() -> REGISTRY.findExtension(null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
        assertThatThrownBy(() -> REGISTRY.getExtensionList(null, ""))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
        assertThatThrownBy(() -> REGISTRY.getExtensionList(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
        assertThatThrownBy(() -> REGISTRY.extensionsOf(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("point 不能为 null");
    }
}

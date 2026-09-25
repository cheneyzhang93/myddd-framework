package org.deep361.myddd.kernel.extension;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ExtensionRegistry} 的 API 形状契约：只暴露约定的 7 个方法，任何新增（含 default）都视为契约变更。 */
class ExtensionRegistryContractTest {

    @Test
    void should_declare_exactly_the_seven_contract_methods() {
        assertThat(ExtensionRegistry.class.getDeclaredMethods())
                // JaCoCo 会给带 default 方法的接口注入合成的 $jacocoInit；default 方法本身不过滤，新增即失败
                .filteredOn(method -> !method.isSynthetic())
                .extracting(ExtensionRegistryContractTest::signature)
                .containsExactlyInAnyOrder(
                        "getExtension(Class, String)",
                        "findExtension(Class, String)",
                        "getExtensionList(Class, String)",
                        "getExtensionList(Class)",
                        "registeredExtensionPoints()",
                        "extensionsOf(Class)",
                        "size()");
    }

    /** 签名取"default 前缀（仅当方法为 default）+ 方法名 + 参数类型简单名"：重载与抽象→default 的修饰符变化都能区分。 */
    private static String signature(Method method) {
        String modifier = method.isDefault() ? "default " : "";
        return modifier + method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(", ")) + ")";
    }
}

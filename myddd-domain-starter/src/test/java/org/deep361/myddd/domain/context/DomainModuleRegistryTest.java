package org.deep361.myddd.domain.context;

import org.deep361.myddd.domain.fixture.multi.core.BillingPolicy;
import org.deep361.myddd.domain.fixture.single.OrderDomainModule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModuleRegistryTest {

    static class Source {
    }

    private static DomainModule module(String name, String scanPackage) {
        return new DomainModule(name, List.of(scanPackage), Source.class);
    }

    private static DomainModuleRegistry standardRegistry() {
        return DomainModuleRegistry.of(List.of(
                module("order", "org.deep361.myddd.domain.fixture.single"),
                module("billing", "org.deep361.myddd.domain.fixture.multi")));
    }

    @Test
    void should_keep_registration_order_and_expose_names() {
        DomainModuleRegistry registry = standardRegistry();

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.isEmpty()).isFalse();
        assertThat(registry.names()).containsExactly("order", "billing");
        assertThat(registry.modules()).extracting(DomainModule::name).containsExactly("order", "billing");
    }

    @Test
    void should_reject_duplicate_module_names() {
        assertThatThrownBy(() -> DomainModuleRegistry.of(List.of(
                module("order", "org.deep361.sample.order"),
                module("order", "org.deep361.sample.other"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领域模块名重复：order");
    }

    @Test
    void get_should_fail_fast_on_unknown_name() {
        DomainModuleRegistry registry = standardRegistry();

        assertThatThrownBy(() -> registry.get("nope"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("未注册的领域模块：nope")
                .hasMessageContaining("order");
    }

    @Test
    void find_should_return_empty_on_unknown_name() {
        DomainModuleRegistry registry = standardRegistry();

        assertThat(registry.find("billing")).map(DomainModule::name).contains("billing");
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    void module_of_should_match_by_scan_package_including_subpackages() {
        DomainModuleRegistry registry = standardRegistry();

        assertThat(registry.moduleOf(OrderDomainModule.class)).map(DomainModule::name).contains("order");
        assertThat(registry.moduleOf(BillingPolicy.class)).map(DomainModule::name).contains("billing");
        assertThat(registry.moduleOf(DomainModuleRegistryTest.class)).isEmpty();
    }

    @Test
    void should_allow_empty_registry() {
        DomainModuleRegistry registry = DomainModuleRegistry.of(List.of());

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.size()).isZero();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void should_reject_null_input() {
        assertThatThrownBy(() -> DomainModuleRegistry.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("领域模块集合");
        assertThatThrownBy(() -> DomainModuleRegistry.of(java.util.Arrays.asList((DomainModule) null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("领域模块不能为 null");
    }
}

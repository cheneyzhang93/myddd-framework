package org.deep361.myddd.domain.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModuleTest {

    static class Source {
    }

    private static final List<String> PACKAGES = List.of("org.deep361.sample.order.core");

    @Test
    void should_carry_name_scan_packages_and_source() {
        DomainModule module = new DomainModule("order", PACKAGES, Source.class);

        assertThat(module.name()).isEqualTo("order");
        assertThat(module.scanPackages()).containsExactly("org.deep361.sample.order.core");
        assertThat(module.source()).isSameAs(Source.class);
    }

    @Test
    void should_defensively_copy_scan_packages() {
        List<String> mutable = new ArrayList<>(PACKAGES);
        DomainModule module = new DomainModule("order", mutable, Source.class);

        mutable.add("org.deep361.sample.order.infra");

        assertThat(module.scanPackages()).containsExactly("org.deep361.sample.order.core");
        assertThatThrownBy(() -> module.scanPackages().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void should_reject_blank_name() {
        assertThatThrownBy(() -> new DomainModule("  ", PACKAGES, Source.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模块名");
        assertThatThrownBy(() -> new DomainModule(null, PACKAGES, Source.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模块名");
    }

    @Test
    void should_reject_empty_or_blank_scan_packages() {
        assertThatThrownBy(() -> new DomainModule("order", List.of(), Source.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扫描包");
        assertThatThrownBy(() -> new DomainModule("order", List.of(" "), Source.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("扫描包");
    }

    @Test
    void should_reject_null_scan_packages_and_source() {
        assertThatThrownBy(() -> new DomainModule("order", null, Source.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("扫描包");
        assertThatThrownBy(() -> new DomainModule("order", PACKAGES, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("来源类");
    }

    @Test
    void covers_should_match_exact_package_and_subpackages_only() {
        DomainModule module = new DomainModule("order", List.of("org.deep361.sample.order"), Source.class);

        assertThat(module.covers("org.deep361.sample.order")).isTrue();
        assertThat(module.covers("org.deep361.sample.order.internal")).isTrue();
        assertThat(module.covers("org.deep361.sample")).isFalse();
        assertThat(module.covers("org.deep361.sample.ordering")).isFalse();
        assertThat(module.covers(DomainModuleTest.class)).isFalse();
        assertThat(new DomainModule("ctx", List.of("org.deep361.myddd.domain.context"), Source.class)
                .covers(DomainModuleTest.class)).isTrue();
    }
}

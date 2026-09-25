package org.deep361.myddd.domain.context;

import org.deep361.myddd.domain.fixture.composed.ComposedModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainModuleScannerTest {

    private static final String SINGLE = "org.deep361.myddd.domain.fixture.single";
    private static final String MULTI = "org.deep361.myddd.domain.fixture.multi";

    @Test
    void should_discover_modules_recursively_under_base_package() {
        DomainModuleScanner scanner = new DomainModuleScanner(List.of(SINGLE));

        List<DomainModule> modules = scanner.scan();

        assertThat(modules).extracting(DomainModule::name).containsExactly("order", "order-sub");
        assertThat(scanner.basePackages()).containsExactly(SINGLE);
        for (DomainModule module : modules) {
            // 未声明 scanPackages 时回退到被标注类所在包
            assertThat(module.scanPackages()).containsExactly(module.name().equals("order")
                    ? SINGLE
                    : SINGLE + ".sub");
        }
    }

    @Test
    void should_normalize_declared_scan_packages() {
        List<DomainModule> modules = new DomainModuleScanner(List.of(MULTI)).scan();

        assertThat(modules).hasSize(1);
        assertThat(modules.get(0).name()).isEqualTo("billing");
        assertThat(modules.get(0).scanPackages())
                .containsExactly(MULTI + ".core", MULTI + ".infra"); // 声明的重复项被去掉、顺序被字典序归一
    }

    @Test
    void should_return_empty_list_when_no_domain_annotation_present() {
        assertThat(new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.plain")).scan())
                .isEmpty();
    }

    @Test
    void should_ignore_types_without_domain_even_if_they_are_components() {
        assertThat(new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.component")).scan())
                .isEmpty();
    }

    @Test
    void should_discover_module_via_composed_annotation_and_skip_the_declaration_itself() {
        List<DomainModule> modules =
                new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.composed")).scan();

        assertThat(modules).hasSize(1);
        assertThat(modules.get(0).name()).isEqualTo("composed");
        assertThat(modules.get(0).source()).isEqualTo(ComposedModule.class);
        assertThat(modules.get(0).scanPackages())
                .containsExactly("org.deep361.myddd.domain.fixture.composed");
    }

    @Test
    void should_reject_domain_on_interface() {
        DomainModuleScanner scanner = new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.badiface"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BadInterfaceModule")
                .hasMessageContaining("不能标注在接口上");
    }

    @Test
    void should_reject_domain_on_abstract_class() {
        DomainModuleScanner scanner =
                new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.badabstract"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BadAbstractModule")
                .hasMessageContaining("不能标注在抽象类上");
    }

    @Test
    void should_reject_domain_on_non_static_inner_class() {
        DomainModuleScanner scanner = new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.badinner"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BadInnerModule")
                .hasMessageContaining("不能标注在非静态内部类上");
    }

    @Test
    void should_sort_modules_by_name_across_base_packages() {
        List<DomainModule> modules = new DomainModuleScanner(List.of(SINGLE, MULTI)).scan();

        assertThat(modules).extracting(DomainModule::name)
                .containsExactly("billing", "order", "order-sub");
    }

    @Test
    void should_reject_blank_module_name() {
        DomainModuleScanner scanner = new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.blank"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BlankNameModule")
                .hasMessageContaining("模块名为空白");
    }

    @Test
    void should_reject_unicode_whitespace_module_name() {
        DomainModuleScanner scanner =
                new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.blankunicode"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnicodeBlankNameModule")
                .hasMessageContaining("模块名为空白");
    }

    @Test
    void should_reject_blank_declared_scan_package() {
        DomainModuleScanner scanner =
                new DomainModuleScanner(List.of("org.deep361.myddd.domain.fixture.badpackage"));

        assertThatThrownBy(scanner::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BlankPackageModule")
                .hasMessageContaining("scanPackages");
    }

    @Test
    void should_reject_null_or_blank_base_packages() {
        assertThatThrownBy(() -> new DomainModuleScanner(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("basePackages");
        assertThatThrownBy(() -> new DomainModuleScanner(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basePackages");
        assertThatThrownBy(() -> new DomainModuleScanner(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basePackages");
    }
}

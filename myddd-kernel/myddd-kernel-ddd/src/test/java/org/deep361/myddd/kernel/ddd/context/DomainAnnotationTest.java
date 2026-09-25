package org.deep361.myddd.kernel.ddd.context;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class DomainAnnotationTest {

    @Domain("order")
    static class OrderDomain {
    }

    @Domain(value = "billing", scanPackages = {"org.deep361.sample.billing", "org.deep361.sample.shared"})
    static class BillingDomain {
    }

    @Test
    void should_expose_declared_module_name() {
        assertThat(OrderDomain.class.getAnnotation(Domain.class).value()).isEqualTo("order");
        assertThat(BillingDomain.class.getAnnotation(Domain.class).value()).isEqualTo("billing");
    }

    @Test
    void scan_packages_should_default_to_empty_array() {
        assertThat(OrderDomain.class.getAnnotation(Domain.class).scanPackages()).isEmpty();
        assertThat(BillingDomain.class.getAnnotation(Domain.class).scanPackages())
                .containsExactly("org.deep361.sample.billing", "org.deep361.sample.shared");
    }

    @Test
    void should_be_retained_at_runtime_and_target_types() {
        assertThat(Domain.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Domain.class.getAnnotation(Target.class).value()).containsExactly(ElementType.TYPE);
    }
}

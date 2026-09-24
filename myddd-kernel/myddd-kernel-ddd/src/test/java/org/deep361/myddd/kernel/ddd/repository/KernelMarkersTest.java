package org.deep361.myddd.kernel.ddd.repository;

import org.deep361.myddd.kernel.ddd.service.DomainService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Repository} 与 {@link DomainService} 的消费者契约测试。
 *
 * <p>这两个是纯标记接口，无运行期行为；本测试的价值在<b>编译期</b>——{@code implements Repository<Order, Long>}
 * 一旦标记被改成抽象类、被加上抽象方法，本类立即编译失败（真实使用方的报错点就在此处），
 * 比任何运行期断言更早暴露破坏性变更。运行期断言承载编译器观测不到的契约：标记必须保持为接口且无任何成员
 * （抽象方法或 default 方法一旦加入即红）。
 *
 * <p>编译器观测不到的改动由反射断言兜底：类型参数顺序。无成员、无边界的标记接口上
 * {@code Repository<Order, Long>} 与 {@code Repository<Long, Order>} 的 {@code implements} 子句互相兼容，
 * 对调 T 与 ID 不产生任何编译错误，故"聚合在前、标识在后"的声明形状由
 * {@link #repository_should_declare_aggregate_first_then_id()} 钉住。
 */
class KernelMarkersTest {

    record Order(Long id) {
    }

    static class InMemoryOrderRepository implements Repository<Order, Long> {
    }

    static class PricingDomainService implements DomainService {
    }

    @Test
    void repository_should_be_implementable_with_aggregate_and_id_type_parameters() {
        Repository<Order, Long> repository = new InMemoryOrderRepository();

        assertThat(repository.getClass().getInterfaces()).containsExactly(Repository.class);
    }

    @Test
    void domain_service_should_be_implementable_as_a_marker() {
        DomainService service = new PricingDomainService();

        assertThat(service.getClass().getInterfaces()).containsExactly(DomainService.class);
    }

    @Test
    void markers_should_be_interfaces_without_members() {
        assertThat(Repository.class.isInterface()).isTrue();
        assertThat(DomainService.class.isInterface()).isTrue();
        assertThat(Repository.class.getDeclaredMethods()).isEmpty();
        assertThat(DomainService.class.getDeclaredMethods()).isEmpty();
    }

    @Test
    void repository_should_declare_aggregate_first_then_id() {
        List<String> typeParameterNames = Arrays.stream(Repository.class.getTypeParameters())
                .map(TypeVariable::getName)
                .toList();

        // 使用方按 @param <T> 聚合根 / @param <ID> 标识 的顺序书写 extends Repository<Order, Long>；
        // 该顺序因无成员、无边界而对编译器不可见，只能在此钉住。
        assertThat(typeParameterNames).containsExactly("T", "ID");
    }
}

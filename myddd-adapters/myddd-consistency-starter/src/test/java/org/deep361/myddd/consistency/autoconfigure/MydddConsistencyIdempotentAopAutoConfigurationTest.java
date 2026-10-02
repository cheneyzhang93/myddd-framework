package org.deep361.myddd.consistency.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.aspectj.lang.Aspects;
import org.deep361.myddd.consistency.idempotent.IdempotentAspect;
import org.deep361.myddd.consistency.lock.DistributedLock;
import org.deep361.myddd.consistency.lock.DistributedLockAspect;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.Idempotent;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式幂等的装配面：{@link MydddConsistencyIdempotentAopAutoConfiguration} 在不在、
 * 三个开关各自管到哪一层为止。
 *
 * <p>与 {@code IdempotentAspectTest} 的分工：那边手工装配上下文，钉<b>切面算错了什么</b>（键、token、
 * 时长、顺序、透传）；这里只钉<b>切面在不在容器里、注解是否真的穿过它到了端口</b>。
 *
 * <p><b>本类的重心是"开关的边界"</b>：{@code myddd.consistency.enabled} 关掉整个一致性适配层（两个切面都没了），
 * 而 {@code *.enabled} 只关掉框架的实现分支——<b>切面不受它管辖</b>。这条不对称反过来读才是要钉的东西：
 * 若切面跟着 {@code idempotent.enabled} 一起消失，业务得到的是"@Idempotent 标着、一次都没拦"的静默失效，
 * 那是幂等最坏的失效形态。下面两条用例（{@code should_keep_the_aspect...} 与
 * {@code should_not_let_one_capability_switch_...}）把这条裁定钉在容器上而不是注释里。
 */
class MydddConsistencyIdempotentAopAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddConsistencyIdempotentAopAutoConfiguration.class));

    /** 记录键的端口：装配证据要落在"调用真的穿过切面到了端口"上。 */
    private static final class RecordingPort implements IdempotentPort {

        private final List<String> keys = new ArrayList<>();

        @Override
        public boolean tryAcquire(String key, String token, Duration ttl) {
            this.keys.add(key);
            return true;
        }

        @Override
        public void release(String key, String token) {
            this.keys.add("release:" + key);
        }
    }

    static class OrderService {

        @Idempotent(key = "'order:' + #orderNo")
        public String submit(String orderNo) {
            return "submitted:" + orderNo;
        }

        @DistributedLock(key = "'order:' + #orderNo")
        public String shipWithLock(String orderNo) {
            return "shipped:" + orderNo;
        }
    }

    /** 代理开关由应用自己声明（框架不加 {@code @EnableAspectJAutoProxy}），所以测试上下文带上它。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class ProxyingConfiguration {

        @Bean
        OrderService orderService() {
            return new OrderService();
        }
    }

    @Test
    void should_assemble_the_aspect_by_default() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotentAspect.class);
        });
    }

    @Test
    void should_back_off_when_aspectj_is_not_on_the_classpath() {
        // 条件里那一句不是装饰：没有 AspectJ 就没有代理基础设施，切面 bean 留着只会让人以为注解生效了。
        runner.withClassLoader(new FilteredClassLoader(Aspects.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotentAspect.class);
        });
    }

    @Test
    void should_back_off_when_the_capability_group_switch_is_off() {
        runner.withPropertyValues("myddd.consistency.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdempotentAspect.class);
        });
    }

    @Test
    void should_still_assemble_when_the_switch_is_written_explicitly_true() {
        // matchIfMissing 的另一半：显式写 true 也得装配。缺了这条，"缺省开"可以被写成"缺省关"而没人发现。
        runner.withPropertyValues("myddd.consistency.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IdempotentAspect.class);
        });
    }

    @Test
    void should_keep_the_aspect_assembled_when_the_framework_store_implementation_is_off() {
        // idempotent.enabled=false 只管存储那一支（见 MydddConsistencyIdempotentAutoConfigurationTest）。
        // 这里钉另一半：切面还在，于是缺端口是"调用时报一句指名开关的话"，不是"注解静默不生效"。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddConsistencyIdempotentAopAutoConfiguration.class))
                .withPropertyValues("myddd.consistency.idempotent.enabled=false")
                .withUserConfiguration(ProxyingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                    assertThat(context).doesNotHaveBean(IdempotentPort.class);
                    assertThatThrownBy(() -> context.getBean(OrderService.class).submit("o-1"))
                            .isInstanceOf(FrameworkException.class)
                            .hasMessageContaining("IdempotentPort")
                            .hasMessageContaining("myddd.consistency.idempotent.enabled")
                            // 那句话还得说清"关掉开关不等于注解变成放行"，否则业务照着前半句去关开关，
                            // 得到的仍然是那个静默失效——只是这一次是它自己选的。
                            .hasMessageContaining("变成放行");
                });
    }

    @Test
    void should_keep_both_aspects_when_the_idempotent_implementation_switch_is_off() {
        // 两个声明面共用一份配置，所以"关幂等的实现"必须既不关幂等切面、也不关锁切面：把两条链放进
        // 同一个容器里跑一遍，才知道条件是不是真的各自独立（合成一个装配类、或把切面挂到 *.enabled 上都会红）。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MydddConsistencyIdempotentAopAutoConfiguration.class,
                        MydddConsistencyLockAopAutoConfiguration.class))
                .withPropertyValues("myddd.consistency.idempotent.enabled=false")
                .withUserConfiguration(ProxyingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedLockAspect.class);
                    assertThat(context).hasSingleBean(IdempotentAspect.class);
                });
    }

    @Test
    void should_run_the_annotation_through_the_idempotent_port_the_application_provided() {
        RecordingPort port = new RecordingPort();

        runner.withBean(IdempotentPort.class, () -> port)
                .withUserConfiguration(ProxyingConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OrderService.class).submit("o-7")).isEqualTo("submitted:o-7");
                    // 键原样到达业务自带的端口：中间没有被框架的实现换掉，也没有被切面二次加工。
                    assertThat(port.keys).containsExactly("order:o-7");
                });
    }
}

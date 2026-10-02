package org.deep361.myddd.job.spring.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code myddd.job.*} 在 spring provider 侧的绑定与校验（{@code IQ} 组）。 */
class MydddJobSpringPropertiesTest {

    /** IQ1：缺省值，并把厂商缺省摆在同一条用例里对照。 */
    @Test
    void should_default_to_enabled_unselected_provider_and_a_pool_of_our_own_size() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        ThreadPoolTaskScheduler vendorDefault = new ThreadPoolTaskScheduler();
        try {
            assertTrue(properties.isEnabled());
            assertNull(properties.getProvider(),
                    "provider 不该有缺省值：缺省即'未写'，歧义判定归 JobProviderSelection");
            assertEquals(4, properties.getSpring().getPoolSize());
            assertEquals(1, vendorDefault.getPoolSize(),
                    "厂商缺省是 1（实测 S1-P1b），我们的 4 必须是显式设出来的");
        } finally {
            // 不关就等于在测试里留一个"我们从不使用厂商生命周期"的坏示范（IQ1 的 shutdown 不是模板）。
            vendorDefault.shutdown();
        }
    }

    /** IQ2：池大小为 0 时快速失败，并且消息里必须点出键名。 */
    @Test
    void should_reject_a_pool_size_of_zero_with_the_offending_key() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        properties.getSpring().setPoolSize(0);
        FrameworkException failure = assertThrows(FrameworkException.class, properties::validate);
        assertThat(failure.getMessage()).contains("myddd.job.spring.pool-size");
    }

    /** IQ3：判据是"小于 1"，不是"等于 0"。 */
    @Test
    void should_reject_a_negative_pool_size_too() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        properties.getSpring().setPoolSize(-1);
        FrameworkException failure = assertThrows(FrameworkException.class, properties::validate);
        assertThat(failure.getMessage()).contains("-1", "myddd.job.spring.pool-size");
    }

    /**
     * IQ4：厂商那句报错没有键名——这就是校验必须提前的全部理由（S1-P5 原文）。
     *
     * <p>实测读数（Spring 7.1 / Boot 4.1）：拒绝发生在 {@code setPoolSize} <b>当场</b>，不再等到
     * {@code initialize()}，所以取证点在 setter 上。
     */
    @Test
    void should_not_delegate_the_pool_size_check_to_the_vendor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        IllegalArgumentException vendorFailure = assertThrows(IllegalArgumentException.class,
                () -> scheduler.setPoolSize(0));
        assertThat(vendorFailure.getMessage()).contains("must be 1 or higher");
        assertThat(vendorFailure.getMessage())
                .as("厂商不知道我们的键名，把它的原话透给用户等于让用户去猜是哪个配置错了")
                .doesNotContain("myddd.job");
    }

    /** IQ5：嵌套对象真被绑定（写法：final 字段 + 只有 getter）。 */
    @Test
    void should_bind_the_nested_spring_section() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        new Binder(new MapConfigurationPropertySource(Map.of("myddd.job.spring.pool-size", "7")))
                .bind("myddd.job", Bindable.ofInstance(properties));
        assertEquals(7, properties.getSpring().getPoolSize());
        properties.validate();
    }

    /** IQ6：provider 逐字保留，属性对象不做归一（归一是 JobProviderSelection 的判定）。 */
    @Test
    void should_keep_the_provider_value_verbatim() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        new Binder(new MapConfigurationPropertySource(Map.of("myddd.job.provider", " spring ")))
                .bind("myddd.job", Bindable.ofInstance(properties));
        assertEquals(" spring ", properties.getProvider());
    }

    /** IQ7：嵌套对象是同一个实例，绑定与读取都走它。 */
    @Test
    void should_expose_one_spring_section_instance() {
        MydddJobSpringProperties properties = new MydddJobSpringProperties();
        assertNotNull(properties.getSpring(), "getSpring() 返回 null 时嵌套绑定就没有落点");
        properties.getSpring().setPoolSize(9);
        assertEquals(9, properties.getSpring().getPoolSize());
        assertSame(properties.getSpring(), properties.getSpring());
    }
}

package org.deep361.myddd.resilience.config;

import java.time.Duration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个自有键的启动期校验（{@code IQ1}–{@code IQ8}）：一律 {@code new} 出来再调 {@code validate()}，
 * 不建上下文——"键真的绑上了属性类"是 {@code AC} 组的判据面，混进来会让这两组互相替对方撒谎。
 */
class MydddResiliencePropertiesTest {

    private final MydddResilienceProperties properties = new MydddResilienceProperties();

    /**
     * {@code IQ1}：三个缺省值逐字，且缺省态可过。
     *
     * <p>后半段是 {@code validate()} 开头 {@code if (!this.enabled) { return; }} 那三行的唯一钉子：
     * 总开关关掉后三个键一个消费者都没有，不该被一个没人读的键挡住启动。
     */
    @Test
    void should_default_to_enabled_five_seconds_and_a_blank_prefix() {
        assertThat(this.properties.isEnabled()).isTrue();
        assertThat(this.properties.getRateLimit().getTimeoutMaxBusyWait()).isEqualTo(Duration.ofSeconds(5));
        assertThat(this.properties.getFallback().getMethodPrefix()).isEmpty();
        assertThatCode(this.properties::validate).doesNotThrowAnyException();

        this.properties.setEnabled(false);
        this.properties.getRateLimit().setTimeoutMaxBusyWait(Duration.ZERO);
        this.properties.getFallback().setMethodPrefix("1bad");
        assertThatCode(this.properties::validate).doesNotThrowAnyException();
    }

    /** {@code IQ2}：零与负数都不配当一个上界护栏。 */
    @Test
    void should_reject_a_busy_wait_ceiling_that_is_zero_or_negative() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(rejectWithCeiling(bad))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.resilience.rate-limit.timeout-max-busy-wait")
                    .hasMessageContaining(String.valueOf(bad));
        }
    }

    /** {@code IQ3}：{@code Duration} 的 getter 允许绑定给出 null。 */
    @Test
    void should_reject_a_null_busy_wait_ceiling() {
        assertThatThrownBy(rejectWithCeiling(null))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("timeout-max-busy-wait")
                .hasMessageContaining("null");
    }

    /**
     * {@code IQ4}：护栏小于最小节拍（50ms）也合法——下限约束的是节拍不是预算。
     * {@code RL7} 从运行面钉同一件事。
     */
    @Test
    void should_accept_a_busy_wait_ceiling_below_the_minimum_poll_interval() {
        this.properties.getRateLimit().setTimeoutMaxBusyWait(Duration.ofMillis(1));
        assertThatCode(this.properties::validate).doesNotThrowAnyException();
    }

    /** {@code IQ5}：亚毫秒精度在换算成 int 毫秒时被静默抹掉，所以直接拒并把出路写进消息。 */
    @Test
    void should_reject_a_busy_wait_ceiling_with_sub_millisecond_precision() {
        assertThatThrownBy(rejectWithCeiling(Duration.ofNanos(1)))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("整毫秒")
                .hasMessageContaining("1500ms 或 1.5s");
    }

    /** {@code IQ6}：内含空白与首尾空白两种形态都拒。 */
    @Test
    void should_reject_a_fallback_prefix_containing_whitespace() {
        for (String bad : new String[] {"fb all", " fb"}) {
            assertThatThrownBy(rejectWithPrefix(bad))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("空白")
                    .hasMessageContaining(bad);
        }
    }

    /** {@code IQ7}：拼出来的降级方法名必须是合法标识符，首字符也不例外。 */
    @Test
    void should_reject_a_fallback_prefix_that_cannot_start_an_identifier() {
        assertThatThrownBy(rejectWithPrefix("1fallback"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("标识符首字符");
    }

    /**
     * {@code IQ8}：空白串按"没启用约定"放过（与注解 {@code key} 的 {@code isBlank()} 判据同一条），
     * {@code null} 不放——这一条同时是"空白与 null 语义不同"的正面证据。
     */
    @Test
    void should_accept_a_blank_or_a_bare_identifier_prefix_and_reject_a_null_one() {
        for (String ok : new String[] {"", "   ", "fallback"}) {
            this.properties.getFallback().setMethodPrefix(ok);
            assertThatCode(this.properties::validate).doesNotThrowAnyException();
        }
        assertThatThrownBy(rejectWithPrefix(null))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.resilience.fallback.method-prefix");
    }

    private ThrowingCallable rejectWithCeiling(Duration ceiling) {
        this.properties.getRateLimit().setTimeoutMaxBusyWait(ceiling);
        return this.properties::validate;
    }

    private ThrowingCallable rejectWithPrefix(String prefix) {
        this.properties.getFallback().setMethodPrefix(prefix);
        return this.properties::validate;
    }
}

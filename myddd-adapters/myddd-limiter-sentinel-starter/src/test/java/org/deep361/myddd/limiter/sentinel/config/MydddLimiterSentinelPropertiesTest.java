package org.deep361.myddd.limiter.sentinel.config;

import java.nio.file.Path;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三个自有键的启动期校验（{@code IQ1}–{@code IQ7}）：一律 {@code new} 出来再调 {@code validate()}，
 * 不建上下文——绑定与装配属 {@code AC} 组。"文件存在"这一格用 {@code @TempDir} 造真文件与真目录，
 * 不 mock {@code Files}（mock 出来的存在性不是存在性）。
 */
class MydddLimiterSentinelPropertiesTest {

    /** {@code IQ1}：三个缺省值逐字，且缺省态可过；{@code resolvedRuleLocation()} 缺省给 null。 */
    @Test
    void should_default_to_enabled_memory_and_no_location() {
        MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getRuleSource()).isEqualTo("memory");
        assertThat(properties.getRuleLocation()).isNull();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.resolvedRuleLocation()).isNull();
    }

    /** {@code IQ2}：既不是 memory 也不是 file 的 rule-source 一律拒（"disk"、空白串、null 各一次）。 */
    @Test
    void should_reject_a_rule_source_that_is_neither_memory_nor_file() {
        for (String bad : new String[] {"disk", "", null}) {
            MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
            properties.setRuleSource(bad);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.limiter.sentinel.rule-source")
                    .hasMessageContaining("memory")
                    .hasMessageContaining("file");
        }
    }

    /** {@code IQ3}：memory 来源却配了 location ⇒ 拒（"配了却不生效"唯一的响法）。 */
    @Test
    void should_reject_a_location_present_while_the_source_is_memory() {
        MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
        properties.setRuleSource("memory");
        properties.setRuleLocation("/tmp/whatever.json");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("没有任何消费者")
                .hasMessageContaining("/tmp/whatever.json");
    }

    /** {@code IQ4}：file 来源但 location 缺失（null 与纯空白）⇒ 拒且含"必填"。 */
    @Test
    void should_require_a_location_when_the_source_is_file() {
        for (String missing : new String[] {null, "  "}) {
            MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
            properties.setRuleSource("file");
            properties.setRuleLocation(missing);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("必填");
        }
    }

    /** {@code IQ5}：file 来源指向不存在的路径 ⇒ 拒，消息同时含原文与解析后的绝对路径。 */
    @Test
    void should_reject_a_file_location_that_does_not_exist_and_name_the_resolved_path(@TempDir Path dir) {
        String relative = dir.resolve("does-not-exist.json").toString();
        MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
        properties.setRuleSource("file");
        properties.setRuleLocation(relative);
        String resolved = Path.of(relative).toAbsolutePath().normalize().toString();
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining(relative)
                .hasMessageContaining(resolved);
    }

    /** {@code IQ6}：location 指向一个存在的目录 ⇒ exists 过得去但语义不对，单独拒。 */
    @Test
    void should_reject_a_file_location_pointing_at_a_directory(@TempDir Path dir) {
        MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
        properties.setRuleSource("file");
        properties.setRuleLocation(dir.toString());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("目录");
    }

    /** {@code IQ7}：总开关关掉 ⇒ 整段跳过（跨模块一致性：与 Resilience/Consistency 的 validate 同裁定）。 */
    @Test
    void should_skip_every_check_when_the_provider_is_disabled(@TempDir Path dir) {
        MydddLimiterSentinelProperties properties = new MydddLimiterSentinelProperties();
        properties.setEnabled(false);
        properties.setRuleSource("disk");
        properties.setRuleLocation(dir.resolve("nope.json").toString());
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}

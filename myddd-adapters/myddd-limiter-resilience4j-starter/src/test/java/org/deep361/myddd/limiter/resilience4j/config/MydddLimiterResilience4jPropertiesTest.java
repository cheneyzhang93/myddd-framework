package org.deep361.myddd.limiter.resilience4j.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code myddd.limiter.resilience4j.*} 三个自有键的启动期校验（{@code IQ} 组）。 */
class MydddLimiterResilience4jPropertiesTest {

    private static MydddLimiterResilience4jProperties properties(String source, String location) {
        MydddLimiterResilience4jProperties properties = new MydddLimiterResilience4jProperties();
        properties.setRuleSource(source);
        properties.setRuleLocation(location);
        return properties;
    }

    /** IQ1：缺省值就是"内存来源、不指向任何文件"，validate 通过且解析不出路径。 */
    @Test
    void should_default_to_enabled_memory_source_without_a_location() {
        MydddLimiterResilience4jProperties properties = new MydddLimiterResilience4jProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getRuleSource()).isEqualTo(MydddLimiterResilience4jProperties.SOURCE_MEMORY);
        assertNull(properties.getRuleLocation());

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertNull(properties.resolvedRuleLocation());
    }

    /** IQ2：rule-source 的第三个词——两家人都不认，消息必须把键名、实际值与两个合法取值一起给出。 */
    @Test
    void should_reject_a_rule_source_neither_side_recognises() {
        FrameworkException failure = assertThrowsValidate(properties("redis", null));
        assertThat(failure.getMessage())
                .contains("myddd.limiter.resilience4j.rule-source", "redis",
                        MydddLimiterResilience4jProperties.SOURCE_MEMORY,
                        MydddLimiterResilience4jProperties.SOURCE_FILE);
    }

    /** IQ3：file 来源但没给路径（null / 空串 / 全空格三种绑法）⇒ 必填。 */
    @Test
    void should_require_a_location_when_the_source_is_file() {
        for (String location : new String[]{null, "", "   "}) {
            FrameworkException failure =
                    assertThrowsValidate(properties(MydddLimiterResilience4jProperties.SOURCE_FILE, location));
            assertThat(failure.getMessage()).contains("必填", MydddLimiterResilience4jProperties.SOURCE_FILE);
        }
    }

    /** IQ4：memory 来源却给了路径 ⇒ 这一键在这条来源下没有消费者，拒收而不是"配了没生效"。 */
    @Test
    void should_reject_a_location_that_memory_source_never_reads(@TempDir Path tempDir) {
        FrameworkException failure = assertThrowsValidate(
                properties(MydddLimiterResilience4jProperties.SOURCE_MEMORY, tempDir.resolve("rules.json").toString()));
        assertThat(failure.getMessage())
                .contains("myddd.limiter.resilience4j.rule-location", "没有任何消费者");
    }

    /** IQ5：file 指向不存在的路径 ⇒ 消息同时给出原始值与 toAbsolutePath 之后的真路径。 */
    @Test
    void should_reject_a_missing_rule_file_and_name_the_resolved_path(@TempDir Path tempDir) {
        String location = tempDir.resolve("absent").resolve("rules.json").toString();
        FrameworkException failure =
                assertThrowsValidate(properties(MydddLimiterResilience4jProperties.SOURCE_FILE, location));
        Path resolved = Path.of(location).toAbsolutePath().normalize();
        assertThat(failure.getMessage()).contains(location).contains(resolved.toString());
    }

    /** IQ6：file 指向目录 ⇒ "那是一个目录而不是规则文件"（退化方向是全部放行，必须响）。 */
    @Test
    void should_reject_a_directory_as_the_rule_location() {
        String tempDir = System.getProperty("java.io.tmpdir");
        FrameworkException failure =
                assertThrowsValidate(properties(MydddLimiterResilience4jProperties.SOURCE_FILE, tempDir));
        assertThat(failure.getMessage()).contains("那是一个目录而不是规则文件");
    }

    /** IQ7：总开关关掉时坏值一律不报错，但路径解析照常工作（关闭不等于瞎掉）。 */
    @Test
    void should_skip_validation_when_disabled_but_still_resolve_the_location(@TempDir Path tempDir)
            throws IOException {
        Path file = Files.writeString(tempDir.resolve("rules.json"), "{\"flow\":[],\"degrade\":[]}");

        MydddLimiterResilience4jProperties badSource = properties("redis", null);
        badSource.setEnabled(false);
        assertThatCode(badSource::validate).doesNotThrowAnyException();

        MydddLimiterResilience4jProperties noLocation =
                properties(MydddLimiterResilience4jProperties.SOURCE_FILE, "  ");
        noLocation.setEnabled(false);
        assertThatCode(noLocation::validate).doesNotThrowAnyException();

        MydddLimiterResilience4jProperties fileSource =
                properties(MydddLimiterResilience4jProperties.SOURCE_FILE, file.toString());
        fileSource.setEnabled(false);
        assertThatCode(fileSource::validate).doesNotThrowAnyException();
        assertThat(fileSource.resolvedRuleLocation()).isEqualTo(file.toAbsolutePath().normalize());
    }

    private static FrameworkException assertThrowsValidate(MydddLimiterResilience4jProperties properties) {
        return org.junit.jupiter.api.Assertions.assertThrows(FrameworkException.class, properties::validate);
    }
}

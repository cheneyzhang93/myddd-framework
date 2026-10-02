package org.deep361.myddd.limiter.resilience4j.rule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规则文档的形态面（{@code RS1}–{@code RS5}）：这份字节是不是一张我们能认识的表。
 *
 * <p>语义（这张表能不能真把流量拦住）在 {@code Resilience4jRulePublisherTest}，两张网各自独立存在是本组
 * 存在的理由——{@code RS5} 的四格虽然由校验器拒，但输入全部先经读取器解析，钉的是"形态合法 ≠ 语义合法"
 * 这条分界线本身。
 */
class Resilience4jRuleDocumentReaderTest {

    private final Resilience4jRuleDocumentReader reader = new Resilience4jRuleDocumentReader(null);

    /** RS1：夹具两段各两条、字段齐全，逐格断回读值；文件入口与字符串入口给逐字相同的文档。 */
    @Test
    void should_read_every_field_of_a_two_section_document(@TempDir Path tempDir) throws IOException {
        Path fixture = tempDir.resolve("valid.json");
        java.nio.file.Files.write(fixture, fixtureBytes("valid.json"));
        MydddResilience4jRuleDocument fromFile = reader.read(fixture);
        MydddResilience4jRuleDocument fromString = reader.readJson(new String(fixtureBytes("valid.json"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fromFile.getFlow()).hasSize(2);
        assertThat(fromFile.getDegrade()).hasSize(2);
        assertThat(fromFile.getFlow().get(0).getResource()).isEqualTo("order:create");
        assertThat(fromFile.getFlow().get(0).getCount()).isEqualTo(50);
        assertThat(fromFile.getFlow().get(1).getResource()).isEqualTo("batch:import");
        assertThat(fromFile.getFlow().get(1).getCount()).isEqualTo(5);

        MydddResilience4jRuleDocument.DegradeEntry errorRatio = fromFile.getDegrade().get(0);
        assertThat(errorRatio.getResource()).isEqualTo("payment:capture");
        assertThat(errorRatio.getThreshold()).isEqualTo(Resilience4jRuleValidator.ERROR_RATIO);
        assertThat(errorRatio.getRatio()).isEqualTo(50.0F);
        assertThat(errorRatio.getMaxRtMs()).isNull();
        assertThat(errorRatio.getMinCalls()).isEqualTo(10);
        assertThat(errorRatio.getWindowMs()).isEqualTo(5000L);
        assertThat(errorRatio.getOpenSeconds()).isEqualTo(30L);

        MydddResilience4jRuleDocument.DegradeEntry slowRatio = fromFile.getDegrade().get(1);
        assertThat(slowRatio.getThreshold()).isEqualTo(Resilience4jRuleValidator.SLOW_RATIO);
        assertThat(slowRatio.getRatio()).isEqualTo(60.5F);
        assertThat(slowRatio.getMaxRtMs()).isEqualTo(200L);
        assertThat(slowRatio.getMinCalls()).isEqualTo(4);
        assertThat(slowRatio.getWindowMs()).isEqualTo(2000L);
        assertThat(slowRatio.getOpenSeconds()).isEqualTo(15L);

        // 两个入口必须逐字同一份文档：路径分支偷偷少读一个字段是这类读取器最常见的错位。
        assertThat(describe(fromString)).isEqualTo(describe(fromFile));
    }

    /** RS2：顶层键写错 ⇒ 拒收并点名只认哪两段，绝不退化成"空表通过"（空表 = 全部放行）。 */
    @Test
    void should_reject_a_top_level_key_that_is_not_a_section_name() {
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> reader.readJson("{\"rateLimiters\":[{\"resource\":\"a\",\"count\":1}]}"));
        assertThat(failure.getMessage())
                .contains("rateLimiters", "能写的段只有", "flow", "degrade");
    }

    /** RS3：条目里多一个 r4j 的真字段名 ⇒ 当场自报，消息含段名、下标、资源名与那个字段名。 */
    @Test
    void should_reject_a_real_resilience4j_field_name_inside_an_entry(@TempDir Path tempDir)
            throws IOException {
        Path fixture = tempDir.resolve("unknown-field.json");
        java.nio.file.Files.write(fixture, fixtureBytes("unknown-field.json"));

        FrameworkException failure = assertThrows(FrameworkException.class, () -> reader.read(fixture));
        assertThat(failure.getMessage())
                .contains("flow[0]", "order:create", "timeoutDuration", "resource", "count");
        // 字符串入口同判：消息的形态面不取决于字节从哪来。
        assertThat(assertThrows(FrameworkException.class, () -> reader.readJson(
                new String(fixtureBytes("unknown-field.json"), java.nio.charset.StandardCharsets.UTF_8))))
                .hasMessageContaining("timeoutDuration");
    }

    /** RS4：截断的 JSON ⇒ 一条带文件路径的 FrameworkException，Jackson 的根因留在 cause 里而不是裸抛。 */
    @Test
    void should_reject_unparsable_json_and_name_the_file(@TempDir Path tempDir) throws IOException {
        Path malformed = tempDir.resolve("malformed.json");
        java.nio.file.Files.write(malformed, fixtureBytes("malformed.json"));

        FrameworkException failure = assertThrows(FrameworkException.class, () -> reader.read(malformed));
        assertThat(failure.getMessage()).contains(malformed.toString()).contains("不是可解析的 JSON");
        assertThat(failure.getCause()).isInstanceOf(JacksonException.class);
    }

    /** RS5：四格形态合法、语义不合法的输入全部拒（其中第 2、3 格是 r4j 侧独有的表达不能）。 */
    @Test
    void should_reject_the_four_shapes_our_ports_cannot_use_though_the_vendor_builds_them() {
        FrameworkException missingCount = assertThrows(FrameworkException.class,
                () -> Resilience4jRuleValidator.validate(
                        reader.readJson("{\"flow\":[{\"resource\":\"a\"}]}")));
        assertThat(missingCount.getMessage()).contains("flow[0]", "资源 a", "count");

        FrameworkException errorCount = assertThrows(FrameworkException.class,
                () -> Resilience4jRuleValidator.validate(reader.readJson(
                        "{\"degrade\":[{\"resource\":\"b\",\"threshold\":\"error-count\",\"ratio\":50}]}")));
        assertThat(errorCount.getMessage())
                .contains("degrade[0]", "资源 b", "threshold=error-count",
                        Resilience4jRuleValidator.ERROR_RATIO, Resilience4jRuleValidator.SLOW_RATIO);

        FrameworkException straySlow = assertThrows(FrameworkException.class,
                () -> Resilience4jRuleValidator.validate(reader.readJson(
                        "{\"degrade\":[{\"resource\":\"c\",\"threshold\":\"slow-ratio\",\"ratio\":50}]}")));
        assertThat(straySlow.getMessage()).contains("degrade[0]", "maxRtMs", "slowCallDurationThreshold");

        FrameworkException hundred = assertThrows(FrameworkException.class,
                () -> Resilience4jRuleValidator.validate(reader.readJson(
                        "{\"degrade\":[{\"resource\":\"d\",\"threshold\":\"error-ratio\",\"ratio\":100}]}")));
        assertThat(hundred.getMessage())
                .contains("degrade[0]", "ratio=100.0", "D-161", "不许偷偷换算成 99.99");
    }

    /** 夹具字节：四份文件都在测试类路径的 {@code /resilience4j-rules/} 下，RS 组共用同一份真字节。 */
    private byte[] fixtureBytes(String name) throws IOException {
        try (java.io.InputStream in = getClass().getResourceAsStream("/resilience4j-rules/" + name)) {
            return java.util.Objects.requireNonNull(in, "夹具缺失：" + name).readAllBytes();
        }
    }

    /** 文档的确定性文本形状：两条入口比的是内容而不是对象身份（读取器每次都新建实例）。 */
    private static String describe(MydddResilience4jRuleDocument document) {
        return document.getFlow().stream()
                .map(entry -> "flow{" + entry.getResource() + "," + entry.getCount() + "}")
                .collect(Collectors.joining(";", "[", "]"))
                + document.getDegrade().stream()
                .map(entry -> "degrade{" + entry.getResource() + "," + entry.getThreshold() + ","
                        + entry.getRatio() + "," + entry.getMaxRtMs() + "," + entry.getMinCalls() + ","
                        + entry.getWindowMs() + "," + entry.getOpenSeconds() + "}")
                .collect(Collectors.joining(";", "[", "]"));
    }
}

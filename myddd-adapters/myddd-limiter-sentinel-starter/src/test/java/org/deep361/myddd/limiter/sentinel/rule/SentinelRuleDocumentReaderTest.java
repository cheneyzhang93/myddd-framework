package org.deep361.myddd.limiter.sentinel.rule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规则文档读取器的形态面（{@code RS1}–{@code RS5}）。全部走 {@code readJson}，只有 {@code RS1}
 * 与 {@code RS4} 各补一句 {@code read(Path)}——{@code IQ} 组已把"路径存在"判到运行期之前，
 * 读取器这一层必须真的读得到字节。
 */
class SentinelRuleDocumentReaderTest {

    private final SentinelRuleDocumentReader reader = new SentinelRuleDocumentReader();

    /** {@code RS1}：两段各规则的每个字段都读回来；文件与字符串两条入口给逐字相同的解析结果。 */
    @Test
    void should_read_every_field_of_a_two_section_document(@TempDir Path dir) throws IOException {
        String json = "{"
                + "\"flow\":["
                + "{ \"resource\": \"a\", \"count\": 100 },"
                + "{ \"resource\": \"b\", \"count\": 20 }"
                + "],"
                + "\"degrade\":["
                + "{ \"resource\": \"c\", \"threshold\": \"rt\", \"ratio\": 0.5, \"maxRtMs\": 500,"
                + "\"minCalls\": 3, \"windowMs\": 3000, \"openSeconds\": 10 }"
                + "]}";
        MydddSentinelRuleDocument fromString = reader.readJson(json);
        assertThat(fromString.flow()).hasSize(2);
        assertThat(fromString.flow().get(0).resource()).isEqualTo("a");
        assertThat(fromString.flow().get(0).count()).isEqualTo(100.0D);
        assertThat(fromString.flow().get(1).count()).isEqualTo(20.0D);
        MydddSentinelRuleDocument.DegradeRuleSpec degrade = fromString.degrade().get(0);
        assertThat(degrade.resource()).isEqualTo("c");
        assertThat(degrade.threshold()).isEqualTo("rt");
        assertThat(degrade.ratio()).isEqualTo(0.5D);
        assertThat(degrade.maxRtMs()).isEqualTo(500.0D);
        assertThat(degrade.minCalls()).isEqualTo(3);
        assertThat(degrade.windowMs()).isEqualTo(3000);
        assertThat(degrade.openSeconds()).isEqualTo(10);

        Path file = dir.resolve("valid-copy.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        MydddSentinelRuleDocument fromFile = reader.read(file);
        assertThat(fromFile.flow().toString()).isEqualTo(fromString.flow().toString());
        assertThat(fromFile.degrade().toString()).isEqualTo(fromString.degrade().toString());
    }

    /** {@code RS2}：整段省略读成空表、字段省略读成 null（不是 0）。 */
    @Test
    void should_treat_an_absent_section_as_empty_and_an_absent_field_as_null() {
        MydddSentinelRuleDocument empty = reader.readJson("{}");
        assertThat(empty.flow()).isEmpty();
        assertThat(empty.degrade()).isEmpty();
        assertThat(empty.flow().toString()).isEqualTo(MydddSentinelRuleDocument.EMPTY.flow().toString());
        assertThat(empty.degrade().toString()).isEqualTo(MydddSentinelRuleDocument.EMPTY.degrade().toString());

        MydddSentinelRuleDocument document = reader.readJson(
                "{\"flow\":[{\"resource\":\"a\"}],"
                        + "\"degrade\":[{\"resource\":\"c\",\"threshold\":\"exception-ratio\","
                        + "\"ratio\":0.5,\"minCalls\":3,\"windowMs\":3000,\"openSeconds\":10}]}");
        assertThat(document.flow().get(0).count()).isNull();
        assertThat(document.degrade().get(0).maxRtMs()).isNull();
    }

    /** {@code RS3}：错形字段一律拒、不强转（count:"10"、resource:5、minCalls:1.5）。 */
    @Test
    void should_not_coerce_a_field_of_the_wrong_shape() {
        assertThatThrownBy(() -> reader.readJson("{\"flow\":[{\"resource\":\"a\",\"count\":\"10\"}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("flow[0]")
                .hasMessageContaining("count")
                .hasMessageContaining("数字")
                .hasMessageContaining("STRING");
        assertThatThrownBy(() -> reader.readJson("{\"flow\":[{\"resource\":5,\"count\":10}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("flow[0]")
                .hasMessageContaining("resource")
                .hasMessageContaining("字符串")
                .hasMessageContaining("NUMBER");
        assertThatThrownBy(() -> reader.readJson("{\"degrade\":[{\"resource\":\"c\",\"threshold\":\"rt\","
                + "\"ratio\":0.5,\"maxRtMs\":500,\"minCalls\":1.5,\"windowMs\":3000,\"openSeconds\":10}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("degrade[0]")
                .hasMessageContaining("minCalls")
                .hasMessageContaining("整数");
    }

    /** {@code RS4}：不可解析的 JSON ⇒ FrameworkException，带路径、带首行化原因、cause 是 JacksonException、消息无裸换行。 */
    @Test
    void should_reject_unparsable_json_and_name_the_file(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("malformed.json");
        Files.writeString(file, "{\"flow\":[", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read(file))
                .isInstanceOf(FrameworkException.class)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).contains(file.toString());
                    assertThat(failure.getMessage()).doesNotContain("\n");
                    assertThat(failure.getCause()).isInstanceOf(JacksonException.class);
                });
    }

    /** {@code RS5}：厂商真字段（limitApp/controlBehavior/grade）与错段名一律按未知字段拒收。 */
    @Test
    void should_reject_a_field_this_framework_does_not_expose_even_when_sentinel_has_it() {
        assertThatThrownBy(() -> reader.readJson(
                "{\"flow\":[{\"resource\":\"a\",\"count\":10,\"limitApp\":\"origin\"}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("flow[0]")
                .hasMessageContaining("limitApp");
        assertThatThrownBy(() -> reader.readJson(
                "{\"flow\":[{\"resource\":\"a\",\"count\":10},{\"resource\":\"b\",\"count\":5,\"controlBehavior\":1}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("flow[1]")
                .hasMessageContaining("controlBehavior");
        assertThatThrownBy(() -> reader.readJson(
                "{\"degrade\":[{\"resource\":\"c\",\"threshold\":\"exception-ratio\",\"ratio\":0.5,"
                        + "\"minCalls\":3,\"windowMs\":3000,\"openSeconds\":10,\"grade\":0}]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("degrade[0]")
                .hasMessageContaining("grade");
        assertThatThrownBy(() -> reader.readJson("{\"flows\":[]}"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("能写的段只有")
                .hasMessageContaining("flow")
                .hasMessageContaining("degrade");
    }
}

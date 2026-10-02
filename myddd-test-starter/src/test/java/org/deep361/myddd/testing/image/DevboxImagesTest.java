package org.deep361.myddd.testing.image;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 镜像清单的唯一真源自证：POM 属性 → 资源过滤 → Java 常量，三段各钉一条，缺一条就红。
 */
class DevboxImagesTest {

    private static final List<String> KEYS = List.of("mysql", "redis", "kafka", "rocketmq", "xxljob-admin");
    private static final Path TEMPLATE = Path.of("src/main/resources/myddd-devbox-images.properties");

    @Test
    void should_list_exactly_the_five_containerized_services() {
        assertThat(DevboxImages.keys()).containsExactlyInAnyOrderElementsOf(KEYS);
    }

    @Test
    void should_hold_a_resolved_image_reference_for_every_key() {
        for (String key : KEYS) {
            assertThat(DevboxImages.of(key))
                    .as("myddd-devbox-images.properties 的 %s 必须是过滤后的 image:tag", key)
                    .doesNotContain("@")
                    .matches("^[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+$");
        }
    }

    @Test
    void should_equal_the_parent_pom_property_after_filtering() {
        for (String key : KEYS) {
            String source = System.getProperty("myddd.devbox.image." + key);
            assertThat(source).as("surefire 未注入 myddd.devbox.image.%s，真源断言失效", key).isNotNull();
            assertThat(DevboxImages.of(key)).isEqualTo(source);
        }
    }

    @Test
    void should_keep_the_resource_file_a_template_not_a_second_source() throws IOException {
        // surefire 工作目录默认是模块 basedir（本 POM 未改 workingDirectory），因此这条路径稳定可读
        assertThat(TEMPLATE).as("必须在模块目录下运行测试").exists();
        List<String> assignments = Files.readAllLines(TEMPLATE).stream()
                .filter(line -> line.contains("=") && !line.trim().startsWith("#"))
                .toList();
        assertThat(assignments).hasSize(KEYS.size());
        for (String line : assignments) {
            String key = line.substring(0, line.indexOf('=')).trim();
            String value = line.substring(line.indexOf('=') + 1).trim();
            assertThat(value).as("%s 的值必须回指 POM 属性", key).isEqualTo("@myddd.devbox.image." + key + "@");
        }
    }

    /**
     * 容器持有类用的是从 devbox 复制进来的两份副本（宿主路径挂载在 Testcontainers 2.x 受"宿主路径暴露"许可
     * 清单管控，CI 上直接抛），复制的唯一代价就是"出现第二真源"——下面两条字节相等守卫把它钉死。
     */
    @Test
    void should_keep_the_it_broker_conf_byte_identical_to_devbox() throws IOException {
        Path devboxCopy = Path.of("../myddd-devbox/config/rocketmq/broker.conf");
        assertThat(devboxCopy).as("必须在模块目录下运行测试").exists();
        assertThat(Files.readAllBytes(Path.of("src/main/resources/rocketmq/it-broker.conf")))
                .isEqualTo(Files.readAllBytes(devboxCopy));
    }

    @Test
    void should_keep_the_xxl_job_schema_byte_identical_to_devbox_seed() throws IOException {
        Path devboxSeed = Path.of("../myddd-devbox/seed/mysql/03-xxl-job.sql");
        assertThat(devboxSeed).as("必须在模块目录下运行测试").exists();
        assertThat(Files.readAllBytes(Path.of("src/main/resources/xxljob/tables_xxl_job.sql")))
                .isEqualTo(Files.readAllBytes(devboxSeed));
    }

    @Test
    void should_fail_on_an_unknown_key_instead_of_returning_null() {
        assertThatThrownBy(() -> DevboxImages.of("clickhouse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clickhouse")
                .hasMessageContaining("mysql");
    }

    @Test
    void should_reject_an_unfiltered_placeholder_value() {
        assertThatThrownBy(() -> DevboxImages.requireValue("mysql", "@myddd.devbox.image.mysql@"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("资源过滤")
                .hasMessageContaining("myddd.devbox.image.mysql");
    }

    @Test
    void should_reject_a_blank_value() {
        assertThatThrownBy(() -> DevboxImages.requireValue("redis", "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis");
    }

    @Test
    void should_point_at_the_build_when_the_manifest_is_missing() {
        assertThatThrownBy(() -> DevboxImages.read(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd-devbox-images.properties");
    }

    @Test
    void should_hand_back_a_name_the_container_class_accepts() {
        DockerImageName mysql = DevboxImages.dockerImage("mysql");
        assertThat(mysql.getRepository()).isEqualTo("mysql");
        assertThat(mysql.getVersionPart()).isEqualTo(DevboxImages.MYSQL.substring("mysql:".length()));
    }

    @Test
    void should_agree_between_convenience_constants_and_key_lookup() {
        Properties loaded = DevboxImages
                .read(DevboxImages.class.getResourceAsStream(DevboxImages.RESOURCE));
        assertThat(DevboxImages.MYSQL).isEqualTo(DevboxImages.of("mysql"));
        assertThat(DevboxImages.REDIS).isEqualTo(DevboxImages.of("redis"));
        assertThat(DevboxImages.KAFKA).isEqualTo(DevboxImages.of("kafka"));
        assertThat(DevboxImages.ROCKETMQ).isEqualTo(DevboxImages.of("rocketmq"));
        assertThat(DevboxImages.XXLJOB_ADMIN).isEqualTo(DevboxImages.of("xxljob-admin"));
        for (String key : KEYS) {
            assertThat(DevboxImages.of(key)).isEqualTo(loaded.getProperty(key).trim());
        }
    }
}

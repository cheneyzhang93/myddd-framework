package org.deep361.myddd.domain.outbox;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FluentAccessorNamingProviderTest {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper mapper = JsonMapper.builder()
            .accessorNaming(new FluentAccessorNamingProvider())
            .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
            // Jackson 3 默认 true；用例允许 JSON 缺省原始类型属性（按类型默认值补齐），与本测试的关注点（命名）无关
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public static final class FancyBean {
        private final String orderId;
        private final String url;
        private final boolean settled;
        private int points;

        public FancyBean(String orderId, String url, boolean settled) {
            this.orderId = orderId;
            this.url = url;
            this.settled = settled;
        }

        public String orderId() {
            return orderId;
        }

        public String getURL() {
            return url;
        }

        public boolean isDone() {
            return true;
        }

        public int points() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public boolean settled() {
            return settled;
        }

        public String get2() {
            return "2";
        }

        public String isActive() {
            return "a";
        }

        public String issue() {
            return "i";
        }

        public String getaway() {
            return "w";
        }

        public static String staticNoise() {
            return "noise";
        }

        public void touch() {
        }

        public String withArg(int index) {
            return "x" + index;
        }

        @Override
        public String toString() {
            return "FancyBean";
        }

        @Override
        public int hashCode() {
            return 7;
        }
    }

    public static class BaseBean {
        private final String orderId;

        public BaseBean(String orderId) {
            this.orderId = orderId;
        }

        public String orderId() {
            return orderId;
        }
    }

    public static final class SubBean extends BaseBean {
        private final int points;

        public SubBean(String orderId, int points) {
            super(orderId);
            this.points = points;
        }

        public int points() {
            return points;
        }
    }

    public record ComputedRecord(String orderId, int points) {
        public String label() {
            return orderId + "#" + points;
        }

        public String isActive() {
            return "x";
        }
    }

    @Test
    void should_recognize_fluent_accessors_alongside_standard_getters() {
        FancyBean bean = new FancyBean("o-1", "http://x", true);
        bean.setPoints(5);

        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(bean), JSON_OBJECT);

        assertThat(json).containsOnlyKeys("orderId", "URL", "done", "points", "settled", "issue", "away");
        assertThat(json)
                .containsEntry("orderId", "o-1")
                .containsEntry("URL", "http://x")
                .containsEntry("done", true)
                .containsEntry("points", 5)
                .containsEntry("settled", true)
                .containsEntry("issue", "i")
                .containsEntry("away", "w");
    }

    @Test
    void should_skip_jdk_object_methods_and_non_accessors() {
        Map<String, Object> json = mapper.readValue(
                mapper.writeValueAsString(new FancyBean("o-1", "http://x", false)), JSON_OBJECT);

        assertThat(json).doesNotContainKeys("toString", "hashCode", "staticNoise", "touch", "withArg",
                "get2", "isActive", "getaway");
    }

    @Test
    void should_apply_same_rules_to_records_including_computed_accessors() {
        Map<String, Object> json = mapper.readValue(
                mapper.writeValueAsString(new ComputedRecord("o-2", 3)), JSON_OBJECT);

        assertThat(json).containsOnlyKeys("orderId", "points", "label");
        assertThat(json).containsEntry("label", "o-2#3");
    }

    @Test
    void should_inherit_fluent_accessors_from_superclass() {
        Map<String, Object> json = mapper.readValue(
                mapper.writeValueAsString(new SubBean("o-3", 9)), JSON_OBJECT);

        assertThat(json).containsOnlyKeys("orderId", "points");
        assertThat(json).containsEntry("orderId", "o-3").containsEntry("points", 9);
    }

    @Test
    void should_delegate_setter_naming_for_deserialization() {
        FancyBean bean = mapper.readValue("{\"orderId\":\"o-9\",\"points\":8}", FancyBean.class);

        assertThat(bean.orderId()).isEqualTo("o-9");
        assertThat(bean.points()).isEqualTo(8);
    }

    @Test
    void should_ignore_read_only_properties_when_deserializing() {
        FancyBean bean = mapper.readValue(
                "{\"orderId\":\"o-1\",\"URL\":\"http://ignored\",\"done\":false}", FancyBean.class);

        assertThat(bean.orderId()).isEqualTo("o-1");
        assertThat(bean.getURL()).isNull();
    }
}

package org.deep361.myddd.persistence.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据源键的线程上下文。
 *
 * <p>这里钉的是三件事：跨线程不可见（要点 5）、异常路径一定复原（要点 2 的失败模式来自"键残留"）、
 * 形状规则与配置侧同源（要点 6）。
 */
class DataSourceKeyHolderTest {

    @BeforeEach
    void unbindAnyLeftoverKey() {
        DataSourceKeyHolder.restore(null);
    }

    @Test
    void should_keep_the_bound_key_invisible_to_other_threads() throws Exception {
        assertThat(DataSourceKeyHolder.current()).isNull();

        DataSourceKeyHolder.bind("replica");
        try {
            AtomicReference<String> seenByChild = new AtomicReference<>("未赋值");
            Thread child = new Thread(() -> seenByChild.set(DataSourceKeyHolder.current()));
            child.start();
            child.join();

            assertThat(seenByChild.get()).isNull();
            assertThat(DataSourceKeyHolder.current()).isEqualTo("replica");
        } finally {
            DataSourceKeyHolder.restore(null);
        }

        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_return_the_previous_key_when_binding_and_put_it_back_on_restore() {
        assertThat(DataSourceKeyHolder.bind("primary")).isNull();
        assertThat(DataSourceKeyHolder.current()).isEqualTo("primary");

        assertThat(DataSourceKeyHolder.bind("replica")).isEqualTo("primary");
        assertThat(DataSourceKeyHolder.current()).isEqualTo("replica");

        DataSourceKeyHolder.restore("primary");
        assertThat(DataSourceKeyHolder.current()).isEqualTo("primary");

        DataSourceKeyHolder.restore(null);
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_restore_the_outer_key_when_run_with_nests() {
        String outerResult = DataSourceKeyHolder.runWith("primary", () -> {
            String innerResult = DataSourceKeyHolder.runWith("replica", DataSourceKeyHolder::current);

            assertThat(innerResult).isEqualTo("replica");
            assertThat(DataSourceKeyHolder.current()).isEqualTo("primary");
            return DataSourceKeyHolder.current();
        });

        assertThat(outerResult).isEqualTo("primary");
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_always_restore_the_previous_key_when_the_action_throws() {
        DataSourceKeyHolder.bind("primary");

        assertThatThrownBy(() -> DataSourceKeyHolder.runWith("replica", () -> {
            throw new IllegalStateException("业务炸了");
        })).isInstanceOf(IllegalStateException.class).hasMessage("业务炸了");

        assertThat(DataSourceKeyHolder.current()).isEqualTo("primary");

        DataSourceKeyHolder.restore(null);
        assertThatThrownBy(() -> DataSourceKeyHolder.runWith("replica", () -> {
            throw new IllegalStateException("业务炸了");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_reject_blank_and_malformed_keys_before_binding() {
        for (String key : java.util.Arrays.asList(" ", "", null, "Main", "main-db", "9main", "m".repeat(65))) {
            assertThatThrownBy(() -> DataSourceKeyHolder.bind(key))
                    .as("键 %s", key)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("a-z0-9_");
        }

        assertThat(DataSourceKeyHolder.current()).isNull();
        assertThat(DataSourceKeyHolder.isValidKey("main_2")).isTrue();
        assertThat(DataSourceKeyHolder.isValidKey("m".repeat(64))).isTrue();
        assertThat(DataSourceKeyHolder.isValidKey(null)).isFalse();
    }
}

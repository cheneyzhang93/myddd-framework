package org.deep361.myddd.testing.reset;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 数据源选择的三条路径：显式 bean 名 / 唯一或 @Primary / 什么都没有。
 */
class DataSourceResolverTest {

    @Test
    void should_use_the_named_bean_and_list_candidates_when_absent() {
        var context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("orders", mock(DataSource.class));
        context.refresh();

        assertThat(DataSourceResolver.resolve(context, "orders")).isNotNull();
        assertThat(DataSourceResolver.resolve(context, "  ")).isNotNull();
        assertThatThrownBy(() -> DataSourceResolver.resolve(context, "missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.test.reset.datasource=missing")
                .hasMessageContaining("orders");
        context.close();
    }

    @Test
    void should_fail_fast_on_ambiguous_datasources_without_a_primary() {
        var context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("first", mock(DataSource.class));
        context.getBeanFactory().registerSingleton("second", mock(DataSource.class));
        context.refresh();

        assertThatThrownBy(() -> DataSourceResolver.resolve(context, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd.test.reset.datasource")
                .hasMessageContaining("first");
        context.close();
    }

    @Test
    void should_point_at_the_persistence_starter_when_no_datasource() {
        var context = new GenericApplicationContext();
        context.refresh();

        assertThatThrownBy(() -> DataSourceResolver.resolve(context, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myddd-persistence-starter");
        context.close();
    }
}

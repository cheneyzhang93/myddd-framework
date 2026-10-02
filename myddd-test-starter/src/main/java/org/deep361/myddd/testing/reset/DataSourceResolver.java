package org.deep361.myddd.testing.reset;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * 按 bean 名或"唯一 / {@code @Primary}"解析清理用的 {@link DataSource}。
 *
 * <p>歧义不猜：多数据源且没标 {@code @Primary} 时直接失败，并在消息里指名要配的那个键。
 * 挑错数据源的后果是"清了另一个库的表"，比集成测试红一次严重得多。
 */
final class DataSourceResolver {

    private DataSourceResolver() {
    }

    static DataSource resolve(ApplicationContext context, String beanName) {
        if (beanName != null && !beanName.isBlank()) {
            try {
                return context.getBean(beanName.trim(), DataSource.class);
            } catch (NoSuchBeanDefinitionException e) {
                throw new IllegalStateException("myddd.test.reset.datasource=" + beanName + " 没有对应的 DataSource bean，现有 DataSource bean："
                        + Arrays.toString(context.getBeanNamesForType(DataSource.class)), e);
            }
        }
        ObjectProvider<DataSource> provider = context.getBeanProvider(DataSource.class);
        try {
            DataSource dataSource = provider.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException("集成测试数据清理需要 DataSource，但上下文里没有该 bean"
                        + "（引入 myddd-persistence-starter 或自行声明 DataSource）");
            }
            return dataSource;
        } catch (org.springframework.beans.factory.NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("上下文里有多个 DataSource 且没有 @Primary："
                    + Arrays.toString(context.getBeanNamesForType(DataSource.class))
                    + "；请指定 myddd.test.reset.datasource=<bean 名>", e);
        }
    }
}

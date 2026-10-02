package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.routing.AspectjProxyGuard;
import org.deep361.myddd.persistence.routing.DataSourceRoutingAspect;
import org.deep361.myddd.persistence.routing.RoutingDataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多数据源路由的装配（Task 17）：只在 {@code myddd.persistence.datasources} 非空时出现。
 *
 * <p><b>顺序是硬约束</b>（{@code before} 的两个类各自为什么必须在后面，见设计要点 1）：Boot 的池化
 * {@code DataSource} 必须让路，MyBatis-Plus 必须在我们之后才能看到唯一候选。
 *
 * <p><b>路由模式下框架独占 {@code DataSource}</b>：本 bean 带 {@code @Primary}，业务额外声明的
 * {@code DataSource} 不会被覆盖，但注入点（MP、事务管理器）拿到的仍是路由器——若业务确实想接管
 * 数据源，请清空 {@code myddd.persistence.datasources} 回到单数据源模式，而不是再声明一个。
 *
 * <p>不声明 {@code @ConditionalOnMissingBean(DataSource.class)}：那样会让业务侧一个无关的数据源 bean
 * 悄悄关掉整个路由能力，而 {@code @UseDataSource} 还在原地生效（切面绑了一个没人读的键）。
 */
@AutoConfiguration(before = {DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class})
@ConditionalOnClass(AbstractRoutingDataSource.class)
@Conditional(RoutingDataSourceCondition.class)
@EnableConfigurationProperties(MydddPersistenceProperties.class)
public class MydddRoutingDataSourceAutoConfiguration {

    @Bean
    @Primary
    public DataSource mydddRoutingDataSource(MydddPersistenceProperties properties, Environment environment) {
        rejectSpringDatasourceUrl(environment, properties.getDefaultDatasource());
        Map<String, DataSource> targets = new LinkedHashMap<>();
        properties.getDatasources().forEach((name, datasource) -> targets.put(name, build(name, datasource)));
        return new RoutingDataSource(targets, properties.getDefaultDatasource());
    }

    @Bean
    public DataSourceRoutingAspect mydddDataSourceAspect() {
        return new DataSourceRoutingAspect();
    }

    @Bean
    public AspectjProxyGuard mydddAspectjProxyGuard(ConfigurableListableBeanFactory beanFactory) {
        return new AspectjProxyGuard(beanFactory);
    }
    private static void rejectSpringDatasourceUrl(Environment environment, String defaultKey) {
        String url = environment.getProperty("spring.datasource.url");
        if (!StringUtils.hasText(url)) {
            return;
        }
        throw new FrameworkException("同时配置了 myddd.persistence.datasources.*（路由模式）与 spring.datasource.url="
                + url + "：两套入口并存时，框架只会暴露自己的路由数据源，spring.datasource.* 就成了没人使用的假配置。"
                + "请把这个库改写进 myddd.persistence.datasources." + defaultKey + ".url（默认键），"
                + "或者删掉 myddd.persistence.datasources 回到单数据源模式。"
                + "注意别用 Testcontainers 的 @ServiceConnection 注入 spring.datasource.url 来配路由模式：容器地址"
                + "要在自己的 datasources.<name>.url 里给（Task 20 的用例即如此）。");
    }

    private static DataSource build(String name, MydddPersistenceProperties.Datasource datasource) {
        DataSourceBuilder<?> builder = DataSourceBuilder.create()
                .url(datasource.getUrl())
                .username(datasource.getUsername())
                .password(datasource.getPassword());
        if (StringUtils.hasText(datasource.getDriverClassName())) {
            builder.driverClassName(datasource.getDriverClassName());
        }
        return builder.build();
    }
}

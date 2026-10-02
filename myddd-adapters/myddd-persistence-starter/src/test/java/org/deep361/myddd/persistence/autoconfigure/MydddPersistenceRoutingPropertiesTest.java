package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.autoconfigure.MydddPersistenceProperties.Datasource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由模式（{@code myddd.persistence.datasources.<name>.*} 与 {@code default-datasource}）的启动期校验。
 *
 * <p>这一组只回答一个问题：什么样的配法会让应用起不来、以及报错里能不能直接看到该改哪一行。
 * 松散绑定、Map 绑定这些机制本身属 Spring 的规矩，不在此重测。
 */
class MydddPersistenceRoutingPropertiesTest {

    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    private static Datasource datasource(String url) {
        Datasource datasource = new Datasource();
        datasource.setUrl(url);
        return datasource;
    }

    private static MydddPersistenceProperties routing(String name, String url) {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.setDefaultDatasource(name);
        properties.getDatasources().put(name, datasource(url));
        return properties;
    }

    @Test
    void should_default_to_no_named_datasources_and_a_primary_default_key() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();

        assertThat(properties.getDatasources()).isEmpty();
        assertThat(properties.getDefaultDatasource()).isEqualTo("primary");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_fail_fast_when_the_default_key_is_not_one_of_the_defined_datasources() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getDatasources().put("main", datasource("jdbc:mysql://h:3306/main"));
        properties.getDatasources().put("report", datasource("jdbc:mysql://h:3306/report"));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.default-datasource=primary")
                .hasMessageContaining("main")
                .hasMessageContaining("report");

        properties.setDefaultDatasource("main");
        assertThatCode(properties::validate).doesNotThrowAnyException();

        properties.setDefaultDatasource("   ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("default-datasource");
    }

    @Test
    void should_fail_fast_when_a_named_datasource_url_is_absent_or_not_a_jdbc_url() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.setDefaultDatasource("main");
        properties.getDatasources().put("main", datasource(null));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.datasources.main.url 不能为空");

        properties.getDatasources().put("main", datasource("  "));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.datasources.main.url 不能为空");

        properties.getDatasources().put("main", datasource("mysql://h:3306/main"));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("jdbc:")
                .hasMessageContaining("mysql://h:3306/main");

        properties.getDatasources().put("main", datasource("jdbc:mysql://h:3306/main"));
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_fail_fast_when_a_datasource_name_is_not_a_valid_identifier() {
        for (String name : List.of("Main", "main-db", "9main", "m".repeat(65))) {
            MydddPersistenceProperties properties = new MydddPersistenceProperties();
            properties.setDefaultDatasource(name);
            properties.getDatasources().put(name, datasource("jdbc:h2:mem:" + name));

            assertThatThrownBy(properties::validate)
                    .as("键名 %s", name)
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.persistence.datasources." + name)
                    .hasMessageContaining("a-z0-9_");
        }
    }

    @Test
    void should_fail_fast_when_a_declared_driver_class_cannot_be_loaded() {
        MydddPersistenceProperties properties = routing("main", "jdbc:mysql://h:3306/main");
        properties.getDatasources().get("main").setDriverClassName("com.example.NotADriver");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.datasources.main.driver-class-name")
                .hasMessageContaining("com.example.NotADriver")
                .hasMessageContaining("无法加载");
    }

    @Test
    void should_fail_fast_when_a_declared_driver_class_is_not_a_jdbc_driver() {
        MydddPersistenceProperties properties = routing("main", "jdbc:mysql://h:3306/main");
        properties.getDatasources().get("main").setDriverClassName(String.class.getName());

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("java.sql.Driver")
                .hasMessageContaining(String.class.getName());
    }

    @Test
    void should_accept_a_real_jdbc_driver_and_blank_credentials() {
        MydddPersistenceProperties properties = routing("main", "jdbc:mysql://h:3306/main");
        Datasource datasource = properties.getDatasources().get("main");
        datasource.setDriverClassName(MYSQL_DRIVER);
        datasource.setUsername(" ");
        datasource.setPassword(null);

        // username/password 不校验：空与空白都是合法配法（本地免密库、SQL Server 集成认证）
        assertThatCode(properties::validate).doesNotThrowAnyException();

        datasource.setDriverClassName("   ");
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}

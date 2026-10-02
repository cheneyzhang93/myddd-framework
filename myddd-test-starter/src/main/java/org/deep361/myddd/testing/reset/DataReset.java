package org.deep361.myddd.testing.reset;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 每个集成测试用例开始前清空当前 schema 的业务表（Flyway 版本表与 {@link #exclude()} 列出的表除外）。
 *
 * <p>清理走 MySQL 的 {@code SET FOREIGN_KEY_CHECKS = 0} + {@code TRUNCATE}，因此：
 * <ul>
 *   <li>只面向 MySQL（本框架的数据库选型即 MySQL）；</li>
 *   <li>自增值一并复位，用例之间不会因"上一轮残留"串味；</li>
 *   <li>要求被测库账号有 TRUNCATE 权限，devbox 默认账号满足。</li>
 * </ul>
 *
 * <p>数据源：上下文中唯一或 {@code @Primary} 的 {@link javax.sql.DataSource}；歧义时请显式配置
 * {@code myddd.test.reset.datasource=<bean 名>}（见 {@link DataResetExtension}）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(DataResetExtension.class)
public @interface DataReset {

    /** 额外不清空的表名（大小写不敏感），例如字典表、种子数据表。 */
    String[] exclude() default {};
}

package org.deep361.myddd.testing.reset;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link DataReset} 的执行体：读配置 → 选数据源 → 清空表。
 *
 * <p>配置键（都可省略，随 {@code spring-configuration-metadata.json} 发布）：
 * <ul>
 *   <li>{@code myddd.test.reset.enabled}（默认 {@code true}）：整体关闭清理（例如只读回归环境）；</li>
 *   <li>{@code myddd.test.reset.exclude}（List，默认空）：全局额外排除表，与注解 {@link DataReset#exclude()} 合并；</li>
 *   <li>{@code myddd.test.reset.datasource}：多数据源且无 {@code @Primary} 时指定用哪个 bean。</li>
 * </ul>
 */
public class DataResetExtension implements BeforeEachCallback {

    /** 永不清空的表名（小写；比较时统一转小写）。 */
    static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    static final String ENABLED_PROPERTY = "myddd.test.reset.enabled";
    static final String EXCLUDE_PROPERTY = "myddd.test.reset.exclude";
    static final String DATASOURCE_PROPERTY = "myddd.test.reset.datasource";

    @Override
    public void beforeEach(ExtensionContext context) {
        ApplicationContext applicationContext = resolveContext(context);
        Environment environment = applicationContext.getEnvironment();
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, true)) {
            return;
        }
        DataSource dataSource = DataSourceResolver.resolve(applicationContext,
                environment.getProperty(DATASOURCE_PROPERTY));
        List<String> excluded = collectExcluded(context.getRequiredTestClass(), environment);
        new DatabaseTruncator(dataSource, excluded).reset();
    }

    /** 注解级 + 全局级排除项合并（顺序：注解在前，配置键在后；归一化与去重由 {@link TableNameList} 负责）。 */
    static List<String> collectExcluded(Class<?> testClass, Environment environment) {
        List<String> merged = new ArrayList<>();
        DataReset annotation = AnnotatedElementUtils.findMergedAnnotation(testClass, DataReset.class);
        if (annotation != null) {
            merged.addAll(Arrays.asList(annotation.exclude()));
        }
        merged.addAll(Arrays.asList(StringUtils
                .commaDelimitedListToStringArray(environment.getProperty(EXCLUDE_PROPERTY, ""))));
        return List.copyOf(merged);
    }

    /** 取 Spring 测试上下文。protected 是为了让单测能替换成手工构造的上下文。 */
    protected ApplicationContext resolveContext(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context);
    }
}

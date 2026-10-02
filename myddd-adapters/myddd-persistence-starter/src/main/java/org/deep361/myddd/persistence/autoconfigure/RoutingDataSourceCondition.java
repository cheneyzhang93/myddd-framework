package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.persistence.autoconfigure.MydddPersistenceProperties.Datasource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 路由模式是否启用：{@code myddd.persistence.datasources} 非空，且 {@code myddd.persistence.enabled} 未被关掉。
 *
 * <p>为什么自己写 {@link Condition} 而不是 {@code @ConditionalOnProperty}：后者只能按<b>完整键名</b>判断，
 * 而这里要判断的是"某个前缀下有没有任意 {@code <name>} 子键"。用 {@link Binder} 走的是与
 * {@link MydddPersistenceProperties} 完全同一条松散绑定路径（实测 {@code driver-class-name} 这类
 * kebab-case 键能正确落到 {@code driverClassName}），因此条件与真实配置值不会分叉。
 *
 * <p>条件里重新绑一次是必要的：装配决策发生在 bean 创建之前，拿不到属性 bean。绑的是同一个类，
 * 因此"条件认为在路由"与"属性 bean 认为在路由"不可能不一致；{@code validate()} 的失败发生在属性 bean
 * 初始化时，不在这里（条件不得抛异常，见 Spring 的约定）。
 */
public class RoutingDataSourceCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Binder binder = Binder.get(context.getEnvironment());
        if (!binder.bind("myddd.persistence.enabled", Boolean.class).orElse(Boolean.TRUE)) {
            return false;
        }
        Map<String, Datasource> datasources = binder
                .bind("myddd.persistence.datasources", Bindable.mapOf(String.class, Datasource.class))
                .orElse(Map.of());
        return !datasources.isEmpty();
    }
}

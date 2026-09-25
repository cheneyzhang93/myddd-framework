package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.ddd.event.DomainEvent;

/**
 * 投递通道解析器：决定一条事件走哪个消息通道（MQ topic / 队列 / 消费组）。
 *
 * <p>框架默认按聚合类型推导（{@link #byAggregateType()}）：类简名转 kebab-case 后拼 {@code -events}——
 * {@code org.example.order.Order} → {@code order-events}。应用可实现本接口按事件类型或事件内容路由，
 * 通过声明自己的 bean 整体替换（自动装配带 {@code @ConditionalOnMissingBean}）。
 *
 * <p>实现必须返回非空白通道名；返回 null / 空白会被 {@code OutboxRecorder} 拒绝。
 */
@FunctionalInterface
public interface OutboxChannelResolver {

    /**
     * 解析事件应投递到的通道。
     *
     * @param event         领域事件（可据其类型做更细的路由）
     * @param aggregateType 聚合类型（类名）
     * @return 非空白通道名
     */
    String resolve(DomainEvent event, String aggregateType);

    /** 默认策略：聚合类简名 kebab-case + {@code -events}。 */
    static OutboxChannelResolver byAggregateType() {
        return (event, aggregateType) -> toKebabCase(simpleName(aggregateType)) + "-events";
    }

    /** 取最后一个 {@code .} 或 {@code $} 之后的类简名（内部类聚合取内部类名）。 */
    private static String simpleName(String aggregateType) {
        if (aggregateType == null) {
            throw new NullPointerException("aggregateType 不能为 null");
        }
        if (aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType 不能为空白");
        }
        int cut = Math.max(aggregateType.lastIndexOf('.'), aggregateType.lastIndexOf('$'));
        String simpleName = aggregateType.substring(cut + 1);
        if (simpleName.isEmpty()) {
            throw new IllegalArgumentException("aggregateType 缺少类简名：" + aggregateType);
        }
        return simpleName;
    }

    /**
     * 驼峰转 kebab-case：非首位的<b>大写字母</b>且前一个字符<b>不是大写字母</b>时插入 {@code -}，其余字符
     * 原样小写。连续大写（缩写）不拆：{@code URLShortener} → {@code urlshortener}；数字边界拆：
     * {@code OAuth2Token} → {@code oauth2-token}。
     */
    private static String toKebabCase(String name) {
        StringBuilder kebab = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (i > 0 && Character.isUpperCase(current) && !Character.isUpperCase(name.charAt(i - 1))) {
                kebab.append('-');
            }
            kebab.append(Character.toLowerCase(current));
        }
        return kebab.toString();
    }
}

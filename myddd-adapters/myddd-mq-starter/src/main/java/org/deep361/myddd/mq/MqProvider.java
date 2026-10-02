package org.deep361.myddd.mq;

import java.util.Locale;

/**
 * 本框架认识的消息 provider。新增一个 provider = 新增一个枚举值 + 一个新模块，路由与装配代码一行不改。
 *
 * <p>{@link #parse(String)} 是配置值 → 枚举的**唯一**入口：它负责把所有"值写错了"的情况变成
 * 一句含"实际值 + 全部可取值"的话，因此任何调用方都不许自己写 {@code valueOf}。
 */
public enum MqProvider {

    /** RocketMQ（经典 remoting 客户端，构件 {@code rocketmq-client}）。 */
    ROCKETMQ,
    /** Kafka（复用 Spring Boot 的 Kafka 装配）。 */
    KAFKA;

    /** 可取值清单（配置文档与异常消息共用同一份，杜绝"消息里漏了一个值"）。 */
    public static final String ALLOWED_VALUES = "rocketmq、kafka";

    /**
     * 解析配置值：大小写不敏感、允许 {@code -} 与 {@code _} 互换、去空白。
     *
     * @throws IllegalArgumentException 值为空白或不在 {@link #ALLOWED_VALUES} 内
     */
    public static MqProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("provider 取值不能为空白，可取值：" + ALLOWED_VALUES);
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return MqProvider.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provider 取值非法：" + raw + "，可取值：" + ALLOWED_VALUES, e);
        }
    }

    /** 该 provider 对应的适配器模块名（失败消息里指路用）。 */
    public String moduleName() {
        return "myddd-mq-" + name().toLowerCase(Locale.ROOT) + "-starter";
    }
}

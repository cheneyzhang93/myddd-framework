package org.deep361.myddd.web.json;

import org.deep361.myddd.domain.outbox.FluentAccessorNamingProvider;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 框架全局 JSON 约定（HTTP 与 Outbox payload 共用同一套线上格式）。
 *
 * <p>四项约定各自的理由与内核 {@code JacksonOutboxPayloadSerializer} 的 javadoc 一致：
 * <ul>
 *   <li>{@link FluentAccessorNamingProvider}：领域对象按业务语言命名方法（{@code amount()}），
 *       没有它整个响应体是 {@code {}}；</li>
 *   <li>{@link ConstructorDetector#USE_PROPERTIES_BASED}：单构造器按属性名绑定，配合父 POM 的
 *       {@code -parameters}；</li>
 *   <li>关 {@link DateTimeFeature#WRITE_DATES_AS_TIMESTAMPS}：时间一律 ISO-8601 文本，
 *       不随上游默认值漂移；</li>
 *   <li>关 {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}：容忍未知字段，
 *       支持"新生产者 + 旧消费者"的滚动升级。</li>
 * </ul>
 *
 * <p><b>刻意不做的事</b>：不开 {@code WRITE_DATES_WITH_ZONE_ID}、不改 {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * 的默认（保持启用：给原始类型字段送 null 是错误，不该静默补零）、不注册任何模块。
 *
 * <p>本类是 {@code myddd.web.json.enabled=true} 时通过 {@code JsonMapperBuilderCustomizer} 生效的，
 * 也允许业务在自己的 {@code JsonMapperBuilderCustomizer} 里显式复用（见 {@link #apply(JsonMapper.Builder)}）。
 */
public final class JsonConventions {

    private JsonConventions() {
    }

    /** 在给定 builder 上应用框架约定（返回同一实例，便于链式继续叠加业务约定）。 */
    public static JsonMapper.Builder apply(JsonMapper.Builder builder) {
        return builder
                .accessorNaming(new FluentAccessorNamingProvider())
                .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 用框架约定构出的 Mapper（给非 Spring 场景与测试用；生产装配走 customizer 通道）。 */
    public static JsonMapper frameworkMapper() {
        return apply(JsonMapper.builder()).build();
    }
}

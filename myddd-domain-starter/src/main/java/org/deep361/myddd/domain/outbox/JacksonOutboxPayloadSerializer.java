package org.deep361.myddd.domain.outbox;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.ConstructorDetector;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 基于 Jackson 3 的载荷序列化实现（框架默认）。
 *
 * <p><b>线上格式约定</b>（改动即破坏兼容，需按 §0.4 记录偏差）：
 * <ul>
 *   <li>{@link FluentAccessorNamingProvider}：识别 fluent 访问器，同时保留标准 Bean 命名；</li>
 *   <li>{@link ConstructorDetector#USE_PROPERTIES_BASED}：单构造器按属性名绑定（配合父 POM 全局
 *       {@code -parameters} 编译参数）；</li>
 *   <li>{@link DateTimeFeature#WRITE_DATES_AS_TIMESTAMPS} 显式关闭：{@code Instant} 写 ISO-8601 文本
 *       （Jackson 3 默认即如此，这里显式声明以锁定线上格式，不随上游默认值漂移）；</li>
 *   <li>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} 显式关闭：消费端容忍未知字段，
 *       支持"新生产者 + 旧消费者"滚动升级；</li>
 *   <li>{@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} 保持 Jackson 默认的启用状态：缺失原始类型
 *       属性不静默补默认值；"新增原始类型组件"是破坏性变更，只有"未知字段"方向被容忍。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：{@link ObjectMapper} 本身线程安全，本类无可变状态。
 */
public final class JacksonOutboxPayloadSerializer implements OutboxPayloadSerializer {

    private final ObjectMapper mapper;

    /** 使用框架默认 Mapper 构造。 */
    public JacksonOutboxPayloadSerializer() {
        this(mapperBuilder().build());
    }

    /**
     * 使用外部 Mapper 构造（自动装配层在应用自定义 JSON 约定时使用）。
     *
     * @param mapper 已按本框架约定配置的 Mapper，不可为 {@code null}
     */
    public JacksonOutboxPayloadSerializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为 null");
    }

    /**
     * 按框架约定构造 Mapper：应用需要自定义 {@code ObjectMapper}（如 HTTP JSON）时复用它，保证与 Outbox
     * payload 的格式一致。
     */
    public static JsonMapper.Builder mapperBuilder() {
        return JsonMapper.builder()
                .accessorNaming(new FluentAccessorNamingProvider())
                .constructorDetector(ConstructorDetector.USE_PROPERTIES_BASED)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public String serialize(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为 null");
        try {
            return mapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new FrameworkException("领域事件序列化失败：" + event.getClass().getName(), ex);
        }
    }

    @Override
    public <T extends DomainEvent> T deserialize(String payload, Class<T> eventType) {
        Objects.requireNonNull(payload, "payload 不能为 null");
        Objects.requireNonNull(eventType, "eventType 不能为 null");
        try {
            return mapper.readValue(payload, eventType);
        } catch (JacksonException ex) {
            throw new FrameworkException("领域事件反序列化失败：" + eventType.getName(), ex);
        }
    }
}

package org.deep361.myddd.mq.payload;

import org.deep361.myddd.domain.outbox.JacksonOutboxPayloadSerializer;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * 业务载荷 ↔ JSON 的转换，供 {@code @MessageListener} 的"业务载荷参数"形态使用
 * （内核 {@code MessageListener} 文档：方法参数为 {@code InboundMessage} <b>或业务载荷类型</b>）。
 *
 * <p><b>为什么缺省 Mapper 复用 {@link JacksonOutboxPayloadSerializer#mapperBuilder()}</b>：Outbox 投递器写出的
 * payload 与这里读入的 payload 是同一份线上数据，四项约定（fluent 访问器、按属性名绑构造器、ISO-8601 时间、
 * 容忍未知字段）任何一处单独改动都会让"新生产者 + 旧消费者"或反过来静默丢字段。缺省值取 domain 侧那一个来源，
 * 本模块不再抄第二份；{@code MessagePayloadConverterTest} 的 PC1 用"与 Outbox 序列化结果逐字相等"钉住它。
 *
 * <p><b>失败语义</b>：转换失败抛 {@link FrameworkException}——它必须让这条消息的消费整体失败，
 * 由适配器的重试策略接管（最终进死信），而不是"跳过一条坏消息"（那是静默丢弃，违反端口契约 (a)）。
 *
 * <p><b>自定义入口</b>：业务需要别的 JSON 约定时贡献一个 {@code MessagePayloadConverter} bean
 * （自动装配是 {@code @ConditionalOnMissingBean}），不要去改 {@code myddd.mq.*} 之外的三方键。
 *
 * <p><b>线程安全</b>：{@link ObjectMapper} 线程安全，本类无可变状态。
 */
public final class MessagePayloadConverter {

    private final ObjectMapper mapper;

    /** 使用框架缺省 Mapper（= Outbox payload 的同一套约定）。 */
    public MessagePayloadConverter() {
        this(JacksonOutboxPayloadSerializer.mapperBuilder().build());
    }

    /**
     * @param mapper 已按期望约定构造的 Mapper，不可为 {@code null}
     */
    public MessagePayloadConverter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为 null");
    }

    /**
     * 序列化成 JSON 文本。
     *
     * @param payload 载荷对象，不可为 {@code null}
     * @return JSON 文本
     * @throws NullPointerException   {@code payload} 为 {@code null}
     * @throws FrameworkException     序列化失败（消息含载荷类名）
     */
    public String write(Object payload) {
        Objects.requireNonNull(payload, "payload 不能为 null");
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException ex) {
            throw new FrameworkException("MQ 载荷序列化失败：" + payload.getClass().getName(), ex);
        }
    }

    /**
     * 从 JSON 文本反序列化。
     *
     * @param payload JSON 文本，不可为 {@code null}
     * @param type    目标类型，不可为 {@code null}
     * @param <T>     目标类型
     * @return 反序列化结果
     * @throws NullPointerException   任一入参为 {@code null}
     * @throws FrameworkException     反序列化失败（消息含目标类名，消费端据此判断"哪个类型读不出来"）
     */
    public <T> T read(String payload, Class<T> type) {
        Objects.requireNonNull(payload, "payload 不能为 null");
        Objects.requireNonNull(type, "type 不能为 null");
        try {
            return mapper.readValue(payload, type);
        } catch (JacksonException ex) {
            throw new FrameworkException("MQ 载荷反序列化失败：" + type.getName(), ex);
        }
    }
}

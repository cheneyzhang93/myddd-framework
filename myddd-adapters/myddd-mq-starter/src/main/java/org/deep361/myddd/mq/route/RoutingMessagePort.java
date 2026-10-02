package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.MessagePort;
import org.deep361.myddd.kernel.port.message.OutboundMessage;
import org.deep361.myddd.mq.MqProvider;

import java.util.Map;
import java.util.Objects;

/**
 * 框架对外的唯一 {@link MessagePort}：按通道把消息交给 provider 侧实现。
 *
 * <p>它自己不发一条消息——职责只有三件：解析路由、执行 R6 的延迟投递开关、把"路由指向了容器里没有的
 * provider"这种装配期缺陷在运行期也说清楚。
 */
public final class RoutingMessagePort implements MessagePort {

    private final MessageRouteTable routeTable;
    private final Map<MqProvider, MessagePort> providerPorts;
    private final boolean delayedDeliveryEnabled;

    /**
     * @param routeTable              路由表，不可为 {@code null}
     * @param providerPorts           provider → 实现，不可为 {@code null}（可为空表：此时任何发送都会失败并说明原因）
     * @param delayedDeliveryEnabled  {@code myddd.mq.deliver-at.enabled}
     */
    public RoutingMessagePort(MessageRouteTable routeTable, Map<MqProvider, MessagePort> providerPorts,
                              boolean delayedDeliveryEnabled) {
        this.routeTable = Objects.requireNonNull(routeTable, "routeTable 不能为 null");
        this.providerPorts = Map.copyOf(providerPorts);
        this.delayedDeliveryEnabled = delayedDeliveryEnabled;
    }

    @Override
    public void send(OutboundMessage message) {
        Objects.requireNonNull(message, "message 不能为 null");
        if (!delayedDeliveryEnabled && message.deliverAt() != null) {
            throw new BizException(CommonErrorCode.ARGUMENT_INVALID,
                    "deliverAt（myddd.mq.deliver-at.enabled=false）");
        }
        MqProvider provider = routeTable.resolve(message.channel());
        MessagePort target = providerPorts.get(provider);
        if (target == null) {
            throw new FrameworkException("通道 " + message.channel() + " 路由到 " + provider
                    + "，但容器里没有 " + provider.moduleName() + " 提供的 ProviderMessagePort bean。"
                    + "请引入该模块，或把这条通道改到已就位的 provider（现有：" + providerPorts.keySet() + "）");
        }
        target.send(message);
    }

    /** 该通道会落到哪个 provider（观测缝：文档、测试与运维自检用）。 */
    public MqProvider providerFor(String channel) {
        return routeTable.resolve(channel);
    }
}

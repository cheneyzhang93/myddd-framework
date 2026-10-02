package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 通道 → provider 的路由表（不可变）。
 *
 * <p>查找规则只有一条：**精确通道名优先，未命中才用缺省 provider**。不做前缀匹配、不做通配符——
 * MQ 通道名同时是 RocketMQ 的 topic 与 Kafka 的 topic，猜错一个字符就是往另一个 topic 发消息。
 *
 * <p>构造入参已由 {@code MydddMqProperties} 校验并解析过（那里负责回显非法值），
 * 本类只负责"取不到路由时怎么说清楚"。
 */
public final class MessageRouteTable {

    private final Map<String, MqProvider> exactRoutes;
    private final MqProvider fallbackProvider;

    /**
     * @param exactRoutes      通道 → provider，不可为 {@code null}（可为空表）；内部做不可变拷贝
     * @param fallbackProvider 未命中时的 provider，可为 {@code null}（表示"没配缺省"）
     */
    public MessageRouteTable(Map<String, MqProvider> exactRoutes, MqProvider fallbackProvider) {
        Objects.requireNonNull(exactRoutes, "exactRoutes 不能为 null");
        this.exactRoutes = Map.copyOf(exactRoutes);
        this.fallbackProvider = fallbackProvider;
    }

    /** 命中就返回命中项；未命中且有缺省就返回缺省。 */
    public Optional<MqProvider> find(String channel) {
        return providerOf(channel).or(() -> Optional.ofNullable(fallbackProvider));
    }

    /**
     * 同 {@link #find(String)}，但未命中且无缺省时快速失败。
     *
     * @throws IllegalArgumentException {@code channel} 为空白
     * @throws FrameworkException       无路由可用，消息含通道名、已配置通道清单与两条补救键
     */
    public MqProvider resolve(String channel) {
        return find(requireChannel(channel)).orElseThrow(() -> new FrameworkException(
                "通道 " + channel + " 没有可用的 MQ 路由：未命中显式配置，且没有缺省 provider。已配置的通道 "
                        + configuredChannels() + "；请补 myddd.mq.channels." + channel + ".provider 或 myddd.mq.default-provider"
                        + "（可取值：" + MqProvider.ALLOWED_VALUES + "）"));
    }

    /** 路由表引用到的全部 provider（含缺省），装配期据此校验"每个被引用的 provider 都有 bean"。 */
    public Set<MqProvider> referencedProviders() {
        Set<MqProvider> providers = new TreeSet<>(exactRoutes.values());
        if (fallbackProvider != null) {
            providers.add(fallbackProvider);
        }
        return Collections.unmodifiableSet(providers);
    }

    /** 已显式配置的通道名（升序，便于失败消息与文档一致）。 */
    public Set<String> configuredChannels() {
        // 不能写成 Set.copyOf(new TreeSet<>(...))：copyOf 返回的是"不保证顺序"的不可变集，
        // 排序在拷贝那一刻就丢了；升序要活到调用方手里，只能把有序集本身包一层不可变。
        return Collections.unmodifiableSet(new TreeSet<>(exactRoutes.keySet()));
    }

    /** 是否配了缺省 provider。 */
    public boolean hasFallback() {
        return fallbackProvider != null;
    }

    private Optional<MqProvider> providerOf(String channel) {
        return Optional.ofNullable(exactRoutes.get(channel));
    }

    private static String requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel 不能为空白");
        }
        return channel;
    }
}

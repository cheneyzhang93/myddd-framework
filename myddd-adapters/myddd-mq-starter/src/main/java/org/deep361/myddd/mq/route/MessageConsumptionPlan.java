package org.deep361.myddd.mq.route;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.mq.MqProvider;
import org.deep361.myddd.mq.listener.MessageListenerRegistrar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 消费侧视图：把"哪些通道有人听"交给路由表判定"该由哪个 provider 去订阅"。
 *
 * <p>为什么必须有它：路由不只决定往哪发，也决定谁在听。一个被 {@code @MessageListener} 监听、
 * 但既无显式路由又无缺省 provider 的通道，<b>没有任何 provider 会订阅它</b>——消息永远躺在 broker 里，
 * 而应用启动一切正常。这与 Task 33 的三条装配期快速失败同源，所以这里同样选择"起不来"而不是"静默不收"。
 *
 * <p><b>调用时机</b>：provider 适配器在自己的 {@code SmartLifecycle.start()}（或等价的消费者启动点）里调用，
 * 那时全部单例已实例化、注册表快照已冻结。真启动时本类抛出的 {@link FrameworkException} 会被 Spring 的
 * 生命周期处理器包成 {@code ApplicationContextException}，应用起不来——单测里直接断言本类型。
 *
 * <p>每次调用都从注册表现取（注册表在刷新后不可变），所以本类没有需要维护的状态副本，
 * 也就不会出现"计划与注册表不一致"这种只在运行期暴露的偏差。
 */
public final class MessageConsumptionPlan {

    private final MessageRouteTable routeTable;
    private final MessageListenerRegistrar registrar;

    /**
     * @param routeTable 路由表，不可为 {@code null}
     * @param registrar  监听器注册表，不可为 {@code null}
     */
    public MessageConsumptionPlan(MessageRouteTable routeTable, MessageListenerRegistrar registrar) {
        this.routeTable = Objects.requireNonNull(routeTable, "routeTable 不能为 null");
        this.registrar = Objects.requireNonNull(registrar, "registrar 不能为 null");
    }

    /**
     * 该 provider 要订阅的通道及其消费组。
     *
     * @param provider provider，不可为 {@code null}
     * @return 通道 → 消费组（⚠️ {@code Map.copyOf} 会重新散列，<b>键的顺序不保证</b>——需要确定顺序的调用方自己排序，
     *         RocketMQ 消费者的订阅顺序就是这么钉住的，见 Task 36 的 {@code CM4}；值可以是空串，表示用适配器的缺省消费组）；
     *         该 provider 一个通道都不负责时返回空 map（适配器据此<b>不启动</b>消费者，这是合法形态）
     * @throws NullPointerException 入参为 {@code null}
     * @throws FrameworkException   存在没有任何 provider 路由的被监听通道（消息全部列出，不只第一条）
     */
    public Map<String, String> subscriptionsFor(MqProvider provider) {
        Objects.requireNonNull(provider, "provider 不能为 null");
        requireAllMonitoredChannelsRoutable();
        Map<String, String> subscriptions = new LinkedHashMap<>();
        for (String channel : registrar.channels()) {
            routeTable.find(channel).filter(provider::equals).ifPresent(ignored -> {
                subscriptions.put(channel, registrar.groupOf(channel));
            });
        }
        return Map.copyOf(subscriptions);
    }

    /** 该 provider 负责的通道集合（{@link #subscriptionsFor(MqProvider)} 的键视图，语义完全一致）。 */
    public Set<String> channelsFor(MqProvider provider) {
        return subscriptionsFor(provider).keySet();
    }

    private void requireAllMonitoredChannelsRoutable() {
        List<String> unrouted = registrar.channels().stream()
                .filter(channel -> routeTable.find(channel).isEmpty())
                .toList();
        if (!unrouted.isEmpty()) {
            throw new FrameworkException("有 " + unrouted.size() + " 个被 @MessageListener 监听的通道解析不出 provider："
                    + unrouted + "。没有 provider 订阅它们，消息会永远留在 broker 里。请为每个通道补 "
                    + "myddd.mq.channels.<channel>.provider（可取值：" + MqProvider.ALLOWED_VALUES + "）"
                    + "，或补 myddd.mq.default-provider");
        }
    }
}

package org.deep361.myddd.mq;

import org.deep361.myddd.kernel.port.message.MessagePort;

/**
 * provider 侧的 {@link MessagePort}：适配器把它"发消息"的实现注册成这个接口的 bean，
 * {@code RoutingMessagePort} 据此收集可用 provider。
 *
 * <p>为什么不用 bean 名约定：bean 名是全局命名空间里的字符串，改名即断链；
 * {@link #provider()} 让"这个 bean 属于哪个 provider"成为类型系统里的一条事实，
 * 装配期就能判重（同一 provider 两个 bean）与判缺（路由指向一个不在容器里的 provider）。
 */
public interface ProviderMessagePort extends MessagePort {

    /** 本实现所属的 provider，必须非空且与 {@link MqProvider} 的某一个值对应。 */
    MqProvider provider();

    /**
     * 该 provider 是否支持定点投递（R6）。
     *
     * <p>返回 {@code false} 的实现<b>必须拒发</b>带 {@code deliverAt} 的消息
     * （{@code BizException(CommonErrorCode.ARGUMENT_INVALID)}，消息含 {@code deliverAt}），
     * 不允许静默降级成立即投递。能力由 provider 自己声明，不放进路由表：它是厂商特性，不是部署选择。
     * {@code RoutingMessagePort} <b>不</b>读它——同一个判断不许有两处真相，
     * 而 {@code MessagePortContractTestBase#supportsDelayedDelivery()} 必须能问端口本身
     * （Task 35 Step 0 与本方法同批落地）。
     */
    boolean supportsDelayedDelivery();
}

package org.deep361.myddd.mq.rocketmq.config;

import jakarta.annotation.PostConstruct;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code myddd.mq.rocketmq.*}：§1.2.1 标 ★ 的两条（本仓是裸 client 用法，没有三方
 * {@code @ConfigurationProperties} 可读，只能自有键承载）。
 *
 * <p><b>刻意不校验的东西</b>，三条都写在异常消息里而不是藏在文档里：
 * ① <b>形态</b>：只做非空白，不解析 {@code ip:port}。域名形式（{@code namesrv.example.com:9876}）、
 * 多地址分号列表、代理端点都是合法输入，深度校验只会拒掉合法配置并把用户推向"关掉校验"的口子。
 * ② <b>可达性</b>：启动期不连 name-server。实测 name-server 不可达时厂商 {@code start()} 仍然成功，
 * 失败发生在首次发送——早连一次也拦不住配置错误，只是把 1 秒多的 netty 引导塞进启动。
 * ③ <b>厂商环境变量</b>：不读 {@code NAMESRV_ADDR}（{@code MixAll.NAMESRV_ADDR_ENV}）与
 * {@code rocketmq.namesrv.addr}（{@code MixAll.NAMESRV_ADDR_PROPERTY}）的缺省回落。
 * 框架的键是唯一的真相来源；要用环境变量请写在配置里（{@code name-server: ${NAMESRV_ADDR:}}），
 * 这样它至少在 {@code Environment} 里可见，而不是一个框架读不到的隐式输入。
 * 这条偏离厂商惯例，所以必填失败的消息里要点明它（IQ2 钉消息文本）。
 *
 * <p>其余 producer 参数不进配置（{@code sendMsgTimeout}/{@code maxMessageSize}/{@code retryTimesWhenSendFailed}
 * 一律厂商缺省，实测 3000 ms / 4 MB / 2 次），要改就走 {@code @ConditionalOnMissingBean(RocketMqProducer.class)}
 * 自带实现——这比给每个厂商参数配一个 {@code myddd.*} 键诚实：参数语义属于厂商，我们不做翻译层（§0.3-5）。
 */
@ConfigurationProperties(prefix = "myddd.mq.rocketmq")
public class MydddRocketMqProperties {

    /** 实测厂商缺省值：{@code DefaultMQProducer} 构造后 {@code getNamesrvAddr()} 为 {@code null}。 */
    private String nameServer;

    /** 生产者分组。⚠️ 这是"发送方身份"，与消费组的语义无关（消费组来自 {@code @MessageListener(group=…)}）。 */
    private String producerGroup = "myddd-producer";

    /** {@code myddd.mq.rocketmq.consumer.*}：消费侧自有键（§1.2.1 的三条）。 */
    private final Consumer consumer = new Consumer();

    @PostConstruct
    void validate() {
        if (nameServer == null || nameServer.isBlank()) {
            throw new FrameworkException("myddd.mq.rocketmq.name-server 必填且不能为空白（实际：" + nameServer
                    + "，期望形态：ip:port，多地址用分号分隔，如 10.0.0.1:9876;10.0.0.2:9876）。"
                    + "本框架不回落厂商的 NAMESRV_ADDR 环境变量与 rocketmq.namesrv.addr 系统属性，"
                    + "需要它们请写成 name-server: ${NAMESRV_ADDR:}");
        }
        if (producerGroup == null || producerGroup.isBlank()) {
            throw new FrameworkException("myddd.mq.rocketmq.producer-group 必填且不能为空白（实际：" + producerGroup
                    + "，期望形态：非空白分组名，缺省 myddd-producer）");
        }
        if (consumer.getGroup() == null || consumer.getGroup().isBlank()) {
            throw new FrameworkException("myddd.mq.rocketmq.consumer.group 必填且不能为空白（实际："
                    + consumer.getGroup() + "，期望形态：非空白组名，本框架缺省 myddd-consumer；"
                    + "厂商形态规则在消费者创建时另行校验，见 DefaultRocketMqPushConsumer）");
        }
        if (consumer.getMaxReconsumeTimes() < 0) {
            throw new FrameworkException("myddd.mq.rocketmq.consumer.max-reconsume-times 必须 >= 0（实际："
                    + consumer.getMaxReconsumeTimes() + "，期望形态：重投次数上限，缺省 16；"
                    + "超过上限后消息进入 %DLQ%<消费组>。厂商自身缺省是 -1=交给 broker 配置，"
                    + "本框架不允许这种“同一份应用在不同 broker 上重投次数不同”的形态）");
        }
        Duration timeout = consumer.getConsumeTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new FrameworkException("myddd.mq.rocketmq.consumer.consume-timeout 必须 > 0（实际：" + timeout
                    + "，期望形态：1m 的整数倍，如 15m、30m，缺省 15m）");
        }
        if (timeout.getSeconds() % 60 != 0 || timeout.getNano() != 0) {
            // getNano() 也在判定里：只看 getSeconds()%60 会放过 500ms 这种"秒部为 0"的亚秒值，
            // 而它经 toMinutes() 同样是 0 分钟——规则说的是"整分钟"，亚分钟一律拒。
            throw new FrameworkException("myddd.mq.rocketmq.consumer.consume-timeout 必须是整分钟（实际：" + timeout
                    + "）。厂商参数单位是分钟，亚分钟的值会被截断成 0 分钟而使每条消息都被判为超时；"
                    + "本框架不做取整，请写成 1m 的整数倍");
        }
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    /**
     * 消费侧配置。
     *
     * <p><b>缺省消费组为什么必须是一个框架自有键</b>：内核 {@code @MessageListener#group()} 的冻结文本是
     * "留空表示使用适配器的默认消费组"，而厂商自己的常量 {@code MixAll.DEFAULT_CONSUMER_GROUP}
     * 实测是 {@code DEFAULT_CONSUMER}——所有应用共用它，两个部署就会互相分走对方的消息
     * （集群消费下同一个组是一个负载分组）。所以"缺省"必须由部署方命名，不能借厂商常量。
     *
     * <p><b>{@code maxReconsumeTimes} 显式钉 16</b>：实测厂商缺省是 {@code -1}，语义是"交给 broker 配置"。
     * 把重投上限交给 broker 部署参数，等于让同一份应用在两套 broker 上行为不同，
     * 而 {@code %DLQ%<组>} 的到达时间正是运维要能算的数。
     *
     * <p><b>{@code consumeTimeout} 的单位是分钟</b>：实测 {@code getConsumeTimeout()} 返回 {@code long}、
     * 缺省 {@code 15}，且厂商字节码里 {@code ProcessQueue} 用 {@code getConsumeTimeout() × 60 × 1000}
     * 与消费开始时间比较。亚分钟的值会被静默截断成 0，所以直接拒绝而不是取整。
     */
    public static class Consumer {

        /** 未写 {@code group} 的通道使用的缺省消费组。 */
        private String group = "myddd-consumer";

        /** 重投次数上限，超限后消息进 {@code %DLQ%<消费组>}。 */
        private int maxReconsumeTimes = 16;

        /** 单条消息的消费超时；必须是整分钟（厂商单位）。 */
        private Duration consumeTimeout = Duration.ofMinutes(15);

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public int getMaxReconsumeTimes() {
            return maxReconsumeTimes;
        }

        public void setMaxReconsumeTimes(int maxReconsumeTimes) {
            this.maxReconsumeTimes = maxReconsumeTimes;
        }

        public Duration getConsumeTimeout() {
            return consumeTimeout;
        }

        public void setConsumeTimeout(Duration consumeTimeout) {
            this.consumeTimeout = consumeTimeout;
        }
    }
}

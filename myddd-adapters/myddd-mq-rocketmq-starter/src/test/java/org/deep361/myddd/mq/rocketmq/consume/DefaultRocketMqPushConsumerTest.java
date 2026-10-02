package org.deep361.myddd.mq.rocketmq.consume;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.remoting.protocol.heartbeat.SubscriptionData;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultRocketMqPushConsumer} 的厂商接线（DC1–DC9）：校验借厂商规则、订阅写进厂商状态、
 * 未暴露的参数一条都不偷偷改。
 *
 * <p><b>本类是本模块唯一碰真厂商对象的用例组</b>，但仍然零 broker：构造与 {@code subscribe} 实测都不联网
 * （一手实测 {@code p13out.txt}），取证读 {@code consumer().getDefaultMQPushConsumerImpl()
 * .getSubscriptionInner()}——<b>不是</b> {@code getSubscription()}：后者是外层那张表，启动时才从 inner 复制
 * （{@code p7out.txt}），读错位置会得到"什么都没发生"。除 DC9 故意触发厂商<b>声明过</b>的启动异常之外，
 * 本类一次都不 {@code start()}（不可达 name-server 时厂商抛的是未声明的 {@code IllegalStateException}，
 * 并留下 6 个非守护线程——那是 {@code CM7}/{@code CM8} 用替身演的事，也是 Task 40 的 IT 内容）。
 *
 * <p><b>断言按厂商消息原文逐字写，不抽公共前缀</b>：一手实测 {@code p9out.txt} 里 topic 那条首词大写
 * （{@code The specified topic…}）、group 那条首词小写（{@code the specified group…}），抽一个共用前缀
 * 就是把两条不同规则写成一条。
 */
class DefaultRocketMqPushConsumerTest {

    private static final String NAME_SERVER = "127.0.0.1:9876";
    private static final String GROUP = "myddd-consumer";
    private static final String SUB_ALL = SubscriptionData.SUB_ALL;

    /** 厂商白名单规则的原文（topic 与 group 两条共用后半句，前半句的大小写不同 ⇒ 各自逐字断）。 */
    private static final String ILLEGAL_RULE = "contains illegal characters, allowing only ^[%|a-zA-Z0-9_-]+$";

    private final DefaultRocketMqPushConsumer wiring = new DefaultRocketMqPushConsumer(NAME_SERVER, GROUP);

    /** 取证缝：厂商那张活的订阅表（{@code subscribe} 的真实落点）。 */
    private Map<String, SubscriptionData> subscriptionInner() {
        return wiring.consumer().getDefaultMQPushConsumerImpl().getSubscriptionInner();
    }

    @Test
    void should_reject_an_illegal_consumer_group_naming_both_sources() {
        // DC1：组名形态错的来源有两个（注解的 group 与配置缺省组），消息必须两个都点名，用户才知道改哪一处。
        assertThatThrownBy(() -> new DefaultRocketMqPushConsumer(NAME_SERVER, "bad group"))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("消费组名不合法：bad group")
                .hasMessageContaining("the specified group[bad group] " + ILLEGAL_RULE)
                .hasMessageContaining("@MessageListener")
                .hasMessageContaining("myddd.mq.rocketmq.consumer.group")
                // 厂商消息的第二行永远是那条 docs URL：vendorReason 取首行的效果由这一句钉住。
                .hasMessageNotContaining("For more information, please visit the url");
    }

    @Test
    void should_reject_a_blank_or_overlong_group_with_the_vendor_message() {
        // DC2：null / "" / "  " 三种实测都是同一句（厂商 isBlank 在前），超长那条则回显整个组名。
        for (String blank : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> new DefaultRocketMqPushConsumer(NAME_SERVER, blank))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("the specified group is blank");
        }
        String overlong = "dc2-group-" + "x".repeat(111);           // 121 字符，规则上限 120
        assertThat(overlong).hasSize(121);
        assertThatThrownBy(() -> new DefaultRocketMqPushConsumer(NAME_SERVER, overlong))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("is longer than group max length: 120.")
                // 厂商这条确实回显组名（实测），断前 20 字符足够，整串塞进断言只是噪音。
                .hasMessageContaining(overlong.substring(0, 20));
    }

    @Test
    void should_reject_a_dotted_channel_before_touching_the_vendor_state() {
        // DC3：含点号通道名在 Kafka 合法、在 RocketMQ 非法。份量全在实测对照上：厂商自己的 subscribe
        // 对这种名字不报错且真的写进 subscriptionInner（p13out），而 Validators.checkTopic 明确会拒（p9out）。
        // 所以框架的检查不是重复劳动，是把形态错误变成启动期诊断的唯一位置。
        assertThatThrownBy(() -> wiring.subscribe("order.events", SUB_ALL))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("order.events")
                .hasMessageContaining("The specified topic[order.events] " + ILLEGAL_RULE)
                .hasMessageContaining("Kafka")
                .hasMessageContaining("@MessageListener");

        // 拒在厂商状态之外：订阅表仍是空的，而不是"写进去了但没人知道"。
        assertThat(subscriptionInner()).isEmpty();
    }

    @Test
    void should_enforce_the_vendor_rule_set_and_not_one_more_or_less() {
        // DC4：通过的形态与拒的形态都按厂商名单，框架不另立第二套（%DLQ%/%RETRY%/TBW102/| 都合法）。
        for (String legal : List.of("%DLQ%g", "%RETRY%g", "TBW102", "a|b", "t".repeat(127))) {
            wiring.subscribe(legal, SUB_ALL);
        }
        assertThat(subscriptionInner()).containsKeys("%DLQ%g", "%RETRY%g", "TBW102", "a|b", "t".repeat(127));

        // 128 字符这一条是"框架检查早于且严于厂商订阅期"的第二处证据：厂商 subscribe 放过它（p13out），
        // 而 Validators 的规则本身含长度上限 ⇒ 用的还是同一个 checkTopic，没有另立名单。
        assertThatThrownBy(() -> wiring.subscribe("a".repeat(128), SUB_ALL))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("The specified topic is longer than topic max length 127.");
        for (String illegal : new String[]{"中文通道", "bad name"}) {
            assertThatThrownBy(() -> wiring.subscribe(illegal, SUB_ALL))
                    .isInstanceOf(FrameworkException.class)
                    // 非 ASCII 名走的就是 illegal characters 那条：字符集是厂商定的，框架不放宽。
                    .hasMessageContaining("The specified topic[" + illegal + "] " + ILLEGAL_RULE);
        }
        // 空白与 null 实测是同一句（厂商 isBlank 在前），形态不同但规则同源 ⇒ 逐字写厂商原文。
        for (String blank : new String[]{"   ", null}) {
            assertThatThrownBy(() -> wiring.subscribe(blank, SUB_ALL))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("The specified topic is blank");
        }
        assertThat(subscriptionInner()).hasSize(5);                 // 被拒的一条都没进厂商状态
    }

    @Test
    void should_write_the_subscription_into_the_vendor_state() {
        // DC5：取证必须读 inner 那张表。实测形态：subString=*、expressionType=TAG、tagsSet/codeSet 全空。
        wiring.subscribe("order-events", SUB_ALL);

        assertThat(subscriptionInner()).containsKey("order-events");
        SubscriptionData subscription = subscriptionInner().get("order-events");
        assertThat(subscription.getTopic()).isEqualTo("order-events");
        assertThat(subscription.getSubString()).isEqualTo("*");
        assertThat(subscription.getExpressionType()).isEqualTo("TAG");
        assertThat(subscription.getTagsSet()).isEmpty();
        assertThat(subscription.getCodeSet()).isEmpty();
        assertThat(subscription.isClassFilterMode()).isFalse();
        // 外层那张表与 inner 不是同一份（p7out）：启动时才复制，读错了就等于"什么都没发生"。
        assertThat(wiring.consumer().getSubscription()).isEmpty();
    }

    @Test
    void should_start_from_the_measured_vendor_defaults_and_change_nothing_else() {
        // DC6：没暴露的厂商参数一个都没偷偷改（实测缺省逐个钉）。
        DefaultMQPushConsumer consumer = wiring.consumer();
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(-1);      // 厂商缺省：交给 broker（框架显式钉 16）
        assertThat(consumer.getConsumeTimeout()).isEqualTo(15L);        // 单位是分钟（ProcessQueue 字节码 ×60×1000）
        assertThat(consumer.getPullBatchSize()).isEqualTo(32);
        assertThat(consumer.getConsumeThreadMin()).isEqualTo(20);
        assertThat(consumer.getConsumeThreadMax()).isEqualTo(20);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getConsumeFromWhere()).isEqualTo(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        assertThat(consumer.getMessageModel()).isEqualTo(MessageModel.CLUSTERING);
        assertThat(consumer.isPostSubscriptionWhenPull()).isFalse();
        assertThat(consumer.getPullThresholdForQueue()).isEqualTo(1000);
        assertThat(consumer.getAllocateMessageQueueStrategy())
                .isInstanceOf(AllocateMessageQueueAveragely.class);
    }

    @Test
    void should_apply_exactly_the_two_configured_parameters() {
        // DC7：configure 只动这两项，其余仍是缺省——缺了对照式断言，"多改一个参数"就无人发现。
        wiring.configure(5, 30);

        DefaultMQPushConsumer consumer = wiring.consumer();
        assertThat(consumer.getMaxReconsumeTimes()).isEqualTo(5);
        assertThat(consumer.getConsumeTimeout()).isEqualTo(30L);
        assertThat(consumer.getPullBatchSize()).isEqualTo(32);
        assertThat(consumer.getConsumeThreadMin()).isEqualTo(20);
        assertThat(consumer.getConsumeThreadMax()).isEqualTo(20);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(1);
        assertThat(consumer.getConsumeFromWhere()).isEqualTo(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
        assertThat(consumer.getMessageModel()).isEqualTo(MessageModel.CLUSTERING);
        assertThat(consumer.isPostSubscriptionWhenPull()).isFalse();
        assertThat(consumer.getPullThresholdForQueue()).isEqualTo(1000);
        assertThat(consumer.getSubscription()).isEmpty();
    }

    @Test
    void should_give_the_consumer_a_process_unique_instance_name_and_close_safely() {
        // DC8：实例名 <pid>#<nanoTime>（实测 27600#1181688713094900），与 Task 35 的 producer 同形；
        // 未启动过就 close、重复 close 都安全（实测两次 OK）——回滚与 stop() 因此能无条件调用。
        String instanceName = wiring.consumer().getInstanceName();
        assertThat(instanceName).startsWith(ProcessHandle.current().pid() + "#");
        assertThat(instanceName).isNotEqualTo("DEFAULT");

        assertThatCode(() -> {
            wiring.close();
            wiring.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void should_translate_a_declared_vendor_start_failure_with_full_context() {
        // DC9：厂商 start() 前第一步就是 checkConfig（实测 10 ms、cause=null、发生在任何网络动作之前），
        // 所以这一条能离线跑到"厂商声明过的启动异常"，同时证明"注册必须早于启动"在厂商侧有硬失败兜底
        // （与 CM2 的调用顺序断言互为对照）。
        wiring.subscribe("order-events", SUB_ALL);

        assertThatThrownBy(wiring::start)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("consumer-group=" + GROUP)
                // subscribed 列表唯一的读方就是这句诊断：漏了它，"启动失败时说的是哪批通道"没钉子。
                .hasMessageContaining("订阅通道=[order-events]")
                .hasMessageContaining("name-server=" + NAME_SERVER)
                .hasMessageContaining("messageListener is null")
                .hasCauseInstanceOf(MQClientException.class);

        // 失败启动留下的状态机仍然要关掉（shutdown 未启动安全，见 DC8）。
        assertThatCode(wiring::close).doesNotThrowAnyException();
    }
}

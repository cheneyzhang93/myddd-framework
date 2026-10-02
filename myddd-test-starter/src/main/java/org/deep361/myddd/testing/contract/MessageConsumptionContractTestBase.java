package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.deep361.myddd.kernel.port.message.InboundMessage;
import org.deep361.myddd.kernel.port.message.MessageHeaderNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @MessageListener} 侧的跨厂商契约：RocketMQ 与 Kafka 两个消费适配器必须把同一条线上消息
 * 翻译成<b>同一个形状</b>的 {@link InboundMessage}，并且对业务异常给出<b>同一个方向</b>的交接。
 *
 * <p>与 {@link MessagePortContractTestBase} 的分工：那个基类钉<b>写</b>方向（发出去的消息在厂商侧
 * 长什么样），它的回读用的是测试自己的录制器，<b>不经过适配器的消费路径</b>。本类钉<b>读</b>方向，
 * 是唯一能回答"换 provider 后我的业务方法会看到什么"这个问题的地方。
 *
 * <p><b>基类只断四件可跨厂商比较的事实</b>（Task 39 Step 0 第 4 条）：通道名、载荷、用户消息头、
 * 失败交接的<b>方向</b>。重投次数、耗尽后的落点、{@code messageId} 的拼法、组名来源、是否复用消费者
 * 实例都是厂商事实，出现在本类任何一行断言里都是错的——它们记在 {@code docs/mq/asymmetry.md}，
 * 由各 provider 自己的 {@code WF}/{@code CM}/{@code LM} 用例钉住。
 *
 * <p>子类要实现两类东西：一个动作缝 {@link #deliver(Delivery)}（把一条框架形状的投递翻译成厂商线消息、
 * 经<b>真适配器</b>喂进业务监听器，再把业务侧看到的东西原样报告回来），四个声明缝
 * （{@link #plainChannel()}／{@link #siblingChannel()}／{@link #failingChannel()}／{@link #businessFailure()}，
 * 告诉基类它那套监听器注册表里哪个通道能收、哪个通道会抛、抛的是哪一个异常实例）。基类<b>不</b>自己造
 * 通道名：注册表是 fixture 的事实，硬编码会让基类与两个 rig 互相追着改。
 */
public abstract class MessageConsumptionContractTestBase {

    /** 一次投递的要求：基类给的框架事实，子类负责翻译成厂商线消息。 */
    public record Delivery(String channel, String payload, Map<String, String> headers,
                           String eventId, String traceId, boolean failListener) {

        public Delivery {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    /** 失败交接的方向。只有两个取值，且基类只允许其中一个通过（见 {@code MC-C7}）。 */
    public enum FailureHandoff {
        /** 适配器把失败如实交给传输：RocketMQ 回 {@code RECONSUME_LATER}，Kafka 让异常抵达错误处理器。 */
        TRANSPORT_INFORMED,
        /** 适配器自己消化了异常并回执成功——消息静默丢失。任何 provider 走到这里都必须让 {@code MC-C7} 变红。 */
        SWALLOWED_AT_ADAPTER
    }

    /**
     * 业务侧的可观察结果。
     *
     * @param delivered           监听器实际收到的那条 {@link InboundMessage}；一次都没投到时为 {@code null}
     * @param handoff             失败交接方向
     * @param surfaced            传输侧看到的那个异常（Kafka 交给错误处理器的是原物；RocketMQ 的 seam 是
     *                            状态码、拿不到异常，此时为 {@code null}——这条差异属厂商事实，见不对称清单第 5 行）
     * @param listenerInvocations 业务监听器被调用的次数（跨通道串扰与"每投一次调一次"靠它断）
     */
    public record Observation(InboundMessage delivered, FailureHandoff handoff, Throwable surfaced,
                              int listenerInvocations) {
    }

    /** 把一次投递穿过真适配器。 */
    protected abstract Observation deliver(Delivery delivery);

    /** 有一个正常监听器的通道（{@code MC-C1}–{@code MC-C6}、{@code MC-C8} 的收件方）。 */
    protected abstract String plainChannel();

    /** 与 {@link #plainChannel()} 同组、各自独立监听器的另一个通道（{@code MC-C8} 用它断"不串扰"）。 */
    protected abstract String siblingChannel();

    /** 监听器恒抛 {@link #businessFailure()} 的通道（{@code MC-C7}）。 */
    protected abstract String failingChannel();

    /** {@link #failingChannel()} 上那个监听器所抛异常的<b>代表实例</b>（基类比类型与文案，见 {@code MC-C7}）。 */
    protected abstract RuntimeException businessFailure();

    /**
     * 该通道上监听器的<b>累计</b>调用次数（两个 rig 都有现成的计数器：RocketMQ 侧 {@code ConsumptionRig#countFor}、
     * Kafka 侧 Task 38 Step 7c 的同类方法）。基类只用<b>差值</b>，所以不要求归零，也因此在 Task 40 的真 broker 子类
     * 上同样可跑（那里没有"每条用例前重置"的便宜）。
     */
    protected abstract int listenerInvocationsOn(String channel);

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    private static Delivery plain(String channel, String payload, Map<String, String> headers) {
        return new Delivery(channel, payload, headers, null, null, false);
    }

    /** MC-C1：通道名逐字到达（RocketMQ 的 topic 与 Kafka 的 topic 映到同一个字段，不许加前缀或转大小写）。 */
    @Test
    void should_report_the_channel_verbatim() {
        Observation observation = deliver(plain(plainChannel(), "{\"a\":1}", Map.of()));
        assertEquals(plainChannel(), observation.delivered().channel());
    }

    /** MC-C2：载荷按 UTF-8 解码，中文与 emoji 不许变形；空串载荷不等于 {@code null}（内核 record 不许它为空）。 */
    @Test
    void should_decode_the_payload_as_utf_8() {
        for (String payload : new String[] {"{\"标题\":\"订单已支付 🙂\"}", ""}) {
            assertEquals(payload, deliver(plain(plainChannel(), payload, Map.of())).delivered().payload());
        }
    }

    /** MC-C3：用户头逐字到达，名字大小写保留、不加框架前缀。 */
    @Test
    void should_carry_user_headers_verbatim() {
        Map<String, String> user = new LinkedHashMap<>();
        user.put("x-Tenant", "T-7");
        user.put("retry-count", "3");
        InboundMessage delivered = deliver(plain(plainChannel(), "{}", user)).delivered();
        assertEquals("T-7", delivered.header("x-Tenant").orElse(null));
        assertEquals("3", delivered.header("retry-count").orElse(null));
    }

    /**
     * MC-C4：两个框架保留头原样穿过，且投递结束后调用线程上的 {@code TraceContext} 回到投递前的值
     * （厂商在自己的线程池上跑分发，漏复位会把上一条消息的链路 id 留给下一条）。
     */
    @Test
    void should_carry_the_framework_headers_and_reset_the_trace_after_dispatch() {
        TraceContext.set("trace-of-the-test-thread");
        Delivery delivery = new Delivery(plainChannel(), "{}", Map.of(), "evt-4", "trace-from-wire", false);
        InboundMessage delivered = deliver(delivery).delivered();
        assertEquals("evt-4", delivered.header(MessageHeaderNames.EVENT_ID).orElse(null));
        assertEquals("trace-from-wire", delivered.header(MessageHeaderNames.TRACE_ID).orElse(null));
        assertEquals("trace-from-wire", delivered.traceId().orElse(null));
        // "分发期间监听器线程上的 TraceContext 等于线消息里的 traceId"由 Task 34 的 dispatcher 用例钉
        // （那里是唯一能观察的地方）；本类只钉跨厂商的两半：头是否穿过、投递结束后是否复位。
        assertEquals("trace-of-the-test-thread", TraceContext.currentTraceId());
    }

    /**
     * MC-C5：{@code headers} 的键集必须<b>恰好</b>等于"基类声明的用户头 + 框架保留头"，一个厂商内部键都不许混进来。
     *
     * <p>RocketMQ 侧真会漏：厂商把 {@code KEYS}/{@code TAGS}/{@code WAIT} 这类系统属性塞在同一条消息的属性表里
     * （Task 36 的 {@code WF3} 逐个列过实测名单），摘除规则写在读方向映射里，本条是它在<b>经由适配器</b>之后的复验。
     * Kafka 侧没有内部头要摘（DLT 头只在恢复时加，而恢复不在这条路径上），因此本条对它是"确实无事可做"的通过，
     * 而不是假绿——这条差异本身记在不对称清单第 3 行。
     */
    @Test
    void should_not_leak_vendor_internal_header_keys() {
        Observation observation = deliver(new Delivery(plainChannel(), "{}", Map.of("x-a", "1"), "evt-5", null, false));
        assertEquals(Set.of("x-a", MessageHeaderNames.EVENT_ID), observation.delivered().headers().keySet());
    }

    /** MC-C6：每投一次调一次监听器，不批量、不重复；同一 {@code eventId} 的两次投递读到的头值相等（幂等依据稳定）。 */
    @Test
    void should_invoke_the_listener_exactly_once_per_delivery() {
        Delivery delivery = new Delivery(plainChannel(), "{}", Map.of(), "evt-6", null, false);
        int before = listenerInvocationsOn(plainChannel());
        Observation first = deliver(delivery);
        Observation second = deliver(delivery);
        assertEquals(1, first.listenerInvocations());
        assertEquals(1, second.listenerInvocations());
        assertEquals(before + 2, listenerInvocationsOn(plainChannel()));
        assertEquals(first.delivered().header(MessageHeaderNames.EVENT_ID),
                second.delivered().header(MessageHeaderNames.EVENT_ID));
    }

    /**
     * MC-C7：业务异常必须被<b>如实交给传输</b>，绝不允许在适配层被吞成"消费成功"。
     *
     * <p>{@code surfaced} 允许为 {@code null}：RocketMQ 的 seam 是状态码（{@code RECONSUME_LATER}，异常不外抛，
     * 见 Task 36 的 {@code LM2}），那里根本没有异常对象可看；Kafka 的 seam 是异常抵达错误处理器
     * （{@code onMessage} 现场抛出）。所以本条钉的是<b>两者共同的下界</b>——"不许假装成功"，
     * 加上"如果传输看到了异常，那必须是同一个业务异常（同类、同文案），不许被包装成
     * {@code FrameworkException} 或别的壳"。比的是类型与文案而不是实例：两个 rig 里的失败监听器
     * 都是<b>每次调用 new 一个</b>（"先记后抛"），实例身份在两侧都不可比。
     * "交给传输之后发生什么"（重投 16 次进 {@code %DLQ%}，还是重试 9 次后 commit 跳过）不在本条射程内：
     * 那两条断言在两个 provider 上必一真一假，见 Step 0 第 4 条与不对称清单第 1 行。
     */
    @Test
    void should_hand_a_business_failure_to_the_transport_instead_of_swallowing_it() {
        Observation observation = deliver(new Delivery(failingChannel(), "{}", Map.of(), null, null, true));
        assertEquals(FailureHandoff.TRANSPORT_INFORMED, observation.handoff());
        assertEquals(1, observation.listenerInvocations());
        if (observation.surfaced() != null) {
            assertEquals(businessFailure().getClass(), observation.surfaced().getClass());
            assertEquals(businessFailure().getMessage(), observation.surfaced().getMessage());
        }
    }

    /** MC-C8：同组兄弟通道不许被串扰——投给 A，B 的计数一格都不动（订阅模型错乱时最先露馅的就是这条）。 */
    @Test
    void should_not_cross_into_a_sibling_channel_of_the_same_group() {
        int plainBefore = listenerInvocationsOn(plainChannel());
        int siblingBefore = listenerInvocationsOn(siblingChannel());
        Observation observation = deliver(plain(plainChannel(), "{}", Map.of()));
        assertEquals(plainBefore + 1, listenerInvocationsOn(plainChannel()));
        assertEquals(siblingBefore, listenerInvocationsOn(siblingChannel()));
        // 只有这个等式能把两种实现区分开，缺它则子类交一个累计计数上来也照样全绿。
        assertEquals(observation.listenerInvocations(), listenerInvocationsOn(plainChannel()) - plainBefore);
    }

    /**
     * MC-C9：通道名<b>大小写敏感</b>——把通道名全大写再投一次，它既不许静默成功，也不许折叠回原通道。
     *
     * <p>这条是 {@code MC-C1} 的另一半：{@code MC-C1} 用的是夹具里的真实通道名（全小写），所以"某处偷偷
     * {@code toLowerCase()}"这种错误在它上面<b>读不出来</b>（Task 39 变异扫描 FX9 就是这个发现方式）。
     * 唯一的补法是投一个"只差大小写"的名字，于是本条同时钉住两件跨厂商一致的事：
     * 未订阅的通道必须被<b>拒绝</b>（分发器抛"该通道没有任何 @MessageListener"，Task 34 的既有语义），
     * 且原通道的计数一格都不许动。厂商侧的可见形态不同（RocketMQ 回 {@code RECONSUME_LATER}、Kafka 抛异常），
     * 但"方向"相同 ⇒ 与 {@code MC-C7} 同一类断言。
     */
    @Test
    void should_not_fold_the_case_of_a_channel_name() {
        int before = listenerInvocationsOn(plainChannel());
        Observation observation = deliver(plain(plainChannel().toUpperCase(Locale.ROOT), "{}", Map.of()));
        assertEquals(FailureHandoff.TRANSPORT_INFORMED, observation.handoff());
        assertNull(observation.delivered(), "大小写折叠后的投递不该有任何一条消息真的到达");
        assertEquals(before, listenerInvocationsOn(plainChannel()));
    }
}

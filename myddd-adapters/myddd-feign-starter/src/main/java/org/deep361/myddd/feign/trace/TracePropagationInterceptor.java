package org.deep361.myddd.feign.trace;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.deep361.myddd.kernel.common.trace.TraceContext;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 出站 traceId 透传：给每个 Feign 请求补上 {@code X-Trace-Id}，并在 id 符合 W3C 形态时补 {@code traceparent}。
 *
 * <p>三条刻意的取舍：
 * <ul>
 *   <li><b>只补空缺，不改已有</b>：调用方（或别的拦截器）已经写了这两个信头之一时原样保留。
 *       一是 {@code RequestTemplate#header} 是<b>追加</b>语义，重复写会变成同名两个值；
 *       二是显式声明代表调用方的意图，框架没有理由覆盖它。</li>
 *   <li><b>不合规的 id 一个信头都不发</b>：内核 {@code TraceContext#set} 刻意不校验格式（入站 id 可能来自
 *       别家实现），而含空白或控制字符的信头值会被 HTTP 客户端直接拒绝——为了让一段链路把整次调用弄失败是
 *       本末倒置。因此这里按 web 入站同一份白名单（{@code [A-Za-z0-9_-]{1,64}}）自检，不过就静默跳过；
 *       {@code traceparent} 另加"32 位小写十六进制且非全零"这一层，因为它的格式是规范契约。</li>
 *   <li><b>没有 traceId 时生成并绑定到当前线程</b>（{@link TraceContext#ensureTraceId()}）：定时任务、
 *       MQ 消费者等没有 HTTP 入站的起点，第一次出站就建立 id，本线程后续日志与对端拿到同一个 id。
 *       生命周期由起点负责（web 过滤器清、任务/消费者自行清），本拦截器只借不还。</li>
 * </ul>
 */
public class TracePropagationInterceptor implements RequestInterceptor {

    /** W3C Trace Context 的信头名；小写是规范要求，也是 HTTP/2 的线上传输形态。 */
    public static final String TRACE_PARENT_HEADER = "traceparent";

    private static final String VERSION = "00";
    private static final String FLAGS_SAMPLED = "01";
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final Pattern HEADER_SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern W3C_TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @Override
    public void apply(RequestTemplate template) {
        String traceId = TraceContext.ensureTraceId();
        if (!HEADER_SAFE_TRACE_ID.matcher(traceId).matches()) {
            return;
        }
        if (!isAlreadySet(template, TraceContext.TRACE_ID_HEADER)) {
            template.header(TraceContext.TRACE_ID_HEADER, traceId);
        }
        if (!isAlreadySet(template, TRACE_PARENT_HEADER)
                && W3C_TRACE_ID.matcher(traceId).matches() && !ALL_ZERO_TRACE_ID.equals(traceId)) {
            template.header(TRACE_PARENT_HEADER, VERSION + "-" + traceId + "-" + newSpanId() + "-" + FLAGS_SAMPLED);
        }
    }

    /**
     * 大小写不敏感地判断"调用方是否已经给了这个信头且给了非空值"。
     *
     * <p>值非空这一半是按防御留的：一手实测（feign-core 13.6.1）{@code RequestTemplate} 会把空值集合的
     * 信头整个丢掉——{@code headers(Map.of("X-Trace-Id", List.of()))} 与 {@code header("X-Trace-Id")}
     * 之后 {@code headers()} 都是 {@code {}}。所以在当前三方契约下这条判断永不为假（对应变异按等价处理，
     * 见本任务计划的变异账），但"信头名在、值为空"一旦被 feign 改成保留，它的正确语义就是"视为没给"，
     * 而不是让链路信头以空值发出去。
     */
    private static boolean isAlreadySet(RequestTemplate template, String name) {
        for (Map.Entry<String, Collection<String>> header : template.headers().entrySet()) {
            if (name.equalsIgnoreCase(header.getKey()) && !header.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 16 位小写十六进制的 spanId。取值域从 1 开始而不是"随机后重摇"：W3C 规定全零 span-id 非法，
     * 下界写进 {@code nextLong(1L, …)} 让这件事在类型上就不可能发生（也省掉一条永远走不到的循环分支）。
     * 代价是少一位熵（63 位而非 64 位），对"链路内区分一次跨度"这个用途没有影响。
     */
    private static String newSpanId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE));
    }
}

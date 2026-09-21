package org.deep361.myddd.kernel.common.trace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 全链路 traceId 上下文（ThreadLocal 持有）。
 *
 * <p>traceId 为 32 位十六进制（与 W3C trace-id 等长），由 web / feign / mq / job 适配器在链路边界
 * 读写信头 {@link #TRACE_ID_HEADER} 完成透传；领域层与应用层只通过本类读取当前值。
 *
 * <p>值为<b>线程私有</b>：不继承到子线程，异步/线程池场景需由适配器在任务提交时显式读取并重新
 * {@link #set(String)}。
 *
 * <p><b>适配器边界的推荐写法</b>：进入边界时先保存 {@link #currentTraceId()} 的原值（在请求线程内发起
 * Feign 调用时原值非 null），再 {@link #set(String)} 新值；退出时在 finally 中恢复原值——原值为 null 时
 * {@link #set(String)} 会拒绝，改用 {@link #clear()}。线程池复用时若不清理，下一个任务会读到上一个任务的
 * 残留 traceId。
 */
public final class TraceContext {

    /** 链路透传统一使用的 HTTP / 消息头名。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** 当前线程 traceId；不存在时返回 null。 */
    public static String currentTraceId() {
        return CURRENT.get();
    }

    /**
     * 绑定 traceId 到当前线程。
     *
     * <p>只校验非空白，<b>不校验格式</b>：入站信头可能是 W3C 之外的实现自定义的 id，长度与字符集都不受
     * 本类约束，合法性校验属于适配器边界的职责。校验失败时原有绑定保持不变。
     *
     * @param traceId 待绑定的 traceId，不可为 {@code null} 或空白
     * @throws IllegalArgumentException {@code traceId} 为 {@code null} 或空白
     */
    public static void set(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不能为 null 或空白");
        }
        CURRENT.set(traceId);
    }

    /** 已存在则复用（入站 {@link #set(String)} 的 id 优先于生成），否则生成并绑定到当前线程后返回。 */
    public static String ensureTraceId() {
        String existing = CURRENT.get();
        if (existing != null) {
            return existing;
        }
        String generated = newTraceId();
        CURRENT.set(generated);
        return generated;
    }

    /** 移除当前线程的 traceId；未绑定时为空操作。 */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 生成新的 32 位十六进制 traceId（不绑定当前线程）。
     *
     * <p>随机源为 {@link ThreadLocalRandom}，128 位取值仅用于链路区分，<b>不具备密码学强度</b>，
     * 不可当作密钥、令牌或幂等键使用。
     */
    public static String newTraceId() {
        long high = ThreadLocalRandom.current().nextLong();
        long low = ThreadLocalRandom.current().nextLong();
        return String.format("%016x%016x", high, low);
    }
}

package org.deep361.myddd.resilience;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.RateLimit;
import org.deep361.myddd.kernel.port.resilience.RateLimitPort;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

/**
 * {@link RateLimit} 的执行侧：算资源名、按 R8 在预算内重试 {@link RateLimitPort#tryAcquire}、
 * 拿到许可就放行，拿不到就说清"为什么没拿到、还试了几次"。
 *
 * <p><b>这里没有阈值语义</b>：每秒允许多少、按什么维度限流，全在 provider 的规则里（Task 45/46）。
 * 端口签名只有 {@code (resource, permits)}，{@code permits} 是"这一次请求消耗几个许可"，
 * 所以注解 {@code permits()==0}（内核 javadoc："由外部配置决定"）在本装配下唯一的非自相矛盾读法是
 * <b>归一为 1</b>：本次请求要一个许可，给不给由规则决定（`RL1`）。写成负数是形态错误，快速失败（`RL3`）。
 *
 * <p><b>等待为什么不放进 provider</b>：{@link RateLimitPort} 的 javadoc 明写"非阻塞"，而
 * {@code @RateLimit.timeout} 的 javadoc 明写"等待由适配器切面在端口之上实现"。两边合起来就是 R8：
 * 切面循环调一个非阻塞端口，两家 provider 因此天然等价——这也是本任务不需要等待语义等价性用例的理由，
 * 等待的所有可观察面都在这一层。
 *
 * <p><b>尝试次数算出来而不是等墙钟</b>（Step 0 第 5 条）：间隔 {@code timeout/8} 夹在
 * <code>[{@value #MIN_POLL_INTERVAL_MILLIS}ms, {@value #MAX_POLL_INTERVAL_MILLIS}ms]</code>，
 * 预算取 {@code min(timeout, 护栏)}，总尝试次数 {@code 1 + 预算/间隔}。用 deadline 会让同一个注解的
 * 尝试次数随机器负载漂移，用例只能写成区间；用次数则"预算内多试几次"这件事在两侧都是逐字可断的。
 *
 * <p><b>截断要能被看见，但不靠日志</b>（Step 0 第 6 条）：拒绝异常携带六个机读数
 * {@code [注解 timeout, 实际预算, 护栏值, 间隔, 计划次数, 实际次数]}（毫秒，最后两个为整数），
 * 经 {@link BizException#getMessageArgs()} 读出。被限流是预期路径而不是异常路径，hot 路径上的 WARN
 * 在攻击流量下就是日志洪水；而"只说一次"要一个新的状态位，本任务不为一条日志引入它（"本任务不做"第 5 行）。
 * 六个数同时进了 i18n 重解析的参数面，而 {@code TOO_MANY_REQUESTS} 的文案没有占位符，
 * 所以展示文案一个字都不变——诊断走机器可读的那条线。
 *
 * <p><b>中断归一</b>：等待被打断时恢复中断标志后按同一条身份拒绝。吞掉中断会让上层取消逻辑
 * （线程池 shutdown、Web 异步超时）看不到这里曾经被取消；而把它翻译成 {@code InterruptedException}
 * 之外的第二种错误码，等于让"被取消"和"被限流"在监控上分家。
 *
 * <p><b>{@code @Order(-5)}：限流在熔断外面</b>。与 {@code CircuitBreakerAspect} 的 {@code -4}、
 * {@code IdempotentAspect} 的 {@code -1}、{@code DistributedLockAspect} 的 {@code 0} 连读成五格，
 * 依据是探针 U6 的实测嵌套（值小者在外）。关系只有一条：<b>一次限流拒绝绝不该记成一次熔断失败</b>——
 * 否则针对某接口的流量攻击会把下游整个服务熔掉，而那正是限流要防的事（`OR1`）。
 */
@Aspect
@Order(RateLimitAspect.ASPECT_ORDER)
public class RateLimitAspect {

    /** 五格顺序表的最外一格（见类 javadoc）。 */
    public static final int ASPECT_ORDER = -5;

    static final long MIN_POLL_INTERVAL_MILLIS = 1L;
    static final long MAX_POLL_INTERVAL_MILLIS = 50L;
    static final int POLL_INTERVAL_DIVISOR = 8;

    private final ObjectProvider<RateLimitPort> rateLimitPorts;
    private final long busyWaitCeilingMillis;
    private final PollSleeper sleeper;
    private final ResourceKeyResolver keys = new ResourceKeyResolver("RateLimit");

    /**
     * @param rateLimitPorts      端口接缝；收 {@link ObjectProvider} 而不是端口本身，与
     *                            {@code IdempotentAspect} 同一条理由：声明面不该要求某一种
     *                            provider 实现在场，缺口的报话在<b>第一次调用</b>时给出（`RL14`）
     * @param busyWaitCeilingMillis 注解 {@code timeout} 的上界护栏（毫秒）；由自动配置从
     *                            {@code myddd.resilience.rate-limit.timeout-max-busy-wait} 换算，
     *                            整毫秒已由 {@code MydddResilienceProperties#validate()} 在启动期保证
     * @param sleeper             等待节拍接缝，生产装配传 {@link ThreadPollSleeper}
     */
    public RateLimitAspect(ObjectProvider<RateLimitPort> rateLimitPorts, long busyWaitCeilingMillis,
            PollSleeper sleeper) {
        if (rateLimitPorts == null || sleeper == null) {
            throw new FrameworkException("RateLimitAspect 需要 RateLimitPort 的 ObjectProvider 与一个"
                    + " PollSleeper，装配里拿到 null 说明调用写错了——这不是业务会碰到的路径");
        }
        this.rateLimitPorts = rateLimitPorts;
        this.busyWaitCeilingMillis = busyWaitCeilingMillis;
        this.sleeper = sleeper;
    }

    /**
     * 环绕"方法上有 {@link RateLimit} 或所属类上有 {@link RateLimit}"的连接点。
     *
     * <p>切点<b>不绑定注解形参</b>（探针 U1：绑定式析取会把方法级注解读出 {@code NULL}、
     * 把方法级与类型级并存读出类型级）。注解实例由 {@link ResourceKeyResolver#declared} 按
     * "先方法后类"取，与切点匹配用的是同一套 find-merged 语义，所以不存在
     * "切点认为该切、切面读不到注解"的裂缝；反过来也不需要 null 分支——两级都读不到时切点根本不会命中。
     */
    @Around("@annotation(org.deep361.myddd.kernel.port.resilience.RateLimit) "
            + "|| @within(org.deep361.myddd.kernel.port.resilience.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object target = joinPoint.getTarget();
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
        Method invoked = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = ClassUtils.getMostSpecificMethod(invoked, targetClass);
        RateLimit spec = this.keys.declared(method, targetClass, RateLimit.class);
        String resource = this.keys.resourceText(spec.key(), method, joinPoint.getArgs(), target, targetClass);
        int permits = permits(spec, method);
        long declaredTimeout = timeout(spec, method);
        RateLimitPort port = requireRateLimitPort(resource);

        long budget = Math.min(declaredTimeout, this.busyWaitCeilingMillis);
        long interval = declaredTimeout == 0L ? 0L : clampInterval(declaredTimeout / POLL_INTERVAL_DIVISOR);
        int plannedAttempts = interval == 0L ? 1 : (int) (budget / interval) + 1;

        for (int attempt = 1; ; attempt++) {
            if (port.tryAcquire(resource, permits)) {
                return joinPoint.proceed();
            }
            if (attempt >= plannedAttempts) {
                throw rejection(spec, resource, declaredTimeout, budget, interval, plannedAttempts, attempt);
            }
            try {
                this.sleeper.sleep(interval);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw rejection(spec, resource, declaredTimeout, budget, interval, plannedAttempts, attempt,
                        interrupted);
            }
        }
    }

    /**
     * {@code permits} 的两个合法形态：{@code 0}（缺省）归一为一次一个许可，正数原样。
     * 负数在<b>调端口之前</b>拒绝——它连"要几个许可"都说不清，端口拿到只会收到一个更晚的
     * {@code IllegalArgumentException}，而那条消息里没有注解名也没有方法名。
     */
    private static int permits(RateLimit spec, Method method) {
        int declared = spec.permits();
        if (declared < 0) {
            throw new FrameworkException("@RateLimit(permits=" + declared + ") 在 "
                    + ResourceKeyResolver.describe(method) + " 上是负数：permits 是“本次请求消耗几个许可”，"
                    + "负数端口无法解释（它的实现契约要求 ≥1）。要“本次不额外消耗”请写 0，"
                    + "它会归一为一次一个许可（阈值归 provider 的规则决定）");
        }
        return declared == 0 ? 1 : declared;
    }

    private static long timeout(RateLimit spec, Method method) {
        long declared = spec.timeout();
        if (declared < 0) {
            throw new FrameworkException("@RateLimit(timeout=" + declared + ") 在 "
                    + ResourceKeyResolver.describe(method) + " 上是负数：timeout 是“获取许可的最长等待毫秒数”"
                    + "（内核 javadoc 原文），负预算会让“等待”退化成一次都不试还得不到一句说明。"
                    + "不等待请写 0（缺省）");
        }
        return declared;
    }

    private static long clampInterval(long rawInterval) {
        return Math.min(Math.max(rawInterval, MIN_POLL_INTERVAL_MILLIS), MAX_POLL_INTERVAL_MILLIS);
    }

    private BizException rejection(RateLimit spec, String resource, long declaredTimeout, long budget,
            long interval, int plannedAttempts, int attemptsMade) {
        return rejection(spec, resource, declaredTimeout, budget, interval, plannedAttempts, attemptsMade, null);
    }

    /**
     * 拒绝的构造：注解文案非空时只换展示文案（R12），错误身份、HTTP 429、i18n 键与那六个机读数一律不变。
     *
     * <p>第六个数 {@code attemptsMade} 是"计划次数"与"实际次数"的分离点：预算耗尽时两者相等，
     * 被中断时前者更大——排查"为什么只试了两次"时，这两个数是唯一能区分两种解释的证据。
     */
    private BizException rejection(RateLimit spec, String resource, long declaredTimeout, long budget,
            long interval, int plannedAttempts, int attemptsMade, Throwable cause) {
        Object[] diagnostics = {declaredTimeout, budget, this.busyWaitCeilingMillis, interval,
                plannedAttempts, attemptsMade};
        String message = spec.message();
        if (message.isBlank()) {
            return cause == null ? new BizException(CommonErrorCode.TOO_MANY_REQUESTS, diagnostics)
                    : BizException.causedBy(CommonErrorCode.TOO_MANY_REQUESTS, cause, diagnostics);
        }
        return new RateLimitRejection(message, cause, diagnostics);
    }

    private RateLimitPort requireRateLimitPort(String resource) {
        RateLimitPort port = this.rateLimitPorts.getIfAvailable();
        if (port == null) {
            throw new FrameworkException("@RateLimit 需要一个 RateLimitPort bean，容器里没有它，"
                    + "被拒绝的资源名是 " + resource + "。要么引一个限流 provider："
                    + "myddd-limiter-sentinel-starter（myddd.limiter.sentinel.enabled=true，缺省）或"
                    + " myddd-limiter-resilience4j-starter（myddd.limiter.resilience4j.enabled=true，缺省）；"
                    + "要么自己提供一个 RateLimitPort 实现——切面只按类型取，不关心它是谁建的。"
                    + "写 myddd.resilience.enabled=false 关掉的是<b>切面本身</b>：那之后注解一次都不会被拦，"
                    + "既不报错也不日志，这是本框架唯一被支持的静默形态，代价请见 package-info");
        }
        return port;
    }
}

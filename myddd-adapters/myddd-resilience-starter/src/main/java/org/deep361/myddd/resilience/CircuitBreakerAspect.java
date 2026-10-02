package org.deep361.myddd.resilience;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.resilience.CircuitBreaker;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * {@link CircuitBreaker} 的执行侧：算资源名、把业务方法整体交给 {@link CircuitBreakerPort}、
 * 只在"端口真的把这次请求挡在门外"时走同类内的 fallback。
 *
 * <p><b>三个阈值不到达 provider，这是端口的形状决定的</b>：{@link CircuitBreakerPort#execute} 只收
 * {@code (resource, action)}，没有任何参数通道能承载 {@code failureRateThreshold} /
 * {@code slidingWindowSize} / {@code waitDurationInOpenState}。所以本类只做两件事——<b>形态校验</b>
 * （负数、超过 100 的百分比一律启动后第一次调用就拒）和<b>不假装生效</b>（诊断与 package-info 都写明
 * "阈值归 provider 的配置面"）。与 {@code @RateLimit.permits} 不同：那个数端口收得下，这三个数端口收不下；
 * 硬把它们塞进某个"看起来像"的地方，得到的就是两家 provider 行为不一致而用例全绿。等价性表里那一行
 * 由 Task 47 承载（偏差 D-144）。
 *
 * <p><b>为什么"动作有没有跑起来"是唯一的区分点</b>：内核给端口的 javadoc 让"熔断打开"抛
 * {@code BizException(SERVICE_DEGRADED)}，而业务方法自己完全可以抛同一个异常（同一个错误码、同一个类）。
 * 两类事实在类型上无法分辨，只有一个布尔值能分辨：拒绝发生在 {@code action.get()} <b>之前</b>就是端口挡的，
 * 发生在<b>之后</b>就是业务抛的。端口实现是否真的按这个顺序无所谓——本类只观察自己交给它的那段动作
 * 有没有被执行，所以这条判据不依赖任何 provider 的行为（`CB2` 与 `CB4` 是这条判据的两面）。
 *
 * <p><b>fallback 只在端口拒绝时调用</b>：注解留空 = 不降级（内核 javadoc 原文），非空 = 同类内查找。
 * 业务异常绝不触发 fallback——否则一次 NPE 会被静默换成一个"看起来正常"的返回值，那是最难查的一类事故。
 *
 * <p><b>fallback 在裸目标上调用</b>（探针 F3）：{@code joinPoint.getTarget()} 而不是代理。理由有两条，
 * 一条是行为、一条是事故面：在代理上调会让 fallback 自身再被同一条 {@code @CircuitBreaker} 拦一次
 * （端口已拒 → 再次拒绝 → 再次找 fallback），这是无限递归的入口。代价是 fallback 上的注解不生效
 * （`CB14`），出路写在 package-info：需要注解就给自身代理。
 *
 * <p><b>查找规则定死成两格</b>（探针 F7/F8/F9）：候选名 = {@code 前缀 + 首字母大写的注解原文}（即
 * {@code cb + both → cbBoth}）与 {@code 注解原文}
 * （缺省前缀为空串时只剩后者一个）；每个候选按"与原方法同参数"优先、"同参数 + {@code Throwable} 尾参"
 * 次之。跨继承链查找（{@link ReflectionUtils#findMethod}），私有方法可达（{@code makeAccessible}），
 * 返回值必须与原方法兼容。<b>两个候选都命中就快速失败</b>——"先试前缀、试不到退回原文"是一种静默偏好，
 * 团队里两个人对同一个注解读出不同的方法，而用例全绿。
 *
 * <p><b>{@code @Order(-4)}：熔断在限流里面</b>（探针 U6，值小者在外）。五格顺序表的完整读法写在
 * {@code RateLimitAspect} 的类 javadoc 里；这里的硬关系是"被限流的请求不该算进熔断的失败率"（`OR1`）。
 */
@Aspect
@Order(CircuitBreakerAspect.ASPECT_ORDER)
public class CircuitBreakerAspect {

    /** 五格顺序表的第二格（最外是 {@link RateLimitAspect#ASPECT_ORDER}）。 */
    public static final int ASPECT_ORDER = -4;

    static final int MAX_FAILURE_RATE_PERCENT = 100;

    private final ObjectProvider<CircuitBreakerPort> circuitBreakerPorts;
    private final String fallbackMethodPrefix;
    private final ResourceKeyResolver keys = new ResourceKeyResolver("CircuitBreaker");

    /**
     * @param circuitBreakerPorts 端口接缝；收 {@link ObjectProvider} 的理由与限流侧一致（`CB15` 的对岸：
     *                            容器缺端口不是启动期事故）
     * @param fallbackMethodPrefix {@code myddd.resilience.fallback.method-prefix}，可为空串但不可为
     *                            {@code null}（空串校验归 {@code MydddResilienceProperties#validate()}）
     */
    public CircuitBreakerAspect(ObjectProvider<CircuitBreakerPort> circuitBreakerPorts,
            String fallbackMethodPrefix) {
        if (circuitBreakerPorts == null || fallbackMethodPrefix == null) {
            throw new FrameworkException("CircuitBreakerAspect 需要 CircuitBreakerPort 的 ObjectProvider 与"
                    + " fallback 前缀（可为空串，不可为 null），装配里拿到 null 说明调用写错了");
        }
        this.circuitBreakerPorts = circuitBreakerPorts;
        this.fallbackMethodPrefix = fallbackMethodPrefix;
    }

    /**
     * 环绕"方法上有 {@link CircuitBreaker} 或所属类上有 {@link CircuitBreaker}"的连接点。切点写法与
     * 限流侧同源（探针 U1 禁用绑定式析取），注解实例同样由 {@link ResourceKeyResolver#declared} 按
     * "先方法后类"取。
     */
    @Around("@annotation(org.deep361.myddd.kernel.port.resilience.CircuitBreaker) "
            + "|| @within(org.deep361.myddd.kernel.port.resilience.CircuitBreaker)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object target = joinPoint.getTarget();
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
        Method invoked = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = ClassUtils.getMostSpecificMethod(invoked, targetClass);
        CircuitBreaker spec = this.keys.declared(method, targetClass, CircuitBreaker.class);
        validateThresholds(spec, method);
        String resource = this.keys.resourceText(spec.key(), method, joinPoint.getArgs(), target, targetClass);
        CircuitBreakerPort port = requireCircuitBreakerPort(resource);

        // 唯一的判据：这段动作有没有真的跑起来。端口"挡在门外"发生在 action.get() 之前，业务自己抛
        // 同一个 SERVICE_DEGRADED 发生在之后（类 javadoc 第三段）。
        boolean[] actionStarted = {false};
        Supplier<Object> guarded = () -> {
            actionStarted[0] = true;
            return proceedBoxed(joinPoint);
        };
        try {
            return port.execute(resource, guarded);
        } catch (ActionThrowableFailure boxed) {
            throw boxed.cause();
        } catch (BizException failure) {
            boolean refusedBeforeAction = !actionStarted[0]
                    && failure.getErrorCode() == CommonErrorCode.SERVICE_DEGRADED;
            if (refusedBeforeAction && !spec.fallbackMethod().isBlank()) {
                return invokeFallback(findFallback(spec, method, targetClass), target, joinPoint.getArgs(),
                        failure);
            }
            throw failure;
        }
    }

    /**
     * 三项阈值的形态校验。{@code 0} 是内核 javadoc 认可的"由外部配置决定"，正数按各自的定义域校验：
     * 失败率是百分比（{@code 1..100}）、窗口是次数（{@code >= 1}）、打开后等待是毫秒（{@code >= 1}）。
     *
     * <p><b>校验发生在取端口之前</b>（`CB15` 的第二个断言就是端口的资源清单为空）：一个写错的注解值是
     * 应用作者的错，不该先惊动 provider、更不该在"熔断已经打开"的响应里被顺带吞掉。
     */
    private static void validateThresholds(CircuitBreaker spec, Method method) {
        int rate = spec.failureRateThreshold();
        if (rate < 0 || rate > MAX_FAILURE_RATE_PERCENT) {
            throw new FrameworkException("@CircuitBreaker(failureRateThreshold=" + rate + ") 在 "
                    + ResourceKeyResolver.describe(method) + " 上不是百分比：期望 0（由外部配置决定）或 1–"
                    + MAX_FAILURE_RATE_PERCENT + " 之间的整数。本框架不把它换算成任何 provider 的口径"
                    + "（端口的 execute 签名不带阈值参数），所以这个数只会出现在诊断里，不会生效");
        }
        int window = spec.slidingWindowSize();
        if (window < 0) {
            throw new FrameworkException("@CircuitBreaker(slidingWindowSize=" + window + ") 在 "
                    + ResourceKeyResolver.describe(method) + " 上是负数：期望 0（由外部配置决定）或 ≥1 的"
                    + "统计次数。同上，这个数在本装配下不参与任何调用");
        }
        long wait = spec.waitDurationInOpenState();
        if (wait < 0) {
            throw new FrameworkException("@CircuitBreaker(waitDurationInOpenState=" + wait + ") 在 "
                    + ResourceKeyResolver.describe(method) + " 上是负毫秒数：期望 0（由外部配置决定）或 ≥1"
                    + " 的打开后等待毫秒。同上，这个数在本装配下不参与任何调用");
        }
    }

    /**
     * 候选名：先"前缀 + 首字母大写的注解原文"，再"注解原文"；前缀为空串时只有后者一个。
     *
     * <p>拼接时把注解原文首字母大写，是因为 Java 里"给方法名加前缀"的写法就是 {@code cb + both → cbBoth}
     * （驼峰）；按原文直接拼会得出 {@code cbboth}，那要求业务侧写一个没人会这么命名的方法。
     * 首字母大写只对<b>非空</b>原文做——注解留空表示不降级，走到这里就已经改了契约。
     */
    private List<String> candidateNames(String declaredName) {
        Set<String> names = new LinkedHashSet<>();
        if (!this.fallbackMethodPrefix.isEmpty() && !declaredName.isEmpty()) {
            names.add(this.fallbackMethodPrefix + Character.toUpperCase(declaredName.charAt(0))
                    + declaredName.substring(1));
        }
        names.add(declaredName);
        return new ArrayList<>(names);
    }

    /**
     * 找一个"签名兼容"的同类方法：同名候选里先要原参数表，其次要"原参数表 + 一个 {@code Throwable} 尾参"。
     * 两个候选名各自解出一个方法 ⇒ 歧义，快速失败（类 javadoc 第五段）。
     */
    private Method findFallback(CircuitBreaker spec, Method guarded, Class<?> targetClass) {
        List<String> names = candidateNames(spec.fallbackMethod());
        List<Method> resolved = new ArrayList<>();
        for (String name : names) {
            Method exact = ReflectionUtils.findMethod(targetClass, name, guarded.getParameterTypes());
            if (exact != null) {
                resolved.add(exact);
                continue;
            }
            Class<?>[] withCause = appendType(guarded.getParameterTypes(), Throwable.class);
            Method causeTaking = ReflectionUtils.findMethod(targetClass, name, withCause);
            if (causeTaking != null) {
                resolved.add(causeTaking);
            }
        }
        if (resolved.isEmpty()) {
            throw new FrameworkException("@CircuitBreaker(fallbackMethod=" + names + ") 在 "
                    + ResourceKeyResolver.describe(guarded) + " 的同类（含父类）里找不到兼容的方法。"
                    + "要求：返回类型可赋给被保护方法的返回类型，参数是 "
                    + java.util.Arrays.toString(guarded.getParameterTypes()) + " 或其后加一个 Throwable；"
                    + "fallback 前缀是 " + describePrefix() + "（键 myddd.resilience.fallback.method-prefix，"
                    + "空串表示只按注解原文找）。注解留空表示不降级（内核 javadoc），要给降级就写方法名");
        }
        if (resolved.size() > 1) {
            throw new FrameworkException("@CircuitBreaker(fallbackMethod=\"" + spec.fallbackMethod()
                    + "\") 的两个候选名各解出一个兼容方法：" + resolved.stream().map(ResourceKeyResolver::describe)
                    .toList() + "。本框架不在“前缀名”与“原名”之间做静默偏好——请去掉其中一个，"
                    + "或改掉 myddd.resilience.fallback.method-prefix（当前 " + describePrefix() + "）");
        }
        Method fallback = resolved.get(0);
        boolean compatible = guarded.getReturnType() == void.class
                ? fallback.getReturnType() == void.class
                : guarded.getReturnType().isAssignableFrom(fallback.getReturnType());
        if (!compatible) {
            throw new FrameworkException("fallback " + ResourceKeyResolver.describe(fallback) + " 的返回类型 "
                    + fallback.getReturnType().getName() + " 无法赋给被保护方法 "
                    + guarded.getReturnType().getName() + "。不检查就会等到第一次真降级时抛 ClassCastException——"
                    + "那是最难复现的一类故障，因为它只在下游挂掉时发生");
        }
        return fallback;
    }

    private String describePrefix() {
        return this.fallbackMethodPrefix.isEmpty() ? "空串" : "\"" + this.fallbackMethodPrefix + "\"";
    }

    /**
     * 调用 fallback：接收者是<b>裸目标</b>（探针 F1/F3），反射调用前 {@code makeAccessible}（F9：夹具与切面
     * 不同包，私有 fallback 不走 {@code setAccessible} 就抛 {@code IllegalAccessException}），
     * {@code InvocationTargetException} 解包后原样重抛（F8：不吞、不重新包装、不加第二层业务码）。
     */
    private static Object invokeFallback(Method fallback, Object target, Object[] arguments,
            BizException refusal) throws Throwable {
        boolean takesCause = fallback.getParameterCount() == arguments.length + 1;
        Object[] actual = takesCause ? appendArgument(arguments, refusal) : arguments;
        ReflectionUtils.makeAccessible(fallback);
        try {
            return fallback.invoke(target, actual);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getTargetException();
        } catch (IllegalAccessException | IllegalArgumentException broken) {
            throw new FrameworkException("反射调用 fallback " + ResourceKeyResolver.describe(fallback)
                    + " 失败：" + broken.getMessage() + "。这一步不该失败——makeAccessible 已经做过，"
                    + "参数表也按签名兼容校验过，走到这里说明查找与调用两侧的规则不一致");
        }
    }

    /** 参数表的两种合法形态：与被保护方法逐位同型，或在其后加一个 {@code Throwable}（探针 F6 的读数面）。 */
    private static Class<?>[] appendType(Class<?>[] base, Class<?> extra) {
        Class<?>[] result = new Class<?>[base.length + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        result[base.length] = extra;
        return result;
    }

    private static Object[] appendArgument(Object[] base, Object extra) {
        Object[] result = new Object[base.length + 1];
        System.arraycopy(base, 0, result, 0, base.length);
        result[base.length] = extra;
        return result;
    }

    private static Object proceedBoxed(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new ActionThrowableFailure(failure);
        }
    }

    private CircuitBreakerPort requireCircuitBreakerPort(String resource) {
        CircuitBreakerPort port = this.circuitBreakerPorts.getIfAvailable();
        if (port == null) {
            throw new FrameworkException("@CircuitBreaker 需要一个 CircuitBreakerPort bean，容器里没有它，"
                    + "被保护的资源名是 " + resource + "。要么引一个限流/熔断 provider："
                    + "myddd-limiter-sentinel-starter（myddd.limiter.sentinel.enabled=true，缺省）或"
                    + " myddd-limiter-resilience4j-starter（myddd.limiter.resilience4j.enabled=true，缺省）；"
                    + "要么自己提供一个 CircuitBreakerPort 实现");
        }
        return port;
    }
}

package org.deep361.myddd.consistency.lock;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.deep361.myddd.consistency.support.MethodKeyExpressions;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.ClassUtils;

/**
 * {@link DistributedLock} 的执行侧：算出锁键与时长，然后<b>只调用 {@link LockPort}</b>。
 *
 * <p><b>这里没有锁语义</b>：等待、租约、watchdog、拿不到锁抛什么、释放前是否还持锁——全部在
 * {@link LockPort} 的实现里（Task 24 的 {@code DefaultLockPort}）。切面重复一遍那套规则会得到两个
 * 可能不同步的真相，所以本类的可观察职责只有三件：切点匹配、SpEL 求值、时长解析。
 *
 * <p><b>{@code @Order(0)}，不是最低优先级</b>：锁必须包住事务而不是被事务包住。若切面在事务通知<b>之内</b>，
 * 解锁发生在提交<b>之前</b>——第二个线程能在第一个线程尚未提交的数据上开事务，锁形同虚设。Spring 的事务
 * 通知缺省是最低优先级，所以任何有限的更高优先级都够；这里取 0 而不是 {@code HIGHEST_PRECEDENCE}，
 * 给比锁更外的切面（全链路、鉴权）留出负数区间。顺序由 {@code DistributedLockAspectTest} 用一个
 * 最低优先级的"事务替身"通知取证，不是写在注释里的承诺。
 *
 * <p><b>动作抛出的原始异常原样透传</b>：{@code proceed()} 能抛出任何 {@link Throwable}，而
 * {@link LockPort} 收的是 {@code Supplier}（不能抛受检异常）。这里用一个私有包装类把非运行时异常暂时装箱、
 * 出锁以后解开重抛，所以方法签名上声明的 {@code IOException} 到调用方还是 {@code IOException}——不会被换成
 * {@code RuntimeException} 而绕过 {@code catch (IOException)} 与 {@code throws} 声明。装箱是必需的妥协，
 * 解开是承诺。
 *
 * <p><b>时长字面量按原文缓存</b>：注解里的串是编译期常量，不同串的数量受代码规模约束，每次调用重新解析会把
 * 一个纯优化变成热点路径上的固定开销。键表达式的解析、缓存与三类失败话术在 {@link MethodKeyExpressions} 里，
 * 与 {@code @Idempotent} 共用同一份（两处各写一份会得到两套会各自漂移的话术）。
 */
@Aspect
@org.springframework.core.annotation.Order(0)
public class DistributedLockAspect {

    /**
     * 键表达式的解析与求值交给 {@link MethodKeyExpressions}：锁与幂等两切的"从方法上算出一把键"是同一段逻辑，
     * 各写一份会得到三段会各自漂移的话（三类失败话术各有各的义务）。差异只剩消息里的三个名字，
     * 因此它们是构造参数。
     */
    private final MethodKeyExpressions keys = new MethodKeyExpressions("DistributedLock", "锁键", "LockPort");

    private final Map<String, Duration> durations = new ConcurrentHashMap<>();

    private final ObjectProvider<LockPort> lockPorts;

    /**
     * 收 {@link ObjectProvider} 而不是 {@link LockPort}：切面属于"声明面"，它不该要求框架的 Redisson 锁分支
     * 在场。业务自带 {@code LockPort} 实现、或把 {@code myddd.consistency.lock.enabled} 关掉时，
     * {@code @DistributedLock} 依然应当工作；而容器里<b>一个</b> {@code LockPort} 都没有时，
     * 报的是一句指名道姓的话（见 {@link #requireLockPort()}），不是启动期一个建不出的 bean。
     */
    public DistributedLockAspect(ObjectProvider<LockPort> lockPorts) {
        if (lockPorts == null) {
            throw new FrameworkException("DistributedLockAspect 需要一个 LockPort 的 ObjectProvider，"
                    + "拿到的是 null——装配里不该出现这种调用");
        }
        this.lockPorts = lockPorts;
    }

    /**
     * 环绕所有带 {@link DistributedLock} 的方法。
     *
     * <p>切点用 {@code @annotation(distributedLock)} 的参数绑定而不是类型串：注解对象直接进方法签名，
     * 省掉"再从方法上取一次注解"，同时把"注解必须在被调用的方法上"这条匹配规则整个交给 Spring。
     */
    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        Method method = mostSpecificMethod(joinPoint);
        Duration waitTime = parseDuration(distributedLock.waitTime(), method, "waitTime");
        Duration leaseTime = parseDuration(distributedLock.leaseTime(), method, "leaseTime");
        String key = resolveKey(distributedLock.key(), method, joinPoint);
        LockPort lockPort = requireLockPort();
        try {
            return lockPort.executeLocked(key, waitTime, leaseTime, () -> proceedInsideLock(joinPoint));
        } catch (ActionThrowableFailure failure) {
            throw failure.cause();
        }
    }

    private LockPort requireLockPort() {
        LockPort lockPort = this.lockPorts.getIfAvailable();
        if (lockPort == null) {
            throw new FrameworkException("@DistributedLock 需要一个 LockPort bean，容器里没有它。"
                    + "要么打开框架的 Redisson 锁实现：myddd.consistency.enabled=true（缺省）且 "
                    + "myddd.consistency.lock.enabled=true（缺省），并配好 spring.data.redis.*；"
                    + "要么自己提供一个 LockPort 实现——切面只按类型取，不关心它是谁建的");
        }
        return lockPort;
    }

    private static Object proceedInsideLock(ProceedingJoinPoint joinPoint) {
        try {
            return joinPoint.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable failure) {
            throw new ActionThrowableFailure(failure);
        }
    }

    /**
     * 代理上拿到的是接口方法时，参数名要落到实现类的那个方法上。
     *
     * <p>{@code -parameters} 写进字节码的是实现类的形参名，接口方法读不到。不折算的话，JDK 动态代理
     * （业务实现了接口时的缺省代理形态）下 {@code #userId} 会求值失败而 {@code #p0} 照样能用——
     * 一个"只在某种代理形态下坏"的判据比坏判据更难查。
     */
    private static Method mostSpecificMethod(ProceedingJoinPoint joinPoint) {
        Method invoked = ((MethodSignature) joinPoint.getSignature()).getMethod();
        // 代理式 AOP 的方法执行连接点一定有目标实例（没有目标就没有这次调用），所以这里不写 null 分支。
        return ClassUtils.getMostSpecificMethod(invoked, joinPoint.getTarget().getClass());
    }

    private String resolveKey(String expressionText, Method method, ProceedingJoinPoint joinPoint) {
        return this.keys.evaluate(expressionText, method, joinPoint.getArgs(), joinPoint.getTarget());
    }

    private Duration parseDuration(String text, Method method, String attribute) {
        Duration parsed = this.durations.get(text);
        if (parsed == null) {
            parsed = parseDurationLiteral(text, method, attribute);
            this.durations.put(text, parsed);
        }
        return parsed;
    }

    private static Duration parseDurationLiteral(String literal, Method method, String attribute) {
        try {
            return DurationStyle.detectAndParse(literal);
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new FrameworkException("@DistributedLock(" + attribute + "=\"" + literal + "\") 在 "
                    + MethodKeyExpressions.describe(method) + " 上不是合法时长。可用写法与配置文件同一种语言："
                    + "简单形式 500ms / 30s / 5m / 2h，或 ISO-8601 形式 PT0.5S", failure);
        }
    }

    /** 把 {@code proceed()} 抛出的受检异常暂时装箱，出锁后在 {@link #around} 里解开重抛。 */
    private static final class ActionThrowableFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient Throwable cause;

        private ActionThrowableFailure(Throwable cause) {
            super(null, cause, false, false);
            this.cause = cause;
        }

        private Throwable cause() {
            return this.cause;
        }
    }
}

package org.deep361.myddd.consistency.idempotent;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.deep361.myddd.consistency.support.MethodKeyExpressions;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.Idempotent;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

/**
 * {@link Idempotent} 的执行侧：算出幂等键、生成本次调用的 token、调用 {@link IdempotentPort}，
 * 然后把业务方法的返回值或异常原样交回。
 *
 * <p><b>这里没有存储语义</b>：占位怎么存、TTL 怎么生效、按 token 比对怎么删，全部在 {@link IdempotentPort}
 * 的实现里（Redis 侧 {@code RedisIdempotentStore}，DB 侧 Task 27）。切面重复一遍那套规则会得到两个可能
 * 不同步的真相，所以本类的可观察职责只有四件：切点匹配、算键、按 {@code releaseOnError} 决定要不要释放、
 * 把重复请求翻译成 {@link CommonErrorCode#IDEMPOTENT_CONFLICT}。
 *
 * <p><b>成功时不释放</b>：占位留到 TTL 自然过期，这才是“防重复提交”的全部含义——一次成功的请求必须继续在
 * 窗口内拒绝后来者。{@code releaseOnError} 管的是失败那半边：缺省 {@code true}（校验类失败以后用户可以改
 * 正重试），{@code false} 表示“失败也不许立刻重试”。两条路径各有各的用例，因为把它们写反的表现是
 * “幂等要么完全没用、要么一次失败就锁死到过期”。
 *
 * <p><b>释放只在业务方法抛异常时发生，且用的是同一次调用的 token</b>：token 在
 * {@code tryAcquire} 之前生成一次并贯穿到 {@code release}。每次重新生成会得到一个“释放永远匹配不上”的组合
 * （Lua 比对必然失败），而它只在慢请求上显形——占位被别人重新拿走以后，本请求既没保住幂等也没能撤销占位。
 *
 * <p><b>异常不装箱</b>：与 {@code DistributedLockAspect} 不同，这里直接调用端口而不是把动作交给端口执行，
 * 所以 {@code proceed()} 抛出的受检异常就在本方法的 {@code catch (Throwable)} 里原样重抛，调用方
 * {@code catch (IOException)} 照样抓得到。锁那边需要装箱是因为端口的签名收 {@code Supplier}。
 *
 * <p><b>切点用参数绑定</b>（{@code @annotation(idempotent)}），与 {@code DistributedLockAspect} 同一种写法：
 * 注解实例由 Spring 匹配时按 find-merged 语义解析并直接交进来，切面不再自己从方法上取。这样"匹配用的注解"
 * 与"执行时读的注解"是同一个对象，不会出现"切点认为该切、切面读不到注解"的裂缝。绑定还免费带来两条真实可达
 * 的层级路径：注解写在<b>父类方法</b>上（子类没覆写）时照样命中。<b>接口方法上的注解不命中</b>——
 * 一手实测：那条形态下 bean 根本不会被代理，切点连门都没开。这是代理式 AOP 的既有行为（Spring 按目标类的
 * 方法匹配），两条各有一个用例钉着，因为"幂等注解写在接口上会静默失效"是业务最容易踩空的一格。
 *
 * <p><b>内核 javadoc 里"组合注解（元注解）也要生效"这一句在当前契约下无法由业务构造</b>：
 * {@link Idempotent} 的 {@code @Target} 只有 {@code METHOD}，把它元标注在自己的注解类型上会被 javac 当场拒绝
 * （一手实测：{@code 批注接口不适用于此类型的声明}）。切面这一侧不做任何限制——真要放开只需给内核补一个
 * {@code ElementType.ANNOTATION_TYPE}，find-merged 解析已经就位。这条不自洽登记在 Plan 3 偏差记录里。
 *
 * <p><b>{@code @Order(-1)}：幂等必须比锁更外</b>。{@code DistributedLockAspect} 是 0（Task 25 钉死：
 * 锁要包住事务）。同一个方法上两个注解都有时，幂等若排在锁<b>里面</b>，重复请求会先老老实实排队等锁、
 * 拿到锁、再被拒绝——白占一段临界区，而这正是幂等要省掉的开销。取 -1 而不是更小的负数：负数区间的主人是
 * 全链路与鉴权（见 {@code MydddConsistencyLockAopAutoConfiguration} 的说明），本框架只用到 -1 这一格。
 */
@Aspect
@Order(-1)
public class IdempotentAspect {

    private final MethodKeyExpressions keys = new MethodKeyExpressions("Idempotent", "幂等键", "IdempotentPort");

    private final ObjectProvider<IdempotentPort> idempotentPorts;

    /**
     * 收 {@link ObjectProvider} 而不是 {@link IdempotentPort}：与锁切面同一条理由。声明面不该要求
     * 框架的某一种存储实现在场——业务自带 {@code IdempotentPort}（比如内存版给单测）或把
     * {@code myddd.consistency.idempotent.enabled} 关掉（关的是实现，不是拦截）时，缺口的报话在
     * <b>调用时</b>给出（见 {@link #requireIdempotentPort()}），启动期不因缺少可选实现而失败。
     */
    public IdempotentAspect(ObjectProvider<IdempotentPort> idempotentPorts) {
        if (idempotentPorts == null) {
            throw new FrameworkException("IdempotentAspect 需要一个 IdempotentPort 的 ObjectProvider，"
                    + "拿到的是 null——装配里不该出现这种调用");
        }
        this.idempotentPorts = idempotentPorts;
    }

    /** 环绕所有带 {@link Idempotent} 的方法（含注解声明在接口方法、父类方法上的层级形态）。 */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        Method method = mostSpecificMethod(joinPoint);
        String key = resolveKey(idempotent, method, joinPoint);
        Duration ttl = ttl(idempotent, method);
        IdempotentPort port = requireIdempotentPort();
        String token = UUID.randomUUID().toString();
        if (!port.tryAcquire(key, token, ttl)) {
            throw conflict(idempotent);
        }
        try {
            return joinPoint.proceed();
        } catch (Throwable failure) {
            if (idempotent.releaseOnError()) {
                port.release(key, token);
            }
            throw failure;
        }
    }

    /**
     * 键的两个来源：写了 {@code key} 就按 SpEL 求值，留空就走 R2 的缺省摘要。
     *
     * <p>判据用 {@code isBlank()} 而不是 {@code isEmpty()}：{@code key = " "} 与留空是同一个意图
     * （“我没打算自己指定”），而把它交给 SpEL 只会得到一句“求值得到 null”——那句话说的是表达式写错了，
     * 可这里根本没写表达式。
     */
    private String resolveKey(Idempotent spec, Method method, ProceedingJoinPoint joinPoint) {
        String expressionText = spec.key();
        if (expressionText.isBlank()) {
            return IdempotentKeys.defaultKey(method, joinPoint.getArgs());
        }
        return this.keys.evaluate(expressionText, method, joinPoint.getArgs(), joinPoint.getTarget());
    }

    private static Duration ttl(Idempotent spec, Method method) {
        long seconds = spec.ttl();
        if (seconds <= 0) {
            throw new FrameworkException("@Idempotent(ttl=" + seconds + ") 在 " + MethodKeyExpressions.describe(method)
                    + " 上不是正数：ttl 是“占位存活秒数”，零或负数会让占位当场失效，于是每次请求都算首次——"
                    + "注解形同不存在。请按业务重试窗口给一个正数（缺省 60 秒）");
        }
        return Duration.ofSeconds(seconds);
    }

    private IdempotentPort requireIdempotentPort() {
        IdempotentPort port = this.idempotentPorts.getIfAvailable();
        if (port == null) {
            throw new FrameworkException("@Idempotent 需要一个 IdempotentPort bean，容器里没有它。"
                    + "要么打开框架的幂等实现：myddd.consistency.enabled=true（缺省）、"
                    + "myddd.consistency.idempotent.enabled=true（缺省），并按存储配好连接"
                    + "（store=redis 需要 StringRedisTemplate，即 spring.data.redis.*；store=db 需要 DataSource）；"
                    + "要么自己提供一个 IdempotentPort 实现——切面只按类型取，不关心它是谁建的。"
                    + "写 myddd.consistency.idempotent.enabled=false 只会关掉框架的这份实现，"
                    + "它既不会让这句报话消失，也不会让 @Idempotent 变成放行");
        }
        return port;
    }

    /**
     * 重复请求 → {@link CommonErrorCode#IDEMPOTENT_CONFLICT}（HTTP 409）。
     *
     * <p>{@code message()} 非空时只换<b>展示文案</b>，错误身份（{@code MYDDD_40904}、409、
     * {@code myddd.error.idempotent-conflict}）一个字都不动——业务改文案不该把 409 变成 500，
     * 也不该让监控按错误码聚合的曲线断掉。
     *
     * <p><b>给 web 层的契约</b>：这种异常的 {@code getMessage()} 是业务指定的原文，而
     * {@code ErrorCode#messageKey()} 仍是统一那条键。全局异常处理若一律按 messageKey 重新解析多语言文案，
     * 业务写的这句就会被顶掉。所以解析顺序必须是“异常自带文案非默认时优先用它”，这条写在
     * {@link CustomTextConflict} 上而不是只写在这里。
     */
    private static BizException conflict(Idempotent spec) {
        String message = spec.message();
        return message.isBlank() ? new BizException(CommonErrorCode.IDEMPOTENT_CONFLICT)
                : new CustomTextConflict(message);
    }

    /**
     * 代理上拿到的是接口方法时，方法要折算到实现类那一个再取注解与参数名。
     *
     * <p>与 {@code DistributedLockAspect} 同一条理由：{@code -parameters} 写进字节码的是实现类的形参名，
     * 而注解按业务习惯也写在实现类方法上。不折算会得到“只在某种代理形态下失效”的注解。
     */
    private static Method mostSpecificMethod(ProceedingJoinPoint joinPoint) {
        Method invoked = ((MethodSignature) joinPoint.getSignature()).getMethod();
        return ClassUtils.getMostSpecificMethod(invoked, joinPoint.getTarget().getClass());
    }

    /**
     * 带业务自定义文案的幂等冲突：身份是 {@link CommonErrorCode#IDEMPOTENT_CONFLICT}，只有文案是业务的。
     *
     * <p>之所以要一个子类而不是给 {@link BizException} 加一个“自定义文案”入口：内核契约里
     * {@code BizException} 的 {@code message} 由错误码渲染（{@code defaultMessage} + 占位符参数），
     * 而 {@link CommonErrorCode#IDEMPOTENT_CONFLICT} 的文案里没有占位符，塞参数只会得到
     * “重复请求，请勿重复提交”原样输出。子类只覆写 {@code getMessage()}，
     * {@code getErrorCode()}/{@code getMessageArgs()} 全部继承，因此错误身份与序列化都不变。
     */
    private static final class CustomTextConflict extends BizException {

        private static final long serialVersionUID = 1L;

        private final String businessText;

        private CustomTextConflict(String businessText) {
            super(CommonErrorCode.IDEMPOTENT_CONFLICT);
            this.businessText = businessText;
        }

        @Override
        public String getMessage() {
            return this.businessText;
        }
    }
}

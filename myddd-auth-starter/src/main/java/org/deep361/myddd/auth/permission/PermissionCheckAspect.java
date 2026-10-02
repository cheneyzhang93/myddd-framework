package org.deep361.myddd.auth.permission;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.deep361.myddd.auth.config.AuthPortGuard;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

/**
 * {@link org.deep361.myddd.kernel.port.auth.RequiresPermission} 的执行侧：把声明读出来，问端口一次
 * "登录了吗"，再按组合方式问权限，不通过就给一个身份正确的拒绝。
 *
 * <p><b>401 与 403 的分流只认 {@code currentUser()}</b>。内核 {@link AuthPort} 的实现契约是
 * "未登录 ⇒ {@code currentUser()} 给 {@link java.util.Optional#empty()}、{@code hasPermission(...)} 给
 * {@code false}，都不抛"——于是"未登录"这一件事在端口面上<b>只有</b> {@code currentUser()} 一个读法。
 * 拿 {@code hasPermission} 的 {@code false} 去判未登录会得到 403，而 403 在前端语义里是"你登进去了但没资格"，
 * 客户端因此不会跳登录页：这是一条会被写成工单的真故障，不是理论问题。
 *
 * <p><b>这里没有任何一家厂商的类，也没有任何"当前用户"的第二来源</b>：会话怎么读、token 在哪、
 * 非 Web 线程上未登录会先抛哪一种厂商异常，全部是 provider 的事（R9 的归一在 provider 侧完成，
 * 到端口这一面已经收敛成"空/ {@code false} / 空操作"）。本壳因此不需要、也<b>刻意不设</b>
 * 一个 {@code Supplier<AuthUser>} 式的"当前用户"接缝：那会在 {@code AuthPort.currentUser()} 之外
 * 造第二个身份真源，两家 provider 各自要维护两份一致性——而换 provider 不改业务这句承诺，
 * 靠的正是只有一个读法。
 *
 * <p><b>解析链与切点面同源</b>：具体形状与实测依据都在 {@link PermissionDeclaration#resolve}
 * （三级全部 {@code DIRECT}）。本类只负责把 AspectJ 给的 {@link MethodSignature#getMethod()} 归一到
 * 目标类视角（{@code getMostSpecificMethod} + {@code ultimateTargetClass}），问一次声明，再按声明问端口。
 *
 * <p><b>坏声明先于端口解析</b>：{@link PermissionDeclaration} 的构造抛发生在
 * {@link AuthPortGuard#require()} <b>之前</b>。这条顺序是有意的：声明非法是代码写错，
 * 容器里有没有 provider 都不改变它；反过来先报端口缺席，会让人把"注解写错"排查成"依赖没引"。
 *
 * <p><b>{@code @Order(-6)：鉴权在限流外面</b>。与 {@code RateLimitAspect} 的 {@code -5}、
 * {@code CircuitBreakerAspect} 的 {@code -4}、{@code IdempotentAspect} 的 {@code -1}、
 * {@code DistributedLockAspect} 的 {@code 0} 连读成六格（值小者在外）。关系只有一条：
 * <b>一次权限拒绝不该消耗一次限流许可、不该写一条幂等记录、不该占一把锁</b>——否则针对某接口的
 * 未授权重放会把合法用户的配额吃光，而那正是限流与幂等要防的事。判据用数值严格小于表达
 * （{@code AT14}），不用时序用例：两条注解在同一方法上时 {@code @Order} 相同才需要时序，本框架给了六个
 * 互不相同的值，数值关系就是嵌套关系（与 Task 44 的 {@code OR1} 同一手法）。
 */
@Aspect
@Order(PermissionCheckAspect.ASPECT_ORDER)
public class PermissionCheckAspect {

    /** 框架切面顺序表的最外一格（判据见类注释的 {@code @Order} 段）。 */
    public static final int ASPECT_ORDER = -6;

    private final AuthPortGuard guard;

    /**
     * @param guard 端口在场性判据。<b>不</b>直接收 {@code ObjectProvider}：把"零个 / 恰好一个 / 多个"
     *              三态判据提成一个有名字的类，让每一态各自有用例（{@code PG} 组），而不是散在切面分支里——
     *              auth 这条线上"多个"是真会发生的形态（两家 provider 各产一个 {@code AuthPort} bean）。
     *
     * <p><b>刻意不做 null 检查</b>：本类唯一的 {@code new} 出现在自动装配那一个 {@code @Bean} 方法里，
     * 而用例给的是真 {@link AuthPortGuard}（内部装替身 {@code ObjectProvider}）。无判据的防御分支
     * 等于给覆盖率分母加一行没人走的代码。
     */
    public PermissionCheckAspect(AuthPortGuard guard) {
        this.guard = guard;
    }

    /**
     * 环绕"方法上有 {@code @RequiresPermission} 或所属类上有 {@code @RequiresPermission}"的连接点。
     *
     * <p>切点<b>不绑定注解形参</b>：绑定式析取（{@code @annotation(spec) || @within(spec)}）会把
     * "只有类型级声明"这一格读成 {@code null}，注解实例一律由 {@link PermissionDeclaration#resolve}
     * 自己按三级链取。两侧同源，所以不存在"切点认为该切、切面读不到声明"的裂缝——真读到了就在
     * {@code null} 那一支快速失败，绝不静默放行。
     */
    @Around("@annotation(org.deep361.myddd.kernel.port.auth.RequiresPermission) "
            + "|| @within(org.deep361.myddd.kernel.port.auth.RequiresPermission)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Object target = joinPoint.getTarget();
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
        Method invoked = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = ClassUtils.getMostSpecificMethod(invoked, targetClass);
        PermissionDeclaration declaration = PermissionDeclaration.resolve(method, targetClass);
        if (declaration == null) {
            throw unmatched(joinPoint, targetClass, method);
        }
        AuthPort port = this.guard.require();
        if (port.currentUser().isEmpty()) {
            throw rejection(CommonErrorCode.UNAUTHORIZED, declaration);
        }
        if (declaration.satisfiedBy(port::hasPermission)) {
            return joinPoint.proceed();
        }
        throw rejection(CommonErrorCode.FORBIDDEN, declaration);
    }

    /** 注解文案优先于错误码默认文案（R12）；留空就是统一文案，一个字节都不动。 */
    private static BizException rejection(ErrorCode errorCode, PermissionDeclaration declaration) {
        String message = declaration.message();
        return message.isBlank() ? new BizException(errorCode) : new PermissionRejection(errorCode, message);
    }

    /**
     * 切点开了门、解析却读不到声明：这是框架内部不一致，<b>不能</b>读成"这一格没要求"。
     *
     * <p>探针把切点会开的每一格都量过一遍（方法级自声明、目标类级、继承未覆盖的声明类级、组合注解、
     * CGLIB 与 JDK 两种代理形态），没有一种业务写法落到这一支；被覆盖的父类侧与接口侧两支<b>门根本不开</b>
     * （所以由 {@link PermissionDeclarationValidator} 在启动期报错，而不是在这里兜）。这一支存在的理由是：
     * 若 Spring 的匹配语义在未来某次升级里放宽，宁可抛一条指名类与方法的 {@link FrameworkException}，
     * 也不要静默放行一个鉴权点。按缺口登记，不设复述式用例。
     */
    private static FrameworkException unmatched(ProceedingJoinPoint joinPoint, Class<?> targetClass,
            Method method) {
        return new FrameworkException("切点认为 " + targetClass.getSimpleName() + "." + method.getName()
                + " 该被 @RequiresPermission 拦，但三级解析（方法级、目标类、执行方法的声明类）都没有读到声明："
                + joinPoint.getSignature() + "。这是框架内部不一致，不会由业务的写法造成——"
                + "若你看到这个异常，请带上类名与方法名提问题；在此之前这一格是拒绝执行而不是放行");
    }
}

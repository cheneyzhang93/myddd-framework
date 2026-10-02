package org.deep361.myddd.persistence.routing;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ObjectUtils;

import java.lang.reflect.Method;

/**
 * 把 {@link UseDataSource} 翻译成 {@link DataSourceKeyHolder} 上的一次绑定。
 *
 * <p><b>顺序即正确性</b>：必须在事务通知之外（见 Task 17 设计要点 2 的实测：同序时事务已在默认库上开了
 * 连接，注解读了也来不及）。取 {@code LOWEST_PRECEDENCE - 100} 而不是 {@code HIGHEST_PRECEDENCE}：
 * 前者只要求"比事务通知器更早"，给业务的日志/鉴权类通知器留出更外层的空间；后者会把本切面推到所有通知器
 * 最外面，反而可能早于设置鉴权上下文的那一层。
 *
 * <p><b>只在路由模式里被装配</b>（见 {@code MydddRoutingDataSourceAutoConfiguration}），且该装配带
 * {@link AspectjProxyGuard} —— 少了 AspectJ 感知的代理创建器，本类根本不会被调用。
 */
@Aspect
@Order(DataSourceRoutingAspect.ASPECT_ORDER)
public class DataSourceRoutingAspect {

    /** 事务通知器的顺序是 {@link Ordered#LOWEST_PRECEDENCE}，本切面必须严格在它之前。 */
    public static final int ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Around("@annotation(org.deep361.myddd.persistence.routing.UseDataSource)"
            + " || @within(org.deep361.myddd.persistence.routing.UseDataSource)")
    public Object bindDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        UseDataSource annotation = findAnnotation(joinPoint);
        if (annotation == null) {
            return joinPoint.proceed();
        }
        String previous = DataSourceKeyHolder.bind(annotation.value());
        try {
            return joinPoint.proceed();
        } finally {
            DataSourceKeyHolder.restore(previous);
        }
    }
    /**
     * 按"方法（实现类上的最终形态）→ 方法（签名声明的接口形态）→ 类型"的顺序找注解。
     *
     * <p>{@link AopUtils#getMostSpecificMethod(Method, Class)} 是必需的一步：JDK 代理下
     * {@code MethodSignature#getMethod()} 给出的是<b>接口</b>方法，注解在实现上时那里查不到。
     * 反向也查一次（接口方法上的注解），是为了让"注解在接口上"这种误用在 D5 里以可观察的方式失败，
     * 而不是依赖某一次代理形态的巧合。
     */
    private static UseDataSource findAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method signatureMethod = signature.getMethod();
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(joinPoint.getTarget());
        Method mostSpecificMethod = AopUtils.getMostSpecificMethod(signatureMethod, targetClass);

        UseDataSource found = AnnotatedElementUtils.findMergedAnnotation(mostSpecificMethod, UseDataSource.class);
        if (found == null && !ObjectUtils.nullSafeEquals(mostSpecificMethod, signatureMethod)) {
            found = AnnotatedElementUtils.findMergedAnnotation(signatureMethod, UseDataSource.class);
        }
        if (found == null) {
            found = AnnotatedElementUtils.findMergedAnnotation(targetClass, UseDataSource.class);
        }
        return found;
    }
}

package org.deep361.myddd.resilience;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 资源名的单点：注解里那串 {@code key} 怎么变成端口收到的那个字符串。
 *
 * <p><b>为什么壳里要有一份而不是复用 consistency 侧的 {@code MethodKeyExpressions}</b>：那一类在
 * {@code myddd-consistency-starter}，而 R2 给装配壳的依赖上界里没有兄弟适配器这一项——
 * 弹性壳去依赖一致性壳，"换 provider 不改业务"就先在框架内部破了一次。所以这里是一份<b>同形而独立</b>
 * 的实现，两处重复由 Task 53 收口成一处（那一任务同时把 {@code @RateLimit.timeout} 的等待语义与
 * 锁的 {@code waitTime} 合并，键解析与等待语义是同一次抽取的两半）。在此之前不许假装它们已经合并。
 *
 * <p><b>与 consistency 侧的三处真实差异</b>（不是抄漏）：① 缺省名不是摘要而是内核 javadoc 明写的
 * {@code 简单类名.方法名}，因为限流资源名要出现在 provider 的规则里，人能读懂比稳定短更重要；
 * ② 这里多做一层"注解声明在哪一级"的解析（{@code @RateLimit} 与 {@code @CircuitBreaker} 都是
 * {@code @Target({METHOD, TYPE})}，而 {@code @Idempotent} 只有 METHOD）；③ 空白求值结果一律放行，
 * 由端口按它 javadoc 的实现契约拒绝——切面替端口拒绝会得到同一条契约两处各说一半。
 *
 * <p><b>缓存的是语法树，不是求值结果</b>：与 {@code MethodKeyExpressions} 同一条理由（注解里的串是编译期
 * 常量，热点路径上每次重解析会把纯优化变成固定开销；键写死成求值结果则有跨调用泄漏实参的真风险）。
 * 缓存本身没有行为差异，所以不设用例钉它——被用例钉住的是"每次新建求值上下文"那一半（`RS8`）。
 */
public final class ResourceKeyResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final String annotationName;
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    /**
     * @param annotationName 消息里出现的注解名（{@code RateLimit} / {@code CircuitBreaker}）。
     *                       两个切面各持一份实例：同一段拒绝文案要指名<b>自己</b>那个注解，
     *                       共享一份只会得到"@RateLimit 在 xxx 上求值失败"贴在 @CircuitBreaker 的错误上。
     */
    public ResourceKeyResolver(String annotationName) {
        this.annotationName = annotationName;
    }

    /**
     * 找出这次调用的注解声明，方法级压过类型级。
     *
     * <p>两级都要看，且顺序只有一种解释：{@code @RateLimit} 写在类上是"这个类的所有方法都限流"，
     * 写在方法上是"这一格我有别的说法"。探针 U2 的 {@code overrideByKey<-METHOD:methodWins} 是这条
     * 优先级的唯一一手依据——绑定式切点在这里给出的是相反的答案（U1），所以解析必须由本方法做。
     *
     * <p>类型级只看实现类（含其父类链）。<b>接口上的声明一律不可见</b>：探针 U3/U4 实测那种形态下
     * 切点门根本不开（Spring 按目标类的方法匹配），本方法若从接口读出一个注解，就会造出
     * "解析读得到、切面永不触发"的第二套真相。出路写在 {@code package-info}：把注解移到实现类上。
     */
    <A extends Annotation> A declared(Method method, Class<?> targetClass, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method, type);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(targetClass, type);
    }

    /**
     * 注解里的 {@code key} 求成资源名。留空或纯空白走缺省名；其余整串按 SpEL 解析。
     *
     * <p>判据用 {@code isBlank()} 而不是 {@code isEmpty()}，与 {@code DistributedLockAspect#resolveKey}
     * 同一条：{@code key = " "} 与留空是同一个意图，交给 SpEL 只会得到一句"求值得到 null"，
     * 而这里根本没写表达式。
     */
    String resourceText(String expressionText, Method method, Object[] arguments, Object root,
            Class<?> defaultNameOwner) {
        if (expressionText == null || expressionText.isBlank()) {
            return defaultResourceName(defaultNameOwner, method);
        }
        Expression expression;
        try {
            expression = this.cache.computeIfAbsent(expressionText, PARSER::parseExpression);
        } catch (SpelParseException failure) {
            throw new FrameworkException("@" + this.annotationName + "(key=\"" + expressionText + "\") 在 "
                    + describe(method) + " 上不是合法的 SpEL：" + failure.getMessage(), failure);
        }
        var context = new MethodBasedEvaluationContext(root, method, arguments, PARAMETER_NAMES);
        Object value;
        try {
            value = expression.getValue(context);
        } catch (SpelEvaluationException failure) {
            throw new FrameworkException("@" + this.annotationName + "(key=\"" + expressionText + "\") 在 "
                    + describe(method) + " 上求值失败：" + failure.getMessage()
                    + "。参数名需要 -parameters 编译才可见（本框架父 POM 已全局打开），"
                    + "也可以改用位置变量 #p0/#p1，或检查属性路径拼写", failure);
        }
        // 陌生变量名走的是这里而不是上面的 SpelEvaluationException：SpEL 对读不到的 #变量 给 null 而不报错。
        if (value == null) {
            throw new FrameworkException("@" + this.annotationName + "(key=\"" + expressionText + "\") 在 "
                    + describe(method) + " 上求值得到 null：SpEL 读不到陌生的变量名时不报错、只给 null，"
                    + "所以先核对形参名，拿不准就用位置变量 #p0/#p1。"
                    + "资源名必须能定位到一个具体的限流/熔断对象（空白串会被端口按它的实现契约拒绝）");
        }
        return value.toString();
    }

    /** 缺省资源名：{@code 简单类名.方法名}（内核 {@code RateLimit#key} 与 {@code CircuitBreaker#key} 的 javadoc 原文）。 */
    static String defaultResourceName(Class<?> owner, Method method) {
        return owner.getSimpleName() + "." + method.getName();
    }

    /** 消息里"哪个方法的哪个注解"的写法：全限定类名 + # + 方法名。 */
    static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}

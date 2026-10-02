package org.deep361.myddd.consistency.support;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;

/**
 * 注解里那串 SpEL 键表达式的求值机制：解析、缓存、三类失败各给一句话。
 *
 * <p><b>为什么存在</b>：{@code @DistributedLock} 与 {@code @Idempotent} 的键都是"整串按 SpEL 解析、
 * 求值上下文每次新建、结果原样交给端口"。两处各写一份会得到三段会各自漂移的话——尤其"陌生 {@code #变量}
 * 只给 null、属性路径写错抛 {@code SpelEvaluationException}"这条分岔（一手实测），抄一遍时最容易只抄到
 * 其中一支。抽出来以后，两个切面的差异只剩下<b>消息里的名字</b>，而那正是构造参数。
 *
 * <p><b>缓存的是语法树，不是求值结果</b>：注解里的串是编译期常量，热点路径上每次重解析把一个纯优化变成
 * 固定开销。缓存键是表达式<b>原文</b>，所以两个方法共用同一串 {@code "#userId"} 时共用一棵树，而求值上下文
 * 每次新建、绝不共享（否则就是跨调用泄漏实参）。可观察性上的两个方向各有各的变异取证：旁路缓存无行为差异，
 * 键写死有——详见 consistency-starter 的变异表。
 */
public final class MethodKeyExpressions {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final String annotationName;
    private final String keyNoun;
    private final String portName;
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    /**
     * @param annotationName 消息里出现的注解名（{@code DistributedLock} / {@code Idempotent}）
     * @param keyNoun        消息里对"这个字符串是什么"的称呼（锁键 / 幂等键）
     * @param portName       拒绝空白键的那个端口类型名，报错时要指名
     */
    public MethodKeyExpressions(String annotationName, String keyNoun, String portName) {
        this.annotationName = annotationName;
        this.keyNoun = keyNoun;
        this.portName = portName;
    }

    /**
     * 求值并转成串。空白结果<b>不在这里拒绝</b>——规范化规则归端口（框架只加前缀段、不加工），
     * 在切面里替端口放行或替端口拒绝会让同一条契约两处各说一半。
     */
    public String evaluate(String expressionText, Method method, Object[] arguments, Object root) {
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
        // 陌生变量名走的是这里，不是上面的 SpelEvaluationException：SpEL 对读不到的 #变量 给 null 而不报错
        // （#nope 实测如此）。所以"参数名写错"这条最常见的错法必须由这段话给出 #p0 这条路。
        if (value == null) {
            throw new FrameworkException("@" + this.annotationName + "(key=\"" + expressionText + "\") 在 "
                    + describe(method) + " 上求值得到 null：SpEL 读不到陌生的变量名时不报错、只给 null，"
                    + "所以先核对形参名（形参名需要 -parameters 编译才可见，本框架父 POM 已全局打开），"
                    + "拿不准就用位置变量 #p0/#p1。" + this.keyNoun + "必须能定位到具体业务坐标"
                    + "（空白串同样不允许，会被 " + this.portName + " 拒绝）");
        }
        return value.toString();
    }

    /** 消息里"哪个方法的哪个注解"的写法：全限定类名 + # + 方法名。 */
    public static String describe(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }
}

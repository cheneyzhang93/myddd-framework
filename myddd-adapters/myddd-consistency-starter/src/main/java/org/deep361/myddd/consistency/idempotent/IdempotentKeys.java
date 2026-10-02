package org.deep361.myddd.consistency.idempotent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.util.ReflectionUtils;

/**
 * {@code @Idempotent} 留空 {@code key()} 时的缺省键：{@code 简单类名.方法名:参数摘要}（设计文档 R2）。
 *
 * <p><b>摘要而不是拼接</b>：参数拼进键会让键长随实参增长（一个 DTO 的 {@code toString()} 可以有几百字节），
 * 而 Redis 键名与 DB 主键列都有长度上限，超限的那次请求会直接失败——业务看到的是“这个接口偶尔报系统错误”。
 * 取 {@code sha256} 前 16 个十六进制字符（64 bit）：同一个方法上的重试要<b>相同</b>，不同业务坐标要<b>不同</b>，
 * 64 bit 对这个判据足够（碰撞概率随<b>同一方法</b>的参数组合数增长，不是随全应用请求量增长）。
 *
 * <p><b>R2 的两条“不含”是设计核心，各自挡掉一类静默失效</b>：
 * <ul>
 *   <li><b>不含类型名</b>：{@code 1}（Integer）与 {@code "1"}（String）会渲染成同一个串，于是<b>共用同一把键</b>。
 *       这是裁定的直接后果，写在用例里而不是藏在代码里——业务不接受这个合并时，唯一的出口是显式
 *       {@code key} 表达式。</li>
 *   <li><b>不含 JVM 身份 hash</b>：{@code Object.toString()} 的缺省形态就是身份 hash，所以参数若没有重写
 *       {@code toString()}，<b>每次重试都会得到不同的键</b>，幂等变成永远不命中——而这恰恰是“我加了注解”
 *       唯一能得出的错误结论。因此这里<b>快速失败</b>并给两条出口（显式 {@code key}／补 {@code toString()}）：
 *       一个静默不生效的幂等注解比一次启动就响的报错糟糕得多。</li>
 * </ul>
 *
 * <p><b>分隔符 {@code |} 不转义</b>：单参数 {@code "a|b"} 与两参数 {@code "a"}、{@code "b"} 规范化后同串，因而同键。
 * 转义会改掉 R2 规定的规范化串（“每个参数 {@code String.valueOf(arg)}”），属于另一种语言了；
 * 框架把这条边界也钉成用例，需要精确区分时同样走显式 {@code key}。
 *
 * <p><b>数组展开为 {@code [a,b,c]}</b>：{@code String.valueOf(int[])} 给出的正是被禁止的身份 hash 形态，
 * 所以数组必须自己展开（含多维与元素为 {@code null}）。展开用 {@link Array} 反射而非逐类型分支：
 * {@code List}/{@code Set} 不展开（它们的 {@code toString()} 由集合自己实现且稳定），只有 JVM 数组需要这条路。
 */
final class IdempotentKeys {

    /** 摘要取前多少个十六进制字符（R2 定为 16）。 */
    private static final int DIGEST_HEX_LENGTH = 16;

    /** {@code Object.toString()} 那一个方法，用来判断参数类有没有重写它（见 {@link #usesIdentityToString}）。 */
    private static final Method OBJECT_TO_STRING = ReflectionUtils.findMethod(Object.class, "toString");

    private IdempotentKeys() {
    }

    /**
     * 缺省幂等键。调用方必须已经把注解的 {@code key()} 判为空串——非空时走 SpEL，不经过这里。
     *
     * @param method    被调用的<b>最具体</b>方法（实现类的那个），键里的类名取它的简单类名
     * @param arguments 实参数组，顺序即声明顺序
     */
    static String defaultKey(Method method, Object[] arguments) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName() + ":"
                + digestHex(normalize(arguments));
    }

    /**
     * 规范化参数串：每个实参按声明顺序渲染，用 {@code |} 连接。
     *
     * <p>包内可见并被用例直接钉住：摘要是不透明的 16 个字符，只从最终键断言的话，“数组没展开”和
     * “分隔符换了”这类改动会一起表现为“两个不相干的用例同时红”，而不说是哪一段坏了。
     */
    static String normalize(Object[] arguments) {
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < arguments.length; index++) {
            if (index > 0) {
                normalized.append('|');
            }
            appendValue(normalized, arguments[index], index);
        }
        return normalized.toString();
    }

    private static void appendValue(StringBuilder normalized, Object value, int argumentIndex) {
        if (value == null) {
            // 与 String.valueOf((Object) null) 一致：null 是一个可渲染的值，不是错误。
            normalized.append("null");
            return;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            normalized.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) {
                    normalized.append(',');
                }
                appendValue(normalized, Array.get(value, i), argumentIndex);
            }
            normalized.append(']');
            return;
        }
        if (usesIdentityToString(type)) {
            throw new FrameworkException("@Idempotent 的缺省键要把 " + argumentDescription(argumentIndex)
                    + "（" + type.getName() + "）渲染成串，而这个类没有重写 toString()。Object 的缺省渲染是"
                    + "「类名@身份 hash」，同一个业务对象的每次重试都会得到不同的键——注解看着生效、"
                    + "实则永不命中，重复请求照样进临界区。两个修法：给 @Idempotent(key = \"#业务坐标\") "
                    + "写清到底按什么幂等，或者给这个类补一个稳定且能区分业务的 toString()。"
                    + "集合（List/Map）不在此列，它们自己的 toString() 是可用的");
        }
        normalized.append(value);
    }

    /**
     * 这个类是否只会给出 {@code Object.toString()} 的身份 hash 形态。
     *
     * <p>判据是“解析到的 {@code toString()} 是不是 {@link Object} 上那一个”，而不是“渲染结果长得像不像
     * {@code 名@十六进制}"：后者会把自定义但恰好含 {@code @} 的文案误判成错误，前者只在真的没重写时成立
     * （重写必然让 {@code getDeclaringClass()} 变成该类自己或其某个父类）。
     */
    private static boolean usesIdentityToString(Class<?> type) {
        return OBJECT_TO_STRING.equals(ReflectionUtils.findMethod(type, "toString"));
    }

    private static String argumentDescription(int index) {
        return "第 " + (index + 1) + " 个参数";
    }

    private static String digestHex(String normalized) {
        byte[] digest = sha256().digest(normalized.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, DIGEST_HEX_LENGTH);
    }

    /** SHA-256 是 JCA 的必选算法（《Java Cryptography Architecture Standard Algorithm Name Documentation》）。 */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new FrameworkException("这个 JVM 没有 SHA-256 实现，幂等缺省键无法计算", failure);
        }
    }
}

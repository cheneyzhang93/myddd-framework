package org.deep361.myddd.persistence.routing;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 当前线程绑定的数据源键（Task 17 路由模式的唯一上下文）。
 *
 * <p><b>为什么是 {@link ThreadLocal} 而不是 {@link InheritableThreadLocal}</b>：线程池会复用线程，继承语义
 * 会让一次请求绑定的键被它派生的 worker 长期持有，表现为"某个后台任务一直写在从库里"。跨线程要显式
 * {@link #runWith(String, Supplier)} 重新绑。
 *
 * <p><b>谁在用</b>：{@link DataSourceRoutingAspect} 在 {@code @UseDataSource} 方法外层 bind/restore；
 * 非 bean 方法的场景（定时任务里的一段、投递线程里的一段）用 {@link #runWith(String, Supplier)}；
 * {@link RoutingDataSource} 只读 {@link #current()}。
 *
 * <p><b>只有 5 个方法</b>：{@link #current()}、{@link #bind(String)}、{@link #restore(String)}
 * 是低层三件套，必须成对使用（异常路径靠 {@code finally}）；{@link #runWith(String, Supplier)} 是业务侧
 * 推荐的写法，它内部就是前一后。刻意<b>不</b>提供 {@code Runnable} 重载：{@code runWith(key, () -> foo())}
 * 在 {@code foo()} 有返回值时同时匹配 {@code Supplier} 与 {@code Runnable}，javac 会报歧义，重载决议
 * 变成业务的坑；void 动作写成 {@code runWith(key, () -> { repo.save(x); return null; })}。
 */
public final class DataSourceKeyHolder {

    /**
     * 键名形状：小写字母开头，只含 {@code [a-z0-9_]}，总长 ≤ 64。
     *
     * <p>配置侧（{@code MydddPersistenceProperties} 校验 {@code myddd.persistence.datasources.<name>}）
     * 与代码侧（{@code @UseDataSource("…")}）共用这一份规则，见 {@link #isValidKey(String)}。
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();

    private DataSourceKeyHolder() {
    }

    /** 当前绑定的键；未绑定返回 {@code null}，此时路由器使用默认键。 */
    public static String current() {
        return BOUND.get();
    }

    /**
     * 绑定 {@code key}，返回<b>此前</b>绑定的键（可能为 {@code null}），供 {@link #restore(String)} 复原。
     *
     * @throws IllegalArgumentException {@code key} 形状非法（见 {@link #isValidKey(String)}）
     */
    public static String bind(String key) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("数据源键 [" + key + "] 非法：必须以英文小写字母开头、只含 [a-z0-9_]、"
                    + "长度不超过 64，且必须是 myddd.persistence.datasources 里已定义的键");
        }
        String previous = BOUND.get();
        BOUND.set(key);
        return previous;
    }

    /**
     * 复原到 {@code previous}。传 {@code null} 表示"外层本来没有绑定"，此时必须 {@code remove()} 而不是
     * {@code set(null)}：线程池线程会长活，留一个 null 值槽位就是白占的内存。
     */
    public static void restore(String previous) {
        if (previous == null) {
            BOUND.remove();
        } else {
            BOUND.set(previous);
        }
    }

    /** 在 {@code key} 下执行 {@code action}，无论正常返回还是抛出都复原到先前的键。 */
    public static <T> T runWith(String key, Supplier<T> action) {
        String previous = bind(key);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    /** 形状是否合法（不判断该键是否已在配置里定义——那由 {@link RoutingDataSource} 在取连接时判定）。 */
    public static boolean isValidKey(String key) {
        return key != null && KEY_PATTERN.matcher(key).matches();
    }
}

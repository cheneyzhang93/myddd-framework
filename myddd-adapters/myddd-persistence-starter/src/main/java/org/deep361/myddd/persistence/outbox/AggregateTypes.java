package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.aop.framework.Advised;

import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * 代理聚合根的解包入口：把"运行时看到的类/实例"还原成"业务真正的那个聚合"。
 *
 * <p><b>为什么需要</b>：kernel 默认发布器用 {@code source.getClass().getName()} 生成 {@code aggregateType}，
 * 聚合被代理时它给出的是代理类名；而 {@code Entity#getId()} 是 {@code final} 方法，CGLIB 子类代理无法覆写，
 * 代理实例上读到的 id 是 {@code null}。两者一起写进 Outbox，会得到"通道叫 {@code 0-events}、
 * {@code aggregate_id} 为字符串 null"的记录——发得出去、没人消费。
 *
 * <p><b>代理识别规则（两条同时成立才算代理）</b>：① 类名以父类二进制名开头；② 余下部分带生成器标记
 * （{@code $$}：Spring CGLIB 的 {@code $$SpringCGLIB$$}、javassist 的 {@code _$$_jvst}；或
 * {@code HibernateProxy}）。只用"名字里有 {@code $}"会误伤合法的嵌套子类
 * （{@code Order$Draft extends Order} 与 Hibernate 的 {@code Order$HibernateProxy$x} 形状同源）。
 *
 * <p><b>不支持 JDK 动态代理的类入口</b>：那种代理的父类是 {@code java.lang.reflect.Proxy}，与聚合无继承关系
 * （实测 {@code instanceof Order} 为 false），任何回溯都救不回来——快速失败并给出两条出路，而不是猜一个类型名。
 * 对象入口 {@link #target(Object)} 可以救：Spring 的 JDK 代理实现 {@link Advised}，能拿回目标实例。
 */
public final class AggregateTypes {

    private AggregateTypes() {
    }

    /** 沿父类链上溯，直到第一个不是代理形态的类；见类 javadoc 的识别规则。 */
    public static Class<?> unwrap(Class<?> candidate) {
        Objects.requireNonNull(candidate, "待解包的类型不能为 null");
        Class<?> current = candidate;
        while (isProxyShaped(current)) {
            current = current.getSuperclass();
        }
        if (current == Object.class || Proxy.class.isAssignableFrom(current)) {
            Class<?> landedParent = current.getSuperclass();
            throw new FrameworkException("无法从代理还原聚合类型：" + candidate.getName()
                    + " 沿父类上溯落到 " + current.getName()
                    + (landedParent == null ? "" : "（其父类为 " + landedParent.getName() + "）")
                    + "。这类代理与聚合没有继承关系，猜出来的类型名会静默投错通道。出路二选一："
                    + "① 用 publish(event, aggregateType, aggregateId) 显式给出聚合类型；"
                    + "② 让该 bean 用 CGLIB 代理（proxyTargetClass=true），使代理类成为聚合的子类。");
        }
        return current;
    }

    /** 解包后的二进制类名（嵌套类保留 {@code Outer$Inner}，与 kernel 的 {@code getClass().getName()} 同口径）。 */
    public static String name(Class<?> candidate) {
        return unwrap(candidate).getName();
    }

    /** 若对象是 Spring AOP 代理，取回它背后的目标实例；否则原样返回。 */
    public static Object target(Object candidate) {
        Objects.requireNonNull(candidate, "聚合实例不能为 null");
        if (!(candidate instanceof Advised advised)) {
            return candidate;
        }
        try {
            return advised.getTargetSource().getTarget();
        } catch (Exception exception) {
            throw new FrameworkException("取不到被代理的目标聚合：" + candidate.getClass().getName()
                    + "。拿不到目标实例时立即失败，也不把代理类名与 null id 写进 Outbox。", exception);
        }
    }

    /** 对象入口：先拿回真实实例（Spring 代理），再解包类名（字节码生成器代理）。 */
    public static String name(Object candidate) {
        return name(target(candidate).getClass());
    }

    /**
     * 代理形态判定：① 类名以父类二进制名开头，且 ② 余下部分带某个生成器标记。
     *
     * <p>只靠 ①（"名字像前缀拼接"）会把合法的嵌套子类 {@code Order$Draft extends Order} 当成代理——它与
     * Hibernate 的 {@code Order$HibernateProxy$…} 在"名字 = 父类名 + $开头后缀"上完全同源，因此标记清单
     * 是必须的。只靠 ②（"名字里有 {@code $}"）同理误伤。
     *
     * <p>标记只留两个：{@code $$} 覆盖 Spring CGLIB（{@code $$SpringCGLIB$$}）与 javassist
     * （{@code _$$_jvst…}），{@code HibernateProxy} 覆盖 Hibernate 6 的两种 provider 命名。JDK 动态代理
     * 不在这里判：它的父类是 {@code java.lang.reflect.Proxy}，与聚合无继承关系，条件 ① 就不成立，由
     * {@link #unwrap(Class)} 的落点判定（{@code Proxy.class.isAssignableFrom}）负责快速失败。
     */
    private static boolean isProxyShaped(Class<?> type) {
        Class<?> parent = type.getSuperclass();
        if (parent == null) {
            return false;
        }
        String name = type.getName();
        String parentName = parent.getName();
        if (!name.startsWith(parentName)) {
            return false;
        }
        String suffix = name.substring(parentName.length());
        return suffix.contains("$$") || suffix.contains("HibernateProxy");
    }
}

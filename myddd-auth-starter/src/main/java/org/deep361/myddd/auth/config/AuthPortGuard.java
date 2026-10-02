package org.deep361.myddd.auth.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;

/**
 * {@link AuthPort} 的在场性判据：把"容器里恰好一个 / 零个 / 多个"三种形态各自变成一个可读答案。
 *
 * <p><b>为什么壳里有这一个类</b>：壳不产 {@code AuthPort}（Step 0 第 4 条），但壳里的切面要在每次调用时
 * 拿到它，于是"拿不到"这件事必须有一句成话的说法。这一格与限流侧（Task 44 的
 * {@code RateLimitAspect#requireRateLimitPort}）的<b>差别</b>不是风格：那一处只写了"没有"这一支，
 * 而一手实测（探针 {@code 48c}，{@code D:\tmp\p4probe\auth\p48cout.txt}）表明确实还有第二支——
 *
 * <pre>
 * zero        getIfAvailable=null      stream=[]
 * one         getIfAvailable=PRESENT:A stream=[A]
 * two         getIfAvailable=THROW:NoUniqueBeanDefinitionException  stream=[A, B]
 * twoPrimary  getIfAvailable=PRESENT:A stream=[A, B]
 * </pre>
 *
 * <p>{@code ObjectProvider#getIfAvailable()} 在两个候选且无 {@code @Primary} 时<b>抛</b>
 * {@code NoUniqueBeanDefinitionException}，而不是返回 {@code null}。所以"用 {@code null} 判缺口、
 * 别的情况自然返回第一个"的写法会把多候选这一格交给 Spring 的原始异常——那条消息只有类型名和 bean 名，
 * 没有键名、没有两家 provider、没有可执行的下一步。本类因此以 {@code stream()} 为分类真源，
 * 只在"确实多于一个"时才去问一次 {@code getIfAvailable()}（用来兑现 {@code @Primary} 这条合法消歧），
 * 并把它可能抛的一切都吞成"没消歧"。
 *
 * <p>⚠️ 那一处 {@code catch} 的<b>类型与包名</b>都有讲究。类型：要捕 {@link BeansException} 而不是
 * {@code NoSuchBeanDefinitionException}——一手 {@code javap} 显示
 * {@code NoUniqueBeanDefinitionException extends NoSuchBeanDefinitionException}，捕那个窄类型在别的分支上
 * 正是能吞掉"零个"这一真信号的写法；本类只在 {@code size() > 1} 分支里调用 {@code getIfAvailable()}，
 * 那里不可能合法地抛"零个"，捕 {@code BeansException} 既够宽也不会误导后来者。包名：{@code BeansException}
 * 在 {@code org.springframework.beans}（<b>不是</b> {@code org.springframework.beans.factory}），
 * 而 {@code ObjectProvider} 在后者的包里——照 {@code ObjectProvider} 那行顺手补的 import 会直接编译不过。
 *
 * <p><b>不缓存</b>：{@link #require()} 与 {@link #status()} 每次都重新问容器（{@code PG5} 钉这条）。
 * 缓存一次就等于把"启动期快照"当成运行期事实，而自动配置阶段的读取顺序与 {@code refresh()} 之后才注册的
 * bean 都会让快照说谎；代价只是每次调用一次 {@code stream()}，相对一次权限校验的开销可以忽略。
 *
 * <p><b>状态面不隐藏事实</b>：{@code @Primary} 让 {@link #require()} 能工作时，{@link #status()} 依然
 * 报 {@code MULTIPLE} 和两个实现类名（{@code PG4}）。判据是：诊断要回答的是"容器里有什么"，
 * 而调用要回答的是"我用哪个"；把前者做成后者的镜像，等于用 {@code @Primary} 掩盖一次真实的重复声明——
 * Task 51 那句"两家 provider 同时在 classpath"的展示要读的是前者。
 *
 * <p><b>键名是字符串而不是依赖</b>：{@link #PROVIDER_KEY} 与两个合法值只出现在诊断文本里。壳不引
 * provider 模块、也不镜像那份二选一判定（{@code @Conditional} 与判定函数由各 provider 各自镜像一份），
 * 因为 auth 这条线上壳与 provider 之间没有 compile 边（mq 侧有，那里的壳承载两家共用的执行逻辑，
 * 本壳不承载），而 provider↔provider 又被禁，判定逻辑没有第三个合法落点。
 */
public final class AuthPortGuard {

    /** provider 二选一键的<b>名字</b>：字符串，不是依赖。 */
    static final String PROVIDER_KEY = "myddd.auth.provider";

    /** {@value #PROVIDER_KEY} 的两个合法取值之一。 */
    static final String PROVIDER_SATOKEN = "satoken";

    /** {@value #PROVIDER_KEY} 的另一个合法取值。 */
    static final String PROVIDER_SECURITY = "security";

    /** 三种在场形态。管理端（Task 57）读的是这个而不是异常。 */
    public enum Presence {
        /** 恰好一个候选，或一个 {@code @Primary} 加若干陪跑。 */
        EXACTLY_ONE,
        /** 零个。 */
        NONE,
        /** 两个及以上。 */
        MULTIPLE
    }

    /**
     * 一次在场性快照。
     *
     * @param presence        形态
     * @param implementations 实现类的<b>用户类名</b>（CGLIB 代理已还原，见 {@link ClassUtils#getUserClass(Object)}），
     *                        按名字典序排列以便逐字断言；{@code NONE} 时为空表
     */
    public record Snapshot(Presence presence, List<String> implementations) {

        /** 防御性拷贝：快照一旦给出就不会被容器后续变化改写。 */
        public Snapshot {
            implementations = List.copyOf(implementations);
        }
    }

    private final ObjectProvider<AuthPort> ports;

    /**
     * @param ports 端口接缝；收 {@link ObjectProvider} 而不是 {@code AuthPort} 本身，与限流侧同一条理由：
     *              声明面不该要求某一种 provider 实现在场
     */
    public AuthPortGuard(ObjectProvider<AuthPort> ports) {
        if (ports == null) {
            throw new FrameworkException("AuthPortGuard 需要一个 ObjectProvider<AuthPort>，"
                    + "装配里拿到 null 说明调用写错了——这不是业务会碰到的路径，所以不给业务可读的文案");
        }
        this.ports = ports;
    }

    /**
     * 调用时需要的端口实例。
     *
     * <p>唯一候选直接给出；多候选时先给 {@code @Primary} 一次机会（合法的 Spring 惯用法），
     * 不给就报 {@code PG3} 那条诊断。
     */
    public AuthPort require() {
        List<AuthPort> candidates = candidates();
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw noPort();
        }
        AuthPort primary = primaryCandidate();
        if (primary != null) {
            return primary;
        }
        throw ambiguous(candidates);
    }

    /** 不抛异常的在场性快照：诊断与管理端读这里。 */
    public Snapshot status() {
        List<AuthPort> candidates = candidates();
        Presence presence = candidates.size() <= 1 ? (candidates.isEmpty() ? Presence.NONE : Presence.EXACTLY_ONE)
                : Presence.MULTIPLE;
        return new Snapshot(presence, names(candidates));
    }

    private List<AuthPort> candidates() {
        List<AuthPort> found = new ArrayList<>();
        this.ports.stream().forEach(found::add);
        return found;
    }

    /** 一手实测：这一句在多候选且无 {@code @Primary} 时会抛，因此它的返回值只在"消歧成功"时有意义。 */
    private AuthPort primaryCandidate() {
        try {
            return this.ports.getIfAvailable();
        } catch (BeansException ambiguous) {
            return null;
        }
    }

    private static List<String> names(List<AuthPort> candidates) {
        List<String> names = new ArrayList<>(candidates.size());
        for (AuthPort candidate : candidates) {
            names.add(ClassUtils.getUserClass(candidate).getName());
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private static FrameworkException noPort() {
        return new FrameworkException("权限校验需要一个 AuthPort bean，容器里一个都没有。"
                + "要么引一家 provider：" + "myddd-auth-satoken-starter（" + PROVIDER_KEY + "="
                + PROVIDER_SATOKEN + "）或 myddd-auth-security-starter（" + PROVIDER_KEY + "="
                + PROVIDER_SECURITY + "）——这一键留空时只有在 classpath 上恰好一份 provider 才会装配，"
                + "两份都在就必须写明选哪家，那是 provider 侧条件类在启动期就要拦的事（Task 49/50 的 PS4）；"
                + "要么自己提供一个 AuthPort 实现——切面只按类型取，不关心它是谁建的。"
                + "写 myddd.auth.enabled=false 关掉的是<b>切面本身</b>：那之后注解一次都不会被拦，"
                + "既不报错也不日志，这是本框架唯一被支持的静默形态，代价请见 package-info");
    }

    private static FrameworkException ambiguous(List<AuthPort> candidates) {
        return new FrameworkException("权限校验只能用一个 AuthPort，容器里有 " + candidates.size()
                + " 个且没有一个标了 @Primary：" + names(candidates)
                + "。两家 provider 同时在场的歧义不会走到这里（缺 " + PROVIDER_KEY + " 时 provider 的条件类"
                + "已经在启动期拒绝，Task 49/50 的 PS4），所以这里的多个只能是手工声明的 bean。"
                + "请删掉多余的那个，或给该当选的实现标 @Primary");
    }
}

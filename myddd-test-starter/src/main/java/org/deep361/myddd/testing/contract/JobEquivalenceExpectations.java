package org.deep361.myddd.testing.contract;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * spring 与 xxljob 两个 Job provider 的<b>共享期望表</b>：{@code JobProviderEquivalenceTestBase} 的
 * 八个 {@code EQ} 用例两侧各跑一遍，比的都是"本侧的答复 == 表里本侧那一格"。
 *
 * <p><b>为什么是一张表而不是一侧断另一侧</b>：两侧互相看不见（§0.3-11），所以"一侧改了规则另一侧没跟"
 * 这件事在两侧各自的 {@code PS} 组里都不可见（那五条两侧同形，各绿各的）。表是第三个真相：任何一方漂了
 * 规则，它自己那一列就对不上，红在漂的那一侧。
 *
 * <p><b>{@code null} 的用法是被不变式管住的</b>：某一格写 {@code null} 只允许两个理由——这一侧在这一行的
 * 输入下根本不会被调用到（本侧 marker 不在 {@code present} 里），或这一行的答案是"抛"（{@code ambiguous}）。
 * 这条不变式由 {@code EQ-T1} 断言，所以 {@code null} 不是"不知道填什么"的遮羞布。
 */
public final class JobEquivalenceExpectations {

    /** provider 标识：与两侧的 {@code JobProviderSelection.SPRING}/{@code XXLJOB} 逐字同值（{@code EQ-T3} 钉形状）。 */
    public static final String SPRING = "spring";
    /** 另一个 provider 的标识。 */
    public static final String XXLJOB = "xxljob";
    /**
     * provider → 该侧 port 实现类的全限定名。<b>字符串而不是 Class</b>：两份条件类在装配期互相探测就靠
     * 这两个名字（{@code Class.forName} 式的 marker 探测），而 {@code EQ3} 要断的是"本侧探测对方用的那个
     * 字符串 == 表里对方那一格"，以及"本侧自己那个类的真实 {@code getName()} == 表里本侧那一格"——
     * 后者只有子类能回答（它认得自己的类），于是"有人改名/挪包、另一侧的探测没跟"这件事当场红。
     */
    public static final Map<String, String> MARKERS = Map.of(
            SPRING, "org.deep361.myddd.job.spring.SpringJobPort",
            XXLJOB, "org.deep361.myddd.job.xxljob.XxlJobPort");

    /** 配置键，与两份 {@code JobProviderSelection.KEY} 同值（消息骨架断言用）。 */
    public static final String SELECTION_KEY = "myddd.job.provider";

    /** 判重消息的共享前缀：两侧的措辞在括号里不同（"表达式"/"cron"），前缀与"含任务名"必须相同。 */
    public static final String DUPLICATE_MESSAGE_PREFIX = "任务名重复注册：";

    /**
     * 一行输入与两侧的期望答复。
     *
     * @param configured   {@code myddd.job.provider} 的原始值，可为 {@code null} 或空白
     * @param present      classpath 上实际存在的 provider 标识（纯输入，允许出现"本侧不在场"的组合）
     * @param expectSpring spring 侧的期望答复；{@code null} 表示这一行在 spring 侧不可达，或该行是歧义（见类注释的不变式）
     * @param expectXxljob xxljob 侧的期望答复；同上
     * @param ambiguous    {@code true} 表示两侧在这一行都必须抛 {@code FrameworkException}（§0.3-6 的歧义判定）
     */
    public record Selection(String configured, Set<String> present, Boolean expectSpring,
                            Boolean expectXxljob, boolean ambiguous) {

        /** 本侧那一格：先按标识取值，不解释"为什么是 null"——那是 {@code EQ-T1} 的职责。 */
        public Boolean expectedFor(String provider) {
            return XXLJOB.equals(provider) ? expectXxljob : expectSpring;
        }

        /** 可达性判据：本侧 marker 在不在 {@code present} 里。{@code EQ-T1} 用它核对 {@code null} 的合法性。 */
        public boolean reachableFor(String provider) {
            return present.contains(provider);
        }
    }

    /** 两份 {@code Set.of(SPRING, XXLJOB)} 的简写。 */
    private static final Set<String> BOTH = Set.of(SPRING, XXLJOB);
    /** 两份单侧在场（零配置出路）的简写。 */
    private static final Set<String> ONLY_SPRING = Set.of(SPRING);
    private static final Set<String> ONLY_XXLJOB = Set.of(XXLJOB);

    /**
     * 十三个输入。分组理由：前两行是"显式选中"，第 3–4 行是"歧义"，第 5 行是"显式选中一个谁都不认识的"，
     * 第 6–13 行是"未写/空串/空白"三种形态在"只有一份"时的零配置出路（每种形态两侧各一行，
     * 因为 {@code PS} 组两侧那五条是各自被钉的，表要覆盖的是同一批输入的<b>交叉</b>答复）。
     */
    public static final List<Selection> SELECTIONS = List.of(
            new Selection(SPRING, BOTH, true, false, false),
            new Selection(XXLJOB, BOTH, false, true, false),
            new Selection(SPRING, ONLY_SPRING, true, null, false),
            new Selection(XXLJOB, ONLY_XXLJOB, null, true, false),
            new Selection(null, BOTH, null, null, true),
            new Selection("   ", BOTH, null, null, true),
            new Selection("kafka", BOTH, false, false, false),
            new Selection(null, ONLY_SPRING, true, null, false),
            new Selection("", ONLY_SPRING, true, null, false),
            new Selection("   ", ONLY_SPRING, true, null, false),
            new Selection(null, ONLY_XXLJOB, null, true, false),
            new Selection("", ONLY_XXLJOB, null, true, false),
            new Selection("   ", ONLY_XXLJOB, null, true, false));

    /** 声明面夹具：两侧共用同一份 {@code @MydddJob} 声明，所以 name/cron/description 没有第二个真源。 */
    public static final String DECLARED_JOB_NAME = EquivalenceJobFixtures.JOB_NAME;
    /** 与上一行同源。 */
    public static final String DECLARED_CRON = EquivalenceJobFixtures.JOB_CRON;
    /** 与上一行同源。 */
    public static final String DECLARED_DESCRIPTION = EquivalenceJobFixtures.JOB_DESCRIPTION;

    /** 派发参数：逐字透传的用例按这三行跑（含空串与带空格，不给任何一侧"顺手 trim"的余地）。 */
    public static final List<String> DISPATCH_PARAMS = List.of("2026-09-30", "", "  2026-09-30  ");

    /** {@code dispatch(name, null)} 之后 handler 应当读到的参数：内核 {@code JobContext} 已把 null 规范成空串。 */
    public static final String NULL_PARAM_EXPECTED = "";

    /** 非法 cron 与用它注册的任务名：spring 侧注册期拒、xxljob 侧照收（R7 承认的不对称，{@code EQ8} 钉的就是这一格）。 */
    public static final String INVALID_CRON = "abc";
    /** 与 {@code DECLARED_JOB_NAME} 不重叠，免得非法 cron 那一行把正常注册的用例污染成先失败。 */
    public static final String INVALID_CRON_JOB_NAME = "eq-invalid-cron";
    /** {@code EQ5} 第二次注册用的 cron：必须<b>两侧都合法</b>，否则 spring 侧会先红在 cron 校验而不是判重（{@code 41d:108} 的次序）。 */
    public static final String SECOND_CRON = "0 30 4 * * ?";
    /** 本侧是否在注册期校验 cron（{@code true}＝拒）。这一格进 {@code EQ8}：不对称要被钉住，而不是被抹平。 */
    public static final Map<String, Boolean> VALIDATES_CRON_AT_REGISTRATION = Map.of(SPRING, true, XXLJOB, false);

    private JobEquivalenceExpectations() {
    }
}

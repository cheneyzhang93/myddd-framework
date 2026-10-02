package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.job.MydddJob;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期望表<b>自身的形状</b>：表是两侧共同的真相，那它自己就必须先自洽。
 * 四条断言都在钉"这张表还能不能被当判据"这件事，不重复 {@code PS} 组任何一条。
 */
class JobEquivalenceExpectationsTest {

    private static final String SPRING = JobEquivalenceExpectations.SPRING;
    private static final String XXLJOB = JobEquivalenceExpectations.XXLJOB;

    /** EQ-T1：每一个 {@code null} 格都必须有理由（本侧在这一行不可达，或这一行的答案是抛）。 */
    @Test
    void should_justify_every_null_cell_in_the_selection_table() {
        for (JobEquivalenceExpectations.Selection row : JobEquivalenceExpectations.SELECTIONS) {
            for (String provider : new String[] {SPRING, XXLJOB}) {
                boolean justified = row.expectedFor(provider) == null
                        ? (!row.reachableFor(provider) || row.ambiguous())
                        : row.reachableFor(provider);
                assertTrue(justified, "答复没有理由（null 却没理由，或填了值却不可达）："
                        + row + "，provider=" + provider);
            }
        }
    }

    /** EQ-T2：§0.3-6 在表这一层就不可能破，且歧义与"谁都不认识"两种形态都在表里有行。 */
    @Test
    void should_keep_the_table_free_of_both_providers_true_and_cover_both_refusals() {
        int ambiguousRows = 0;
        int bothFalseAndReachable = 0;
        for (JobEquivalenceExpectations.Selection row : JobEquivalenceExpectations.SELECTIONS) {
            assertFalse(Boolean.TRUE.equals(row.expectSpring())
                    && Boolean.TRUE.equals(row.expectXxljob()), "同一输入两侧都装配：" + row);
            if (row.ambiguous()) {
                ambiguousRows++;
            }
            if (Boolean.FALSE.equals(row.expectSpring()) && Boolean.FALSE.equals(row.expectXxljob())) {
                bothFalseAndReachable++;
            }
        }
        assertTrue(ambiguousRows >= 1, "表里没有歧义行：§0.3-6 的抛那一支没人比");
        assertTrue(bothFalseAndReachable >= 1, "表里没有'显式选中一个谁都不认识的'那一行：不猜意图的规矩没人比");
        /*
         * 完备性这一半是这张表能被当成判据的前提：三种"没写这一键"的形态（{@code null}、空串、空白）在
         * "只有一份 provider 在场"时各有两侧的一行。少一行不是"少一条用例"，而是那一支输入从此<b>无人互比</b>——
         * {@code PS} 组两侧的第五件事（trim 规则）就再没有交叉证据。
         */
        // 片段原文这里写的是 List.of((String) null, "", "   ")：List.of 不收 null 元素，会在循环开始前就抛
        // NullPointerException（实测 JDK 语义）。同一份列表换用允许 null 的 Arrays.asList，判据一字不改。
        for (String form : Arrays.asList((String) null, "", "   ")) {
            for (String single : List.of(SPRING, XXLJOB)) {
                Set<String> present = Set.of(single);
                assertTrue(JobEquivalenceExpectations.SELECTIONS.stream()
                                .anyMatch(row -> Objects.equals(form, row.configured())
                                        && row.present().equals(present) && !row.ambiguous()),
                        "零配置出路少一行：configured=" + form + "，present=" + present);
            }
        }
    }

    /**
     * EQ-T3：marker 表的形状与依赖方向。两个名字必须<b>加载不到</b>——这条是 §0.3-11 的守卫：
     * test-starter 的 main 一旦真去依赖某个 provider，这一条就不是 {@code ClassNotFoundException} 而是通过，
     * 于是"共享期望表"悄悄变成了"单向耦合"。
     */
    @Test
    void should_keep_exactly_two_distinct_markers_unloadable_from_here() {
        assertEquals(Set.of(SPRING, XXLJOB), JobEquivalenceExpectations.MARKERS.keySet());
        Set<String> classNames = new HashSet<>(JobEquivalenceExpectations.MARKERS.values());
        assertEquals(2, classNames.size(), "两个 provider 的 marker 撞成同一个类名");
        for (String marker : JobEquivalenceExpectations.MARKERS.values()) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(marker),
                    marker + " 在 test-starter 的 classpath 上：期望表已经反向依赖 provider 了");
        }
    }

    /** EQ-T4：声明面常量与注解逐字同值，且非法 cron 那一行确实是"另一个值、另一个名字"。 */
    @Test
    void should_keep_the_fixture_declaration_and_the_constants_identical() {
        MydddJob annotation = EquivalenceJobFixtures.RecordingJobHandler.class.getAnnotation(MydddJob.class);
        assertNotNull(annotation, "夹具类没带 @MydddJob：两侧扫描器根本不会装配它");
        assertEquals(EquivalenceJobFixtures.JOB_NAME, annotation.name());
        assertEquals(EquivalenceJobFixtures.JOB_CRON, annotation.cron());
        assertEquals(EquivalenceJobFixtures.JOB_DESCRIPTION, annotation.description());
        assertTrue(JobEquivalenceExpectations.DISPATCH_PARAMS.contains(""),
                "派发参数里没有空串：'参数为空'这一形态两侧没比过");
        assertTrue(JobEquivalenceExpectations.DISPATCH_PARAMS.stream().anyMatch(each -> !each.isEmpty()),
                "派发参数里全是空串：逐字透传等于没比");
        assertTrue(JobEquivalenceExpectations.DISPATCH_PARAMS.stream()
                        .anyMatch(each -> !each.equals(each.trim())),
                "派发参数里没有带首尾空格的形态：适配器顺手 trim 一次，两侧一致而都与原文不同，EQ6 看不见");
        assertFalse(JobEquivalenceExpectations.DECLARED_CRON.equals(JobEquivalenceExpectations.INVALID_CRON),
                "非法 cron 与合法 cron 同值：EQ8 那一格失去意义");
        assertFalse(JobEquivalenceExpectations.DECLARED_JOB_NAME
                .equals(JobEquivalenceExpectations.INVALID_CRON_JOB_NAME),
                "两个名字相同：非法 cron 那一行会先把正常注册弄成重复注册");
    }
}

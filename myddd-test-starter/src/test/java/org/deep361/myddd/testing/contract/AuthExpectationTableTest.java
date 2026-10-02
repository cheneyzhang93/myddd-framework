package org.deep361.myddd.testing.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 期望表自身的六条元用例（{@code EQ-T1}–{@code EQ-T6}）：表与不对称清单<b>双向闭合</b>，
 * 以及"唯一那一行表达不了"必须还是那一行。
 *
 * <p>三条读文件的判据各有不同的可用性，所以读法在这里说死：① 清单路径走系统属性
 * {@code myddd.auth.asymmetry.doc}（缺省 {@code docs/auth/asymmetry.md}），落不到就从当前目录逐级向上找
 * 同名文件——从模块目录跑测试时相对路径指不到仓库根；仍找不到就 <b>fail</b>（读不到等于双向闭合失效，
 * 不许 {@code assumeTrue} 放过）。② 子类源码由兄弟模块持有，本模块不依赖它们，单模块构建时看不见：
 * 文件不在时跳过并<b>打印原因</b>，不许静默绿。
 */
class AuthExpectationTableTest {

    private static final String DOC_PROPERTY = "myddd.auth.asymmetry.doc";
    private static final String DOC_DEFAULT = "docs/auth/asymmetry.md";
    private static final String SATOKEN_SUBCLASS =
            "myddd-adapters/myddd-auth-satoken-starter/src/test/java/org/deep361/myddd/auth/satoken/"
                    + "AuthSatokenEquivalenceTest.java";
    private static final String SECURITY_SUBCLASS =
            "myddd-adapters/myddd-auth-security-starter/src/test/java/org/deep361/myddd/auth/security/"
                    + "AuthSecurityEquivalenceTest.java";

    /** EQ-T1：除装配那一行外，场景名互不重复——两家共用进程级静态面时，重名会让上一行的残留影响下一行。 */
    @Test
    void should_keep_scenario_names_unique_except_the_assembly_row() {
        List<String> names = AuthExpectationTable.ALL.stream()
                .filter(row -> row.kind() != AuthExpectationTable.Kind.SELECTION)
                .map(AuthExpectationTable::scenario)
                .toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    /** EQ-T2：期望必须是一句可判真假的话——"两侧一致"是同义反复，等于没写。 */
    @Test
    void should_require_an_actual_claim_in_every_expected_cell() {
        for (AuthExpectationTable row : AuthExpectationTable.ALL) {
            String plain = row.expected().replaceAll("<[^>]+>", "");
            assertThat(plain)
                    .as("%s 的期望必须超过一句套话：%s", row.scenario(), plain)
                    .hasSizeGreaterThan(20);
        }
    }

    /** EQ-T3：表里写过的不对称编号，清单里必须真有一条——否则一条不对称悄悄消失。 */
    @Test
    void should_find_a_checklist_entry_for_every_referenced_asymmetry() {
        Set<String> headings = asymmetryHeadings();
        for (AuthExpectationTable row : AuthExpectationTable.ALL) {
            if (row.asymmetryRef() != null) {
                assertThat(headings)
                        .as("%s 引用了 %s，清单里必须有这一条", row.scenario(), row.asymmetryRef())
                        .contains(row.asymmetryRef());
            }
        }
    }

    /**
     * EQ-T4：两列的形态族必须一致——一列是夹具准备、一列是配置键，等于两侧比的不是同一层。
     * {@code NOT_EXPRESSIBLE} 与它配对（只有一侧表达得了时，另一侧必须是那个哨兵而不是别的写法）。
     */
    @Test
    void should_keep_the_two_documentation_cells_in_the_same_shape_family() {
        for (AuthExpectationTable row : AuthExpectationTable.ALL) {
            boolean satokenSentinel = isSentinel(row.satokenSetup());
            boolean securitySentinel = isSentinel(row.securitySetup());
            if (satokenSentinel ^ securitySentinel) {
                // 只有一侧表达得了：这就是不对称本身，必须带编号，否则"表达不了"成了无主声明。
                assertThat(row.asymmetryRef())
                        .as("%s 只有一侧表达得了，必须指向清单里的某一条", row.scenario())
                        .isNotBlank();
                continue;
            }
            if (!satokenSentinel) {
                assertThat(prefixOf(row.securitySetup()))
                        .as("%s 两列的形态前缀必须同族（setup: 与 props: 不是同一层）", row.scenario())
                        .isEqualTo(prefixOf(row.satokenSetup()));
            }
        }
    }

    /** EQ-T5：清单里每个条目都得被表反向引用——否则清单会变成第二份"设计文档"。 */
    @Test
    void should_reference_every_checklist_entry_from_the_table() {
        Set<String> referenced = AuthExpectationTable.ALL.stream()
                .map(AuthExpectationTable::asymmetryRef)
                .filter(ref -> ref != null)
                .collect(Collectors.toSet());
        assertThat(asymmetryHeadings()).containsExactlyInAnyOrderElementsOf(referenced);
    }

    /** EQ-T6：{@code NOT_EXPRESSIBLE} 恰好一行且是 {@code EQ7}；两侧子类里被跳过的必须还是这一行。 */
    @Test
    void should_skip_exactly_the_one_row_that_cannot_be_expressed() {
        assertThat(sentinelRows()).containsExactly(AuthExpectationTable.EQ7);

        Path root = repositoryRoot();
        String satoken = readIfPresent(root.resolve(SATOKEN_SUBCLASS));
        assumeTrue(satoken != null,
                () -> "satoken 侧子类源码不可见（" + SATOKEN_SUBCLASS + "）：单模块构建时属正常，跳过这一格");
        assertThat(satoken).contains("EQ7");

        String security = readIfPresent(root.resolve(SECURITY_SUBCLASS));
        assumeTrue(security != null,
                () -> "security 侧子类源码不可见（" + SECURITY_SUBCLASS + "）：单模块构建时属正常，跳过这一格");
        // security 一侧表达得了全部八行：它自己不许写任何跳过入口（哨兵字面量会出现在类注释里，
        // 判"有没有跳过入口"只能判 assumeTrue——那是基类唯一的跳过机制）。
        assertThat(security).doesNotContain("assumeTrue").contains("providerTag");
    }

    private static List<AuthExpectationTable> sentinelRows() {
        return AuthExpectationTable.ALL.stream()
                .filter(row -> AuthExpectationTable.NOT_EXPRESSIBLE.equals(row.satokenSetup())
                        || AuthExpectationTable.NOT_EXPRESSIBLE.equals(row.securitySetup()))
                .toList();
    }

    private static boolean isSentinel(String cell) {
        return AuthExpectationTable.NOT_EXPRESSIBLE.equals(cell);
    }

    private static String prefixOf(String cell) {
        int index = cell.indexOf(':');
        return index < 0 ? "" : cell.substring(0, index + 1);
    }

    /** 清单里所有 {@code ## A-n} 条目的编号。 */
    private static Set<String> asymmetryHeadings() {
        String text = readChecklist();
        return text.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("## A"))
                .map(line -> line.substring(2).trim().split("[ —-]")[0])
                .collect(Collectors.toSet());
    }

    private static String readChecklist() {
        Path path = resolve(Path.of(System.getProperty(DOC_PROPERTY, DOC_DEFAULT)));
        if (path == null) {
            throw new AssertionError("读不到不对称清单（属性 " + DOC_PROPERTY + "，缺省 " + DOC_DEFAULT
                    + "，并已自当前目录逐级向上查过）——双向闭合失效，这条判据不许放过");
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("清单找到了却读不出：" + path, failure);
        }
    }

    /** 先按给定路径试，再从当前目录逐级向上找同一个相对路径（模块目录与仓库根相差一级）。 */
    private static Path resolve(Path relative) {
        if (Files.isRegularFile(relative)) {
            return relative.toAbsolutePath();
        }
        String normalized = relative.toString().replace('\\', '/');
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path candidate = directory.resolve(normalized);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 清单所在目录的上一级即仓库根（清单恒为 {@code docs/auth/asymmetry.md}）。 */
    private static Path repositoryRoot() {
        Path path = resolve(Path.of(System.getProperty(DOC_PROPERTY, DOC_DEFAULT)));
        assertThat(path).as("仓库根必须由清单位置反推").isNotNull();
        return path.getParent().getParent().getParent();
    }

    private static String readIfPresent(Path path) {
        return Files.isRegularFile(path) ? new String(readBytes(path), StandardCharsets.UTF_8) : null;
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new IllegalStateException("读不到 " + path, failure);
        }
    }
}

package org.deep361.myddd.testing.contract;

import java.util.List;

/**
 * auth 两家的<b>期望表</b>：一行 = 一个"能力语义"问题，两列 = 该问题在两家的表达（各自的夹具准备动作），
 * 一值 = 两家都必须给出的答案（或 {@code NOT_EXPRESSIBLE} + 不对称编号）。
 *
 * <p><b>为什么不直接写 8 条断言</b>：等价性最容易作假的形态是"两侧各自断一个绝对值"——那两个绝对值
 * 可以各按各的脾气调，读起来却像等价。本表把<b>期望</b>抽成第三列，两家只能对同一句话负责，
 * 改期望必须改表（表的改动会同时让两侧的子类重跑）。
 *
 * <p><b>与 {@code AuthPortContractTestBase} 的分工</b>：那八条 {@code AUTH-C} 问的是"这一家对不对"，
 * 本表八条问的是"两家对同一句话给的是不是同一个答案"。看起来重复，实际防的是不同的坏法：
 * 改坏一家时 {@code AUTH-C} 红，而<b>同时改两家的脾气</b>（例如两侧都把"半截身份"改成放行）
 * 只有本表会红——因为它两侧断的是同一个 {@code expected} 字符串所描述的关系。
 *
 * <p><b>{@code NOT_EXPRESSIBLE} 是一等公民</b>：它必须带 {@code asymmetryRef}，且两侧子类跑到的行里
 * 被跳过的<b>恰好</b>是这些行（{@code EQ-T6}）。用 skip 蒙过一行而不登记，是本任务最坏的失败模式。
 */
public record AuthExpectationTable(
        String scenario, Kind kind, String satokenSetup, String securitySetup, String expected, String asymmetryRef) {

    /** 这一行测的是哪个能力面。 */
    public enum Kind { LOGIN, PERMISSION, LOGOUT, HIERARCHY, SELECTION }

    /** 表达不了时的哨兵值（不是 {@code null}：null 会让"忘了填"与"确实表达不了"同形）。 */
    public static final String NOT_EXPRESSIBLE = "NOT_EXPRESSIBLE";

    /** 登录成功：身份必须完整。两列写的是各家的准备动作，前缀 {@code setup:} 由 {@code EQ-T4} 管形态。 */
    public static final AuthExpectationTable EQ1 = new AuthExpectationTable(
            "eq:login-ok", Kind.LOGIN,
            "setup:verifier 返回 knownUser + 登录成功之后读 account session 里的用户名",
            "setup:DaoAuthenticationProvider + 把认证成功后的 Authentication 装进 SecurityContextHolder",
            "login(knownCredentials) 之后 currentUser() 必须有值，且四字段与<b>这一家自己声明的</b> knownUser() 逐字相同"
                    + "（不许出现只填一半的身份；两家的 knownUser() 允许互不相同）",
            "A2");

    /** 口令错误：对外语义只有一条，两家各自经过不同的厂商分支。 */
    public static final AuthExpectationTable EQ2 = new AuthExpectationTable(
            "eq:login-bad", Kind.LOGIN,
            "setup:verifier 对 brokenCredentials 返回 empty",
            "setup:DaoAuthenticationProvider 对 brokenCredentials 抛 BadCredentialsException",
            "login(brokenCredentials) 必须抛 BizException(UNAUTHORIZED)，且抛出之后 currentUser() 仍为空；"
                    + "\"用户不存在\"与\"口令错误\"两条分支在端口面必须给同一个答案",
            "A1");

    /** 权限判定：同一份授予集合下的命中与未命中。 */
    public static final AuthExpectationTable EQ3 = new AuthExpectationTable(
            "eq:perm-set", Kind.PERMISSION,
            "setup:权限源给出 [order:read]（SaManager 的 StpInterface 静态面）",
            "setup:token 的 authorities 含 granted(\"order:read\")",
            "已授予的名字 hasPermission=true；未授予的名字返回 false 且<b>不抛</b>；"
                    + "两侧都不许因为\"这一家另有匹配规则\"而改变这两格",
            "A4");

    /** 空白权限名：在问任何来源之前快速失败。 */
    public static final AuthExpectationTable EQ4 = new AuthExpectationTable(
            "eq:perm-blank", Kind.PERMISSION,
            "setup:已登录身份 + hasPermission(\"   \")",
            "setup:已装好带权限的上下文 + hasPermission(\"   \")",
            "空白权限名必须抛 FrameworkException 且消息含参数名（含出路：写清楚是哪个方法收到的空串），"
                    + "不许把它判成\"没有权限\"返回 false——那是把代码写错降级成越权拒绝",
            null);

    /** 登出：身份与权限一起消失（端口面）。 */
    public static final AuthExpectationTable EQ5 = new AuthExpectationTable(
            "eq:logout", Kind.LOGOUT,
            "setup:登录成功之后 logout()",
            "setup:装好身份之后 logout()",
            "logout() 之后 currentUser() 为空、对已授予的名字 hasPermission 返回 false；"
                    + "再登录一次必须能把完整身份装回来",
            "A5");

    /** 无身份时的登出：空操作而不是抛。 */
    public static final AuthExpectationTable EQ6 = new AuthExpectationTable(
            "eq:logout-anonymous", Kind.LOGOUT,
            "setup:从未登录过的线程直接 logout()",
            "setup:装空上下文（有 context、无 authentication）直接 logout()",
            "无身份时 logout() 是空操作：不抛、不把 holder 写成不可写、之后仍能正常装回身份",
            "A5");

    /** 角色层级：只有一家有这个概念 ⇒ 不放进等价断言，但必须登记而不是悄悄少测一行。 */
    public static final AuthExpectationTable EQ7 = new AuthExpectationTable(
            "eq:hierarchy", Kind.HIERARCHY,
            NOT_EXPRESSIBLE,
            "setup:容器里放 RoleHierarchyImpl.fromHierarchy(\"ROLE_admin > ROLE_user\") + 授予 ROLE_admin，"
                    + "再问 hasPermission(\"user:read\")",
            "satoken 侧没有\"角色蕴含\"这个概念（权限就是一串精确名字）⇒ 这一行不是同一句话，"
                    + "不放进等价断言；两家的<b>端口</b>读数本来都不跟随层级（Task 50 的 AC6 钉 security 那一格）",
            "A3");

    /** 装配决策：同一份键（不写 provider 且两家都在 classpath）两侧的答复必须相反。 */
    public static final AuthExpectationTable EQ8 = new AuthExpectationTable(
            "eq:selection", Kind.SELECTION,
            "props:myddd.auth.provider 未写 + 两家 jar 都在",
            "props:myddd.auth.provider 未写 + 两家 jar 都在",
            "两侧都抛 FrameworkException，消息含彼此的名字与两个合法值（谁也不许\"顺手装配\"）",
            "A6");

    /** 全表（{@code EQ-T1} 用它判重复场景名，{@code EQ-T4} 用它判两列的形态族闭合）。 */
    public static final List<AuthExpectationTable> ALL = List.of(EQ1, EQ2, EQ3, EQ4, EQ5, EQ6, EQ7, EQ8);
}

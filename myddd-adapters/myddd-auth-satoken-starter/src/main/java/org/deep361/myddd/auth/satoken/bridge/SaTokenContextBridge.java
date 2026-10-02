package org.deep361.myddd.auth.satoken.bridge;

import cn.dev33.satoken.exception.InvalidContextException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;

import java.util.List;
import java.util.Optional;

/**
 * R9 的唯一归一点：Sa-Token 侧"这一线程没有请求上下文"与"有上下文但没有登录"两支异常，
 * 在本框架只有一种表达——<b>读身份给空、判权限给 false、登出给空操作，一律不抛</b>。
 *
 * <p>本类是模块内唯一允许 {@code catch} 厂商异常的地方，其余代码读 Sa-Token 一律经过这里。理由不是整洁，
 * 是正确性：{@code StpUtil} 的上下文型方法（{@code getLoginIdDefaultNull}/{@code isLogin}/
 * {@code logout()}/{@code getPermissionList()} 无 id 版……）在非 Web 线程上<b>全部抛</b>
 * {@code SaTokenContextException}（一手：探针 49a 逐个 API 读数；继承链一手：探针 49e 打出
 * {@code SaTokenContextException<InvalidContextException<SaTokenException<RuntimeException<}），
 * 而 {@code AuthPort} 的 javadoc 承诺的是"未登录返回 empty、hasPermission 返回 false"。
 * 中间必须有人把异常换成缺省值，而那个人只能是我——业务不该在每一次判权限前写 try/catch。
 *
 * <p><b>catch 面恰好两支，不是 {@code SaTokenException}</b>（探针 49a 的{@code getExtra} 那一格是
 * 决定性证据：未集成 {@code sa-token-jwt} 时它抛 {@code ApiDisabledException}（{@code code=11031}），
 * 那同样是 {@code SaTokenException} 的子类）。把"配置用错了"归一成"这个人没登录"，等于把一条会立刻
 * 暴露的错误压成一条永远不会暴露的行为异常。{@code RS5}/{@code RS6} 一对用例钉的就是这条边界。
 *
 * <p><b>不做</b>的事同样写死在这里：不排序、不去重、不裁剪、不把厂商列表换成不可变副本——
 * {@code permissions()}/{@code roles()} 原样转交（探针 49e 一手读数：返回的就是业务 {@code StpInterface}
 * 给出的<b>同一个实例</b>，{@code [z:read, a:read, z:read]} 连重复项与顺序都保留）。
 * 不可变性由内核一侧负责（{@code AuthUser} 的紧凑构造器做 {@code Set.copyOf}，一手：
 * {@code AuthUser.java:22-49}），这里再拷一份只会让"谁的列表被改了"这条排查线断在中间。
 */
public final class SaTokenContextBridge {

    private SaTokenContextBridge() {
    }

    /**
     * 当前线程的 loginId，归一成 {@code String}（内核 {@code AuthUser.userId} 要的形态）。
     *
     * <p>无上下文或未登录 ⇒ {@link Optional#empty()}。厂商在登录后读回的本就是 {@code String}
     * （探针 49e：{@code StpUtil.login(10001L)} 之后 {@code getLoginIdDefaultNull()} 的
     * {@code class=java.lang.String}），{@code String::valueOf} 这一格不是防御，是把
     * {@code Object} 签名收成 {@code Optional<String>} 的类型转换本身。
     */
    public static Optional<String> currentLoginId() {
        try {
            return Optional.ofNullable(StpUtil.getLoginIdDefaultNull()).map(String::valueOf);
        } catch (InvalidContextException | NotLoginException absent) {
            return Optional.empty();
        }
    }

    /**
     * 该 loginId 的权限列表，<b>原样</b>转交；业务 {@code StpInterface} 返回 {@code null} 时归一成空列表。
     *
     * <p>后者不是想象出来的分支：探针 49e 一手读数——权限源返回 {@code null} 时
     * {@code StpUtil.getPermissionList(id)} <b>就把 {@code null} 交出来</b>（它自己不兜底），
     * 于是 {@code .contains(...)} 会在厂商栈帧里 NPE。一个第三方实现的书写错误不该变成端口的 500。
     *
     * <p>这一格走的是"显式传 id"那一族（探针 49b 的清单：{@code getPermissionList(id)}/
     * {@code getRoleList(id)}/{@code hasPermission(id,p)}/{@code isLogin(id)}/
     * {@code getSessionByLoginId(id)}/{@code logout(id)}/{@code createLoginSession(id)}），
     * <b>不碰请求上下文</b>，所以批处理线程拿到一个已知 loginId 时照样能读权限——这是本方法收 {@code Object}
     * 而不是无参的原因。
     */
    public static List<String> permissions(Object loginId) {
        return nullToEmpty(StpUtil.getPermissionList(loginId));
    }

    /** 该 loginId 的角色列表，语义与 {@link #permissions(Object)} 完全一致（原样转交 + {@code null} 兜底）。 */
    public static List<String> roles(Object loginId) {
        return nullToEmpty(StpUtil.getRoleList(loginId));
    }

    /**
     * 把"没有身份"归一成空操作地执行一次动作——{@code logout()} 的那一层。
     *
     * <p>探针 49a：{@code StpUtil.logout()} 在无上下文线程上抛 {@code SaTokenContextException}；
     * 而端口 javadoc 写的是"未登录时为空操作"。异常在这里被吃掉是<b>契约要求</b>，不是宽容。
     */
    public static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (InvalidContextException | NotLoginException absent) {
            // 归一：这两支在端口面的表达就是"没有身份"。其余异常照抛（RS6 钉这条边界）。
        }
    }

    private static List<String> nullToEmpty(List<String> source) {
        return source == null ? List.of() : source;
    }
}

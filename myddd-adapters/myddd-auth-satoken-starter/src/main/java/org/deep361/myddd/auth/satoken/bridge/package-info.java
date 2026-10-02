/**
 * R9 的唯一归一点：把"没有请求上下文"与"有上下文但没登录"两支厂商异常换成端口缺省值。
 *
 * <p><b>本包只认精确匹配</b>，代价与取舍写在这里：Sa-Token 自己的 {@code StpUtil.hasPermission}
 * 是通配语义（探针 49c 一手：权限源给 {@code order:*} 时 {@code order:read} 判 true，
 * 而 {@code USER:READ} 判 false——大小写敏感、{@code a:*} 不匹配 {@code a.b.c}），内核
 * {@code AuthUser.hasPermission} 的 javadoc 明文"精确匹配，不做通配"。同一个端口里并存两种答案
 * 就是"同一个用户在同一毫秒里得到两个结论"：{@code @RequiresPermission} 走端口，业务代码里手写
 * {@code currentUser()} 再判的人走另一条。裁定是端口这一侧只认精确匹配（{@code D-183}，钉子 {@code AB7}）；
 * <b>要通配，就在你自己的 {@code StpInterface} 里把它展开成具体权限码</b>。厂商的通配能力仍然留给
 * 它自己的注解（{@code @SaCheckPermission} 等），那是厂商的地盘，本框架不代言。
 *
 * <p><b>catch 恰好两支，不是 {@code SaTokenException} 整个基类</b>：基类下还挂着
 * {@code ApiDisabledException}（{@code code=11031}）——那是"配置用错了"，归一成"这个人没登录"
 * 会把一条立刻可见的错误压成一条永远不可见的行为异常（{@code RS6} 钉这一格）。
 *
 * <p><b>权限与角色列表原样转交、不做加工</b>（{@code RS4}）：不可变性由内核
 * {@code AuthUser} 的 {@code Set.copyOf} 负责，这里再拷一份只会让"谁的列表被改了"这条排查线断在中间。
 */
package org.deep361.myddd.auth.satoken.bridge;

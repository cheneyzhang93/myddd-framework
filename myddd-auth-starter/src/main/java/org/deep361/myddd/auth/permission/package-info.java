/**
 * {@code @RequiresPermission} 的执行侧：切面、声明解析与启动期声明校验。
 *
 * <p><b>注解要写在实现类上，写在接口上不会生效</b>。一手实测（探针 {@code 48e}，CGLIB 与 JDK 两种
 * 代理各跑一遍，{@code D:\tmp\p4probe\auth\p48eout.txt}）：注解标在<b>接口的方法</b>或<b>接口类型</b>上时
 * 切点门一次都不开，而反射面读得到（{@code hierOnMethod=yes}）——"注解标着、一次都没拦、不报错也不日志"
 * 就是这么产生的。本包不让它静默：{@code PermissionDeclarationValidator} 在启动期把这两类形态报成错误
 * （{@code RS7}）。
 *
 * <p><b>被子类覆盖的父类方法同样会关掉保护</b>（探针 {@code 48g}：{@code directOnMethod=no
 * hierOnMethod=yes@ParentMarked#marked} 而门不开）。裁定与 Task 44 不同：那里不为"替应用复查它刚做的
 * 决定"做全 bean 扫描，这里扫的是<b>本来就必须在启动期校验的声明形状</b>，多报这一格零额外成本，
 * 而它是一条安全形态的洞（父类有保护、子类重写后静默失去），所以启动期拒（{@code RS6}，两种形状）。
 *
 * <p><b>解析链是三级 {@code DIRECT}，不是 {@code TYPE_HIERARCHY}</b>：方法 → 目标类 → 执行方法的声明类。
 * 用 {@code DIRECT} 的两条后果都写进了用例：{@code @Inherited} 缺席导致的"父类标注在子类上生效"
 * 这一格必须判<b>不生效</b>（{@code RS5}），而 {@code AT} 组里"父类标注、子类未覆盖的方法执行"仍然要拦
 * （{@code AT11}，因为方法本身就是父类那个方法）。两格读起来像矛盾，判据是同一条：<b>门开不开</b>由
 * 切点决定（探针 48e），<b>声明读不读得到</b>由这条三级链决定，两者必须同源，否则会出现
 * "门开了但读到一条本该不生效的声明"或反之。
 *
 * <p><b>坏声明在构造时就拒</b>：空数组、空白元素、{@code trim} 后重复各一条消息
 * （{@code RS8}/{@code RS9}），并且发生在 {@code AuthPort} 被问之前（{@code AT10}）——
 * 一条永远不可能满足的声明不该先惊动权限数据源。
 *
 * <p><b>本包不管权限数据从哪来</b>：某个权限码在库里存不存在，归 provider 的 {@code StpInterface}
 * 与 {@code AuthenticationManager} 的权限面（R15 的 fail-fast 是 Task 49 的自检）。壳管的是
 * "声明写得对不对、这次调用该不该拒"，两层一旦混起来就没人能解释"为什么改了数据库权限要重启应用"。
 *
 * <p><b>{@code message()} 的兑现路径</b>：非空时它进 {@code PermissionRejection.getMessage()}，
 * web 侧 {@code AnnotationCarriedMessage} 让它在 {@code Response.message} 上压过 i18n 缺省
 * （R12；这条链的两半分别由 {@code AT6} 与 {@code TX} 钉，缺一半都不成立，见 D-172）。
 */
package org.deep361.myddd.auth.permission;

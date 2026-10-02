/**
 * 鉴权能力的 provider 中立装配壳（R1，Plan 4 批的第三个壳）。
 *
 * <p><b>零厂商依赖</b>：本模块的 compile 依赖只有内核端口/错误码与 Spring（一手：本模块 {@code pom.xml}）。
 * Sa-Token 与 Spring Security 的坐标出现在这里就等于把"换 provider 不改业务"判死，
 * 所以 {@code AC8} 用 {@code Class.forName} 反向钉住它：{@code cn.dev33.satoken.stp.StpUtil} 与
 * {@code org.springframework.security.authentication.AuthenticationManager} 两个类名在本模块的
 * <b>测试</b>类路径上也必须加载不到——测试类路径才是最容易被人顺手加 {@code test} 依赖的地方。
 *
 * <p><b>本壳一个 {@code AuthPort} bean 都不产</b>：端口由各 provider 实现（Task 49 satoken /
 * Task 50 security），壳只在"零个"与"多个"时给启动期诊断与调用期诊断（{@code config} 包）。
 *
 * <p><b>401 与 403 的分流只有一条判据</b>：{@code AuthPort.currentUser()} 为空 ⇒ 401
 * （{@code CommonErrorCode.UNAUTHORIZED}），非空但权限不足 ⇒ 403（{@code FORBIDDEN}）。
 * 把未登录判成权限不足会让前端不去跳登录页——那是一条真业务故障，写在 {@code permission} 包说明的
 * 第一段，用例是 {@code AT4}/{@code AT5}。
 *
 * <p><b>当前用户只有一个来源</b>：{@code AuthPort.currentUser()}。壳里<b>没有</b>、也刻意不增设
 * 一个 {@code Supplier<AuthUser>} 式的"当前用户"接缝，理由与判据写在
 * {@code PermissionCheckAspect} 的类注释；多一个读法就多一份一致性要维护，而 provider 换一家时
 * 没人会去检查两份读法是否还同意彼此。
 *
 * <p><b>唯一的静默形态</b>：{@code myddd.auth.enabled=false} 之后 {@code @RequiresPermission}
 * 一次都不会被拦，既不报错也不日志。缺端口、缺凭据校验器、声明写成空数组<b>都不是</b>静默形态，
 * 它们分别由 {@code AC6}、{@code CV1}、{@code RS8} 变成启动期或调用期的可读诊断。
 *
 * <p><b>包结构</b>：{@code permission}（切面与声明）、{@code credential}（R14 接缝的启动期守卫）、
 * {@code config}（四个自有键、装配与端口守卫）。{@code audit} 包是 Task 52 的落点，本模块交付到
 * Task 48 时<b>不存在</b>。没有 {@code AuthResourceNames} 这一类：auth 只有一个端口与一个注解，
 * 不存在两条端口互相串扰的问题，加前缀是凭空造 API（对照 Task 45/46 的 limiter 侧，那里确实需要）。
 */
package org.deep361.myddd.auth;

/**
 * provider 的装配面：一个自有键、二选一判定、{@code @Conditional} 与唯一一份自动装配。
 *
 * <p><b>{@code spring.security.*} 与 {@code spring-security.*} 一个键都不翻译</b>（§0.3-5）：本包只有
 * {@code myddd.auth.security.enabled}。最容易踩到的一条去处写在这里——"登录成功了浏览器里为什么没有
 * 会话 cookie / {@code Authorization} 头"：这一家<b>没有</b>厂商缺省可关，因为把凭据写进响应根本不是
 * 本模块的事（端口 {@code login} 的 javadoc 已把返回方式交出去，一手 {@code AuthPort.java:19-23}）。
 * 那是业务 {@code SecurityFilterChain} 与厂商 {@code SecurityContextRepository} 的决定：要会话就配
 * {@code HttpSessionSecurityContextRepository}，要 Bearer 就配自己的 {@code AuthenticationSuccessHandler}。
 *
 * <p><b>不引 {@code spring-security-web} / {@code spring-security-config}，也不产过滤链</b>：R9 要求
 * provider 在批处理线程与 MQ 消费线程上一样正确，而 {@code SecurityContextHolder} 是纯 ThreadLocal
 * （一手探针 50a 第三格：子线程读到 {@code null}）——非 Web 线程的正确行为不需要任何 Web 装配。
 * 产一份 {@code SecurityFilterChain} 就是替全站决定"凭据怎么带"，与上一条同源。
 *
 * <p><b>不产 {@code AuthenticationManager}／{@code UserDetailsService}／{@code PasswordEncoder}</b>
 * （判据 {@code AC5}）：那三份合起来是"用户库长什么样、口令怎么存"，归业务或业务引的
 * {@code spring-boot-starter-security}。
 *
 * <p><b>{@code myddd.auth.provider} 的判定逻辑两家各镜像一份</b>（{@link AuthSecurityProviderSelection}
 * 与 satoken 侧的同名兄弟）：auth 这条线上壳与 provider 之间没有 compile 依赖边（D-176 那张表），
 * 壳递不出它，而 provider 之间零依赖（§0.3-11）⇒ 没有第三个合法落点。"两份镜像给出同一答案"由
 * Task 51 的等价性用例钉——那是唯一能同时看到两份的场合。
 */
package org.deep361.myddd.auth.security.config;

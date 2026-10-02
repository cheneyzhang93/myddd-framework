/**
 * provider 的装配面：一个自有键、二选一判定、{@code @Conditional}、R15 自检与唯一一份自动装配。
 *
 * <p><b>{@code sa-token.*} 那 93 个键一个都不翻译</b>（§0.3-5，一手 {@code cfgmeta.json}）：本包只有
 * {@code myddd.auth.satoken.enabled}。最容易被问的一条去处写在这里——"登录成功了为什么响应里没有
 * {@code Authorization} 头"：厂商缺省 {@code isWriteHeader=false}，token 只经 {@code Set-Cookie} 写出
 * （探针 49d 一手，{@code AB2} 钉住形状）。前后端分离要头，请配<b>你自己的</b>
 * {@code sa-token.is-write-header=true}；框架不代做这个决定，也不在任何诊断里替你改它。
 *
 * <p><b>{@code sa-token-spring-boot4-starter} 归业务引，本模块不引</b>：它是 Web 侧的装配
 * （过滤器链 + {@code SaBeanInject}），而 R9 要求 provider 在非 Web 线程上也正确。它缺席的后果由
 * {@link StpInterfacePresenceGuard} 的第三条消息说清，不留静默全 deny。
 *
 * <p><b>{@code myddd.auth.provider} 的判定逻辑两家各镜像一份</b>（{@link AuthSatokenProviderSelection}
 * 与 Task 50 侧的同名兄弟）：auth 这条线上壳与 provider 之间没有 compile 依赖边（D-176 那张表），
 * 壳递不出它，而 provider 之间零依赖（§0.3-11）⇒ 没有第三个合法落点。"两份镜像给出同一答案"由
 * Task 51 的等价性用例钉——那是唯一能同时看到两份的场合。
 */
package org.deep361.myddd.auth.satoken.config;

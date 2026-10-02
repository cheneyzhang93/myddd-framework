/**
 * {@code AuthPort} 的 Spring Security 实现。
 *
 * <p><b>这一家只有一个身份字符串</b>：{@code AuthUser.userId} 与 {@code username} 同源于
 * {@code Authentication.getName()}。厂商面是硬的——{@code UserDetails} 在 7.1.1 只有三个抽象方法
 * （{@code getAuthorities}、{@code getUsername}、{@code getPassword}，四个状态位全
 * {@code default=true}），<b>没有第二个标识</b>；{@code getName()} 在四种主体上的取值路径也逐条实测过
 * （字符串给原值、{@code UserDetails} 给 {@code getUsername()}、其它对象给
 * {@code String.valueOf(主体)}、主体为 {@code null} 给<b>空串</b>）。
 * ⇒ 要让两个字段不同，业务把 id 放进一个实现 {@code UserDetails} 的主体并让 {@code getName()} 返回它；
 * 框架<b>不猜</b>第二个名字，也不为它开新端口。
 *
 * <p><b>这是本框架两家之间第一条已登记的行为不对称，必须与 satoken 侧那份一起读</b>：那边
 * {@code loginId} 与用户名是两个东西（{@code StpUtil.login(loginId)} 按 id 建会话、用户名是我们自己
 * 写进 account session 的）。⇒ "换 provider 业务代码零改动"这条承诺在<b>方法签名</b>层成立，
 * 在<b>身份字符串</b>层不成立；跨家等价性的判据因此是"两家各自的 {@code knownUser()} 自洽 +
 * 四方法行为等价"，比"两家给同一个字符串"是错的判据。
 *
 * <p><b>本包不碰 {@code SecurityContextHolder}</b>：holder 的读、写、清全部集中在
 * {@code bridge} 那一份归一点（R9 的唯一落点），端口只是它的四个转发。包内出现第二次
 * {@code SecurityContextHolder} 调用点，就意味着"未登录"这件事有两个判据。
 */
package org.deep361.myddd.auth.security;

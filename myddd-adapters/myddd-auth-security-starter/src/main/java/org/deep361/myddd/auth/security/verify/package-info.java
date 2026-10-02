/**
 * 一次登录的生命周期：解析 {@code AuthenticationManager} → 造未认证 token → 认证 → 写 holder；
 * 失败按两支翻译。
 *
 * <p><b>为什么这一家没有启动期守卫</b>（与 satoken 那两家的启动期自检<b>故意</b>不对称）：
 * Sa-Token 的 {@code StpUtil.login(id)} 收了 id 就发会话、一个凭据都不校验，所以"没人校验"必须拦在
 * 启动期——放过去就是一条静默发出的、谁也没批的身份。Spring Security 这边没有同形通道：容器里没有
 * {@code AuthenticationManager} 时本包<b>无从调用任何认证</b>，也就写不出"半截身份"；运行时一条含两条
 * 出路的 {@code FrameworkException} 是正确答案，而给一个不可能静默失败的点加启动自检，等于给部署方
 * 一条假的"已经检查过"的信号。判据：单元级那条"没有 manager ⇒ 登录才报、读面不受影响"，与容器级那条
 * "装配不失败"。
 *
 * <p><b>为什么不消费 {@code CredentialVerifier}</b>：内核那个端口是给"框架自己没有任何凭据校验能力"的
 * 那一家准备的；{@code AuthenticationManager} 本身就是认证链（口令比对、状态位、事后擦除凭据全在里面），
 * 再套一层"业务给我们一个校验器、我们替厂商调它"就是把厂商的地盘复制一份（R14 原文的分工）。
 * ⇒ 业务接自己用户库的写法是实现 {@code AuthenticationProvider} 或给 {@code DaoAuthenticationProvider}
 * 一个 {@code UserDetailsService}。
 *
 * <p><b>两支翻译之外一律原样上抛</b>：{@code InternalAuthenticationServiceException}（用户库故障）先于
 * {@code AuthenticationException} 捕获并走 {@code SYSTEM_ERROR}；库里存了无 {@code {noop}} 前缀的原始
 * 口令时厂商抛的是 {@code IllegalArgumentException}，那是<b>配置事实</b>不是认证事实，吞进 401 就是掩盖。
 * 非 {@code AuthenticationException} 的运行时异常原样穿过 {@code ProviderManager} 是实测过的。
 *
 * <p><b>"失败不留半截身份"在本家钉的是端口对外语义，不是语句顺序</b>：{@code install} 放在最后是正确写法
 * （它保证"认证成功后身份必然在场"），但把一次未认证的请求 token 提前写进 holder 在端口面上读不出来——
 * 桥那三支判据的第二支（认证位）本来就不认它。真正的防线在 {@code bridge}，不在这里。
 */
package org.deep361.myddd.auth.security.verify;

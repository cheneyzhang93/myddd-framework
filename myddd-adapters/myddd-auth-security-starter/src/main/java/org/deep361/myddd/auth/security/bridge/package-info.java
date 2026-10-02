/**
 * R9 的唯一归一点与 {@code AuthUser} 的唯一组装点。这一家的"不抛"是<b>结构性</b>的：
 * {@code SecurityContextHolder.getContext()} 从不抛（一手读数：从未用过 holder 的线程给的是
 * {@code authentication=null} 的 {@code SecurityContextImpl}），所以本包<b>没有</b> {@code try/catch}——
 * 与 satoken 侧那个恰好 catch 两支 {@code SaTokenContextException}/{@code NotLoginException} 的桥
 * 形状不同、职责同名。写在这里是因为"下一位看到没有 catch"会以为是漏了。
 *
 * <p><b>两道过滤各有一条不可让的判据</b>：
 * <ol>
 *   <li><b>匿名不算登录</b>——{@code AnonymousAuthenticationToken.isAuthenticated()} 为 {@code true}，
 *       只看那一位会把每个访客读成"用户 {@code anonymousUser} 已登录"。判据用厂商的
 *       {@code AuthenticationTrustResolver}，不自写 {@code instanceof AnonymousAuthenticationToken}：
 *       那个接口正是厂商为"这一格由我裁决"而留的（它还有 {@code setAnonymousClass}），也不用
 *       {@code isFullyAuthenticated()}：它把 remember-me 判成 false，而记住我的身份<b>是</b>已登录身份。</li>
 *   <li><b>认证因子不是授权数据</b>——厂商在认证成功后往 token 上追加一条
 *       {@code FactorGrantedAuthority -> FACTOR_PASSWORD}，不滤掉它就等于给所有人授予了一个业务
 *       从未写过的权限码。按<b>类型</b>滤，不按那八个 {@code FACTOR_*} 字符串滤（按字符串要维护那张表，
 *       并且会漏掉新增的一种）。</li>
 * </ol>
 *
 * <p><b>{@code ROLE_} 前缀走叠加式切分</b>：{@code permissions} 收<b>全部</b>原串、{@code roles} 额外
 * 给去壳视图。独占式二选一是错的：厂商自己的 {@code hasAuthority("ROLE_admin")} 实测为 {@code true}，
 * 端口的精确匹配面必须能被业务用任何真实存在的字符串命中。<b>要层级，就把可达角色物化进 authorities</b>：
 * {@code RoleHierarchy} bean 在场时本包不跟随、也<b>不</b> fail-fast——那件事在本模块的实现面上是
 * <b>零行代码</b>。与 satoken 侧的权限源自检故意不同形：那边缺的是权限数据源本身（缺了就是"静默全 deny"
 * 这种纯故障），这边多出来的是厂商的一项<b>合法能力</b>，为它启动失败等于框架禁止业务用 Spring Security
 * 的一个特性。这一格的容器级读数是装配用例里那条三句并排（装配不失败、端口判 false、厂商判 true）。
 */
package org.deep361.myddd.auth.security.bridge;

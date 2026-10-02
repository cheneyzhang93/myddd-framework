package org.deep361.myddd.auth.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.deep361.myddd.auth.security.testing.SecurityContextHolderScope;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.testing.contract.AuthPortContractTestBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * {@link AuthPortContractTestBase} 的 Spring Security 侧子类：八条跨厂商契约在这里<b>第二次</b>真跑。
 *
 * <p><b>本文件一条 {@code @Test} 都没有</b>，八条全部由基类继承。出现任何一条 {@code skipped} 就是有人
 * 把某条 {@code AUTH-C} 改成了 {@code assumeTrue}：探针 50a 第一格已经证明整条链在<b>零 Spring 上下文、
 * 零 Servlet、零请求作用域</b>的环境里读得通 holder（未用过 holder 的线程给的是 {@code authentication=null}
 * 的空上下文而不是异常），没有任何一条可以理由性地跳过。
 *
 * <p><b>与 satoken 侧子类（{@code 49f}）的三处形状差，全部有出处</b>：
 * <ol>
 *   <li>{@code openRequestScope()}/{@code closeRequestScope()} 在这里是 holder 的<b>装空上下文</b>与
 *       {@code clearContext()}。satoken 侧的 {@code open} 还要顺手重装一次权限源，因为那边的
 *       {@code StpInterface} 挂在 {@code SaManager} 的<b>进程级静态字段</b>上、{@code close} 会把它还原成
 *       缺省的全 deny 实现；这一家的"权限源"就是 token 自身，而 token 由 {@link #authPort()} 的登录写进
 *       holder，随作用域一起消失——没有静态面要还原，故本类<b>没有</b>还原钩子，也<b>不需要</b>。
 *       用户库则是构造在实例字段上的进程内 lambda，作用域关闭不动它，跨用例不串号。</li>
 *   <li>{@code knownUser()} 的两个身份字符串<b>同一个值</b>（{@code userId == username == "50101"}），
 *       而 satoken 侧是两个（{@code 49101} 与 {@code cheney}）。这是 D-193 在契约套件里的第一次落地，
 *       基类四句字段断言对此完全无感（它只比 {@code knownUser()} 与端口读数）。⇒ Task 51 的等价性判据
 *       必须是"两家各自的 {@code knownUser()} 自洽 + 四方法行为等价"，<b>不是</b>"两家给同一个字符串"。</li>
 *   <li>{@code knownUser().permissions()} 含 {@code ROLE_finance} 本身（{@code RS5}/{@code 50a} 第 5 条的
 *       附加式映射：{@code roles} 去前缀、{@code permissions} 给全部 authority 串），satoken 侧那一份只有
 *       {@code order:read}。同一句 {@code assertEquals} 在两家各自成立，两家的期望值<b>不许</b>互相抄。</li>
 * </ol>
 *
 * <p><b>顺带被 C1 钉住的一格</b>：厂商认证成功后会往 token 上追加一条 {@code FACTOR_PASSWORD}
 * （{@code 50a} 第 4 条 / D-194），而 {@link #knownUser()} 的那一句权限全集断言里没有它。于是
 * {@code AUTH-C1} 本身就是桥里那道 {@code instanceof} 过滤的第二只杀手——{@code 50e} 的 {@code AB2} 是
 * 第一只，{@code 50i} 的 {@code SVM14}（"把过滤判断换成恒真条件、一支都不剔"）会同时撞这两处。
 *
 * <p><b>认证链是真链</b>：现场 {@code new} 的 {@code ProviderManager} + {@code DaoAuthenticationProvider}
 * + 进程内 {@code UserDetailsService} lambda，形状就是探针 50b/50c 用过的那一套（不需要
 * {@code afterPropertiesSet()}，一手：那两支探针全程直接 {@code new} 并 authenticate 成功）。口令一律写
 * {@code {noop}} 前缀形——少了前缀厂商抛的是 {@code IllegalArgumentException}（探针 50b 末段那一格），
 * 而本文件要的读数只有"认证成功"与"口令错"两种。夹具用户用 {@code authorities(...)} 一次写全、
 * <b>不用 {@code roles()}</b>（D-195：后写的那一份会整份覆盖前一份，{@code AUTH-C1} 的角色断言会当场失去判据）。
 *
 * <p><b>未知用户走的是"显式抛 {@code UsernameNotFoundException}"而不是"返回 {@code null}"</b>（一手：
 * 探针 50h 第一格——{@code loadUserByUsername} 返回 {@code null} 时厂商抛
 * {@code InternalAuthenticationServiceException}，消息 {@code UserDetailsService returned null, which is an
 * interface contract violation}，而本端口对那一支的翻译是 {@code SYSTEM_ERROR}／500，见 {@code 50e} 的
 * {@code AB4}；第二格：显式抛 {@code UsernameNotFoundException} 被缺省 {@code hideUserNotFoundExceptions}
 * 吞成 {@code BadCredentialsException} 并保住 cause）。本文件两条凭据的用户名都是 {@link #USER}，这一支
 * <b>在用例面上打不到</b>，写出来只是为了让这份库对"任何用户名"都有一个<b>语义正确</b>的答案——
 * 而 {@code null} 那个答案会把认证失败伪装成用户库故障（登记 **D-210**：本段初稿写的正是 {@code : null}）。
 * 同一支探针的第四格顺手给出一个反直觉读数：{@code DaoAuthenticationProvider} <b>不校验</b>库里返回的身份
 * 名字与请求的名字是否一致（请求 {@code 50101}、库里返回 {@code someone-else} ⇒ 认证成功且
 * {@code getName()=someone-else}）——所以"图省事让 lambda 无条件返回同一个用户"是能编译能跑的错写法，
 * 本模块所有夹具库一律按请求用户名建 {@code User}（{@code 50e} 的 {@code Store} 同规）。
 */
class SpringSecurityAuthPortContractTest extends AuthPortContractTestBase {

    /** 口令只在这一个常量里出现一次，两条凭据与用户库共用它。 */
    private static final String PASSWORD = "contract-secret";
    /** 本类独占的用户名段（{@code RS} 用 {@code 50003}–{@code 50010}，{@code AB} 用 alice/bob 等人名字）。 */
    private static final String USER = "50101";
    /** 两个身份字符串同源（D-193）、permissions 为附加式全集（D-195）的那一份期望。 */
    private static final AuthUser KNOWN =
            new AuthUser(USER, USER, Set.of("finance"), Set.of("ROLE_finance", "order:read"));

    private final SecurityContextHolderScope scope = new SecurityContextHolderScope();
    private final SpringSecurityAuthPort authPort = new SpringSecurityAuthPort(managers(knownUserStore()));

    @Override
    protected AuthPort authPort() {
        return authPort;
    }

    @Override
    protected AuthCredentials validCredentials() {
        return new AuthCredentials(USER, PASSWORD);
    }

    @Override
    protected AuthCredentials brokenCredentials() {
        return new AuthCredentials(USER, "wrong-" + PASSWORD);
    }

    @Override
    protected AuthUser knownUser() {
        return KNOWN;
    }

    /**
     * "本线程有一个上下文、但没人登录"：装一个 {@code authentication=null} 的空上下文（一手：探针 50a
     * 第一格，全新线程上 holder 给的正是这个形状）。这就是请求已经穿过过滤链、尚未认证的形态。
     */
    @Override
    protected void openRequestScope() {
        scope.open(null);
    }

    @Override
    protected void closeRequestScope() {
        scope.close();
    }

    @Override
    protected Optional<AuthUser> currentUserWithoutRequestScope() {
        return CompletableFuture.supplyAsync(authPort::currentUser).join();
    }

    /**
     * 只有 {@link #USER} 这一条记录、只有两条权限的用户库；口令错由厂商自己判成
     * {@code BadCredentialsException}（探针 50h 第三格，消息是本地化的中文"用户名或密码错误"，
     * <b>不断言消息文本</b>——同一份码在不同 JVM locale 下给出不同字符串，钉它等于给 CI 埋雷）。
     */
    private static AuthenticationManager knownUserStore() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(username -> {
            if (!USER.equals(username)) {
                throw new UsernameNotFoundException(username);
            }
            return User.withUsername(username).password("{noop}" + PASSWORD)
                    .authorities("ROLE_finance", "order:read").build();
        });
        return new ProviderManager(provider);
    }

    /**
     * 只实现 {@code stream()} 的 {@link ObjectProvider} 假实现，与 {@code SpringSecurityAuthPortTest} 里那份
     * <b>各写一份</b>：判据同 {@code 49f}（两者都只覆写 {@code stream()}，因为 Spring 7 里
     * {@code ObjectProvider} 的全部方法都是 {@code default}，一手 {@code ObjectProvider.java:85-95}；
     * 抽成第三个工具类会让测试源里多一个只服务于两个用例的公共面）。
     */
    private static ObjectProvider<AuthenticationManager> managers(AuthenticationManager... source) {
        List<AuthenticationManager> fixed = List.of(source);
        return new ObjectProvider<>() {
            @Override
            public Stream<AuthenticationManager> stream() {
                return fixed.stream();
            }
        };
    }
}

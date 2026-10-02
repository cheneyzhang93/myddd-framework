package org.deep361.myddd.auth.security.testing;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 侧的"这一线程的 holder 里躺着某个 token"夹具：<b>零 Spring、零 Servlet、零网络</b>。
 *
 * <p>本类比 satoken 侧的 {@code SaTokenRequestScope}（{@code 49d}）薄得多，因为 holder 的三支柱本来就
 * 是进程内 ThreadLocal，不需要实现任何接口。五支 token 工厂一一对应 {@code RS} 组的在场形态
 * （已认证 / 匿名 / 未认证 / 主体是厂商 {@code UserDetails} / 主体是任意对象），
 * {@link #withVendorFactorAuthority} 只多一条厂商权限。<b>没有一支走真认证链</b>：真
 * {@code ProviderManager} 的形状属于 Step 5 的 {@code AuthenticationManagerLogin}，在 {@code AB} 组
 * 现场建（探针 50b/50c 的形状），夹具替它兜底只会让 {@code RS} 的失败读数分不清是桥错了还是认证链错了。
 *
 * <p><b>三条纪律，全部来自一手读数</b>（{@code 50a} 第 7 条，探针 50d 第三格是它们的证据）：
 * <ol>
 *   <li><b>绝不改动全局 strategy</b>：{@code setStrategyName("MODE_GLOBAL")} 在厂商那边<b>真的生效</b>
 *       （探针 50d：调完之后 strategy 变成 {@code GlobalSecurityContextHolderStrategy}，再调回
 *       {@code MODE_THREADLOCAL} 才恢复）。它换掉的是<b>整个 JVM</b> 的 holder 语义，一次就足以让同一
 *       构建后面的线程相关用例读到别人的身份。本类因此不提供任何改 strategy 的口子，并且 {@code RS1}
 *       会把当前 strategy 类名读出来自证一次。</li>
 *   <li><b>{@code setContextHolderStrategy(同一个实例)} 是合法调用，不抛</b>（探针 50d 同一格）——
 *       所以"没抛异常"不能当"什么都没发生"的证据，纪律 ① 靠"不提供口子"保证，而不是靠一个断言。</li>
 *   <li><b>静态 holder 没有请求作用域</b>：{@code @AfterEach} 只有一支 {@code clearContext()}，
 *       它必须<b>无条件</b>执行（探针 50a 第一格：从未装过的线程上它是空操作、不抛），
 *       所以不需要判"本用例到底装没装过"。</li>
 * </ol>
 *
 * <p>与 satoken 侧同规的一条：<b>username 字面量每条用例各用一个</b>（本模块 {@code RS} 组分配
 * {@code 50003}–{@code 50010}），只有 {@code RS4} 两次装配故意复用 {@code 50004}——那一格比的是同一个人
 * 在两种权限集下的读数，不是两个人。
 *
 * <p>⚠️ 本类的 {@link #open(Authentication)} 刻意<b>不</b>调用被测的 {@code SecurityContextBridge.install(...)}
 * 而是自己写 holder：夹具如果经由被测方法装身份，{@code install} 写坏了就读不出"装没装上"，而 {@code RS3}
 * 的自证句（{@code installed()} 非空，读的是夹具自己的字段）正是为了让 {@code RS} 组的每一次失败都落在
 * <b>读</b>那一侧。写侧的钉子放在 {@code 50e} 的 {@code AB1}（登录成功之后必须读得到身份，端到端）。
 */
public final class SecurityContextHolderScope {

    /** 本线程 holder 里刚装进去的那个 token。 */
    private Authentication installed;

    /** 把给定 token 装进本线程 holder（{@code null} 表示"装一个 authentication=null 的空上下文"）。 */
    public void open(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        this.installed = authentication;
    }

    /** 拆掉本线程 holder（幂等）。 */
    public void close() {
        SecurityContextHolder.clearContext();
        this.installed = null;
    }

    /** 刚装进去的那个 token，供用例先自证夹具在场再断言桥的行为（{@code RS3} 用）。 */
    public Authentication installed() {
        return this.installed;
    }

    /** 已认证 token：主体是字符串。 */
    public static Authentication authenticated(String name, List<String> authorities) {
        return withPrincipal(name, authorities);
    }

    /**
     * 已认证 token：主体是<b>任意</b>对象。{@code getName()} 在三种主体上的读数是 {@code RS8} 的
     * 全部判据（一手探针 50g：字符串给原值、非 {@code UserDetails} 的 POJO 给 {@code toString()}、
     * 自定义 {@code UserDetails} 给 {@code getUsername()}）。
     */
    public static Authentication withPrincipal(Object principal, List<String> authorities) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, granted(authorities));
    }

    /** 厂商匿名 token（{@code RS2}）：{@code isAuthenticated()} 为 true 的那一种。 */
    public static Authentication anonymous() {
        return new AnonymousAuthenticationToken("50-anonymous-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
    }

    /** 未认证 token（{@code RS3}）：厂商静态工厂，权限恒为空集（探针 50e 第二格同一读数）。 */
    public static Authentication unauthenticated(String name, String password) {
        return UsernamePasswordAuthenticationToken.unauthenticated(name, password);
    }

    /**
     * 主体是厂商 {@code User} 的已认证 token（{@code RS5}）：权限写全字符串、用 {@code authorities(...)}
     * 一次给齐——{@code 50a} 第 5 条（D-195）的夹具纪律，用 {@code roles()} 会被后写的
     * {@code authorities()} 整份覆盖掉。
     */
    public static Authentication withUserDetails(String username, List<String> authorities) {
        UserDetails user = User.withUsername(username).password("{noop}50-sentinel").authorities(
                authorities.toArray(new String[0])).build();
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    /**
     * 厂商事后追加 {@code FACTOR_PASSWORD} 的那种 token：<b>不</b>打真认证，用公开静态工厂
     * {@code FactorGrantedAuthority.fromFactor("PASSWORD")} 直接拼（一手：探针 50f 第一格，它的
     * {@code getAuthority()} 就是 {@code FACTOR_PASSWORD}，与探针 50a 真认证结果里那条逐字相同）。
     * 这样 {@code RS4} 的钉子只依赖被滤的<em>类型</em>，而不依赖一整条认证链恰好会追加它。
     */
    public static Authentication withVendorFactorAuthority(String name, List<String> authorities) {
        List<GrantedAuthority> mixed = new ArrayList<>(granted(authorities));
        mixed.add(FactorGrantedAuthority.fromFactor("PASSWORD"));
        return UsernamePasswordAuthenticationToken.authenticated(name, null, mixed);
    }

    private static List<GrantedAuthority> granted(List<String> authorities) {
        return authorities.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }

    /** 读一次当前 strategy 类名：{@code RS1} 用它自证"本模块的夹具没碰全局策略"（纪律 ①）。 */
    public static String strategyClassName() {
        return SecurityContextHolder.getContextHolderStrategy().getClass().getName();
    }
}

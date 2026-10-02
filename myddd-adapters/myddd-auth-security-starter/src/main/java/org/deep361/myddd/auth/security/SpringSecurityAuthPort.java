package org.deep361.myddd.auth.security;

import java.util.Optional;
import org.deep361.myddd.auth.security.bridge.SecurityContextBridge;
import org.deep361.myddd.auth.security.verify.AuthenticationManagerLogin;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;

/**
 * {@link AuthPort} 的 Spring Security 实现。四方法的取值面全部经 {@link SecurityContextBridge}，
 * 本类自己一次 {@code SecurityContextHolder} 都不碰。
 *
 * <p><b>本类不持有 {@code AuthenticationManager}，只持有它的 {@link ObjectProvider}</b>：这一家的
 * 认证链是业务给的（本模块不产，见 {@code config} 包说明），而缺席的那一种情况<b>只在真的有人登录时</b>
 * 才需要成为错误——把解析提前到构造期会让"引了本模块但没配认证链、只用 {@code currentUser()} 读网关
 * 塞进来的身份"这一格合法用法变成启动失败。解析点与它抛什么，全部在
 * {@code verify/AuthenticationManagerLogin}。
 *
 * <p><b>四条实现契约的落点</b>（端口 javadoc 的四格）：
 * <ul>
 *   <li>未登录 ⇒ {@code currentUser()} 空、{@code hasPermission} false，一律不抛——这一家是
 *       <b>结构性</b>的不抛（holder 读不到身份是一个 null 而不是异常），桥里没有 catch；</li>
 *   <li>登录失败 ⇒ {@code BizException}，但<b>两支</b>去处（{@code UNAUTHORIZED} 与 {@code SYSTEM_ERROR}），
 *       理由在 {@code verify} 包说明与 {@code AuthenticationManagerLogin} 的类注释；</li>
 *   <li>{@code permission} 空白 ⇒ 快速失败（与内核 {@code AuthUser#hasPermission} 同一条口径）；</li>
 *   <li>token 的出口：本方法<b>不返回</b> token，也不写响应头——这一家的"凭据怎么带"是业务
 *       {@code SecurityFilterChain} 与厂商 {@code SecurityContextRepository} 的事（会话 cookie、
 *       Bearer 头，或业务自己的任何形状），端口替不了。登录成功的可见效果就是
 *       "同线程下一次 {@code currentUser()} 读得到"。</li>
 * </ul>
 */
public class SpringSecurityAuthPort implements AuthPort {

    private final AuthenticationManagerLogin login;

    public SpringSecurityAuthPort(ObjectProvider<AuthenticationManager> authenticationManagers) {
        this.login = new AuthenticationManagerLogin(authenticationManagers);
    }

    @Override
    public Optional<AuthUser> currentUser() {
        return SecurityContextBridge.currentAuthentication().map(SecurityContextBridge::toAuthUser);
    }

    @Override
    public void login(AuthCredentials credentials) {
        this.login.login(credentials);
    }

    @Override
    public void logout() {
        SecurityContextBridge.logout();
    }

    /**
     * 精确匹配 holder 里那份身份的业务权限串（{@link SecurityContextBridge#authorityNames}），
     * <b>不</b>经 {@code toAuthUser}：那一格会构造 {@code AuthUser}，而内核的空白身份校验会在
     * "holder 里躺着一个 {@code getName()} 为空的畸形 token"时抛出来（主体为 {@code null} 时厂商
     * {@code getName()} 给的是<b>空串</b>而不是字面量 {@code null}），把一个"这个人有没有这条权限"
     * 的判定变成 500。判权限不需要身份字符串，就不该引入它的失败面。
     */
    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission 不能为空白");
        }
        return SecurityContextBridge.currentAuthentication()
                .map(authentication -> SecurityContextBridge.authorityNames(authentication).contains(permission))
                .orElse(false);
    }
}

package org.deep361.myddd.auth.security.verify;

import org.deep361.myddd.auth.security.bridge.SecurityContextBridge;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * 一次登录的全部生命周期：解析 {@link AuthenticationManager} → 造未认证 token → 认证 →
 * 把成功结果装进 holder；失败按<b>两支</b>翻译。
 *
 * <p><b>为什么这一家不消费 {@code CredentialVerifier}</b>：内核那个端口是给"框架自己没有任何凭据校验
 * 能力"的那一家准备的（Sa-Token 的 {@code StpUtil.login(id)} 收了 id 就发会话，必须有人先校验）；
 * Spring Security 的 {@code AuthenticationManager} 本身就是认证链（口令比对、状态位、事后清理凭据
 * 全在里面），再来一层"业务给我们一个校验器、我们替厂商调它"就是把厂商的地盘复制一份（R14 原文的分工）。
 * ⇒ 业务侧要接自己的用户库，写法是实现一个 {@code AuthenticationProvider}（或给
 * {@code DaoAuthenticationProvider} 一个 {@code UserDetailsService}），而不是实现
 * {@code CredentialVerifier}。
 *
 * <p><b>为什么本类也没有启动期守卫</b>（与 satoken 那两处的启动期自检<b>故意</b>不对称）：
 * Sa-Token 侧缺了依赖会<b>静默成功</b>（没有权限源 ⇒ 会话照发；没有校验器 ⇒ 靠守卫拦，否则
 * {@code StpUtil.login} 会发出一个谁也没批的身份），而这里缺席 {@code AuthenticationManager} 时
 * {@code authenticate()} 根本无从调用——不存在"半截身份"通道，运行时一条 {@link FrameworkException}
 * 就是正确答案。给一个不可能静默失败的点加启动自检，等于给部署方一条假的"已经检查过"的信号。
 *
 * <p><b>两支翻译，顺序不可换</b>：{@link InternalAuthenticationServiceException} 必须先于
 * {@link AuthenticationException} 捕获（它是它的子类）；换过来之后用户库故障会被读成"这个人密码错了"，
 * 运维面上是一条永远没人修的 401。（顺带一条语言面事实：这一对 catch 反过来写 javac 当场拒绝，
 * 所以"顺序不可换"这条纪律没有可注入的变异形态，只有"整支删除"那一种。）厂商消息<b>按 JVM locale
 * 本地化</b>（实测 {@code BadCredentialsException} 的消息是本地化文案）⇒ 本类不把厂商消息拼进任何文本，
 * 也不在任何断言里等值比较它。
 */
public final class AuthenticationManagerLogin {

    private final ObjectProvider<AuthenticationManager> authenticationManagers;

    /**
     * 由 {@code SpringSecurityAuthPort} 构造（跨包，故为 public）：本类不产 bean，
     * 端口是它唯一的调用方。
     */
    public AuthenticationManagerLogin(ObjectProvider<AuthenticationManager> authenticationManagers) {
        this.authenticationManagers = authenticationManagers;
    }

    /**
     * 认证并把结果装进本线程 holder。
     *
     * @param credentials 登录凭据，原样递给认证链
     * @throws BizException         凭据被拒（{@code UNAUTHORIZED}）或用户库故障（{@code SYSTEM_ERROR}），
     *                              两者都保留厂商异常作为 cause
     * @throws FrameworkException   容器里没有可用的 {@code AuthenticationManager}
     */
    public void login(AuthCredentials credentials) {
        AuthenticationManager manager = this.authenticationManagers.getIfAvailable();
        if (manager == null) {
            throw new FrameworkException("没有可用的 " + AuthenticationManager.class.getName()
                    + "：本模块不产认证链，也不回退成「没有校验的登录」。两条出路——"
                    + "① 业务侧提供一个 AuthenticationManager bean（Spring Security 的标准做法："
                    + "@EnableWebSecurity + AuthenticationProvider/DaoAuthenticationProvider）；"
                    + "② 换 provider：myddd.auth.provider=satoken。");
        }
        Authentication request = UsernamePasswordAuthenticationToken.unauthenticated(
                credentials.username(), credentials.password());
        Authentication authenticated;
        try {
            authenticated = manager.authenticate(request);
        } catch (InternalAuthenticationServiceException brokenUserStore) {
            throw BizException.causedBy(CommonErrorCode.SYSTEM_ERROR, brokenUserStore);
        } catch (AuthenticationException rejected) {
            throw BizException.causedBy(CommonErrorCode.UNAUTHORIZED, rejected);
        }
        SecurityContextBridge.install(authenticated);
    }
}

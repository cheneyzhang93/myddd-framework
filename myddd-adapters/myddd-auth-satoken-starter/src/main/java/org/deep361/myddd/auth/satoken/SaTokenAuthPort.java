package org.deep361.myddd.auth.satoken;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import org.deep361.myddd.auth.satoken.bridge.SaTokenContextBridge;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthPort;
import org.deep361.myddd.kernel.port.auth.AuthUser;
import org.deep361.myddd.kernel.port.auth.CredentialVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.Set;

/**
 * {@link AuthPort} 的 Sa-Token 实现。
 *
 * <p><b>四个字段各有各的来源</b>（一手依据全部在 {@code 49a} Step 0 第 4 条，逐格给过探针读数）：
 * <ul>
 *   <li>{@code userId} ⇐ {@link SaTokenContextBridge#currentLoginId()}（厂商会话里的权威标识，归一成 {@code String}）；</li>
 *   <li>{@code roles}/{@code permissions} ⇐ 业务自己声明的 {@code StpInterface}，经
 *       {@link SaTokenContextBridge#roles(Object)}/{@link SaTokenContextBridge#permissions(Object)} 读取；</li>
 *   <li>{@code username} ⇐ <b>我们自己写进 account session 的一个 {@code String}</b>，键
 *       {@link #USERNAME_KEY}。不用厂商 {@code extra} 通道：那是 {@code sa-token-jwt} 的能力，
 *       未集成时读它抛 {@code ApiDisabledException}（{@code code=11031}，探针 49a 一手）。</li>
 * </ul>
 *
 * <p><b>三条失败表达</b>：凭据校验不通过（含校验器缺席、校验器返回 {@code null}）⇒
 * {@code BizException(CommonErrorCode.UNAUTHORIZED)}；权限名非法 ⇒ {@code IllegalArgumentException}；
 * 没有请求上下文 ⇒ 不是失败而是缺省值（{@link SaTokenContextBridge} 的全部意义）。
 *
 * <p><b>{@code hasPermission} 只认精确匹配</b>，走"取列表 + {@code contains}"，<b>不走</b>
 * {@code StpUtil.hasPermission}——后者是通配语义（{@code order:*} 命中 {@code order:read}，探针 49c 一手），
 * 而 {@code AuthUser.hasPermission} 的 javadoc 明文"精确匹配，不做通配"。同一个端口里并存两种答案是
 * {@code 49a} 第 2 条否掉的自相矛盾，钉子是 {@code AB7}。
 *
 * <p><b>不持有 {@code StpInterface} 引用</b>：我们不是它的注入者（{@code SaBeanInject} 才是，
 * 见 {@code 49a} 第 1 条），所以权限数据只能经 {@code SaManager} 的静态面取。
 *
 * <p><b>token 的出口</b>：登录成功后 token 只经响应的 {@code Set-Cookie} 写出（厂商缺省
 * {@code isWriteHeader=false}，探针 49d 一手）。前后端分离要 {@code Authorization} 头的业务请配自己的
 * {@code sa-token.is-write-header=true}——本端口不返回 token、不代做那个决定（{@code AuthPort.login} 的
 * javadoc 已把返回方式交出去），{@code config} 包说明里留了这条去处。
 */
public class SaTokenAuthPort implements AuthPort {

    /** account session 里存用户名的键（值是 {@code String}；R11 的 {@code Serializable} 属 Task 52）。 */
    public static final String USERNAME_KEY = "myddd.auth.satoken.username";

    private static final Logger log = LoggerFactory.getLogger(SaTokenAuthPort.class);

    private final ObjectProvider<CredentialVerifier> credentialVerifiers;

    public SaTokenAuthPort(ObjectProvider<CredentialVerifier> credentialVerifiers) {
        this.credentialVerifiers = credentialVerifiers;
    }

    @Override
    public Optional<AuthUser> currentUser() {
        return SaTokenContextBridge.currentLoginId().map(SaTokenAuthPort::readUser);
    }

    /**
     * 校验凭据 → 发会话 → 写用户名，<b>顺序不许颠倒</b>（{@code 49a} 第 5 条第 ③ 点：先写会话再校验会在
     * 失败路径上留下"半截身份"，那正是 {@code AUTH-C2} 第三句断言的对象）。
     */
    @Override
    public void login(AuthCredentials credentials) {
        CredentialVerifier verifier = credentialVerifiers.getIfAvailable();
        if (verifier == null) {
            log.warn("没有可用的 {}：登录必然失败（业务侧需要提供一个 CredentialVerifier bean）", CredentialVerifier.class);
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        AuthUser verified = verifier.verify(credentials);
        if (verified == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        StpUtil.login(verified.userId());
        StpUtil.getSessionByLoginId(verified.userId()).set(USERNAME_KEY, verified.username());
    }

    @Override
    public void logout() {
        SaTokenContextBridge.runQuietly(StpUtil::logout);
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("permission 不能为空白");
        }
        return SaTokenContextBridge.currentLoginId()
                .map(loginId -> SaTokenContextBridge.permissions(loginId).contains(permission))
                .orElse(false);
    }

    /**
     * 按 loginId 组装身份。account session 取"按人一份"而不是"按设备一份"（两个 session 的 id 形状一手见
     * 探针 49d：{@code satoken:login:session:<loginId>} 与 {@code satoken:login:token-session:<uuid>}）；
     * 读不到 {@link #USERNAME_KEY} 时兜底成 loginId 本身——绕过本端口的原生登录就是这一格（{@code AB6}）。
     */
    private static AuthUser readUser(String loginId) {
        SaSession account = StpUtil.getSessionByLoginId(loginId);
        Object stored = account.get(USERNAME_KEY);
        String username = stored == null ? loginId : String.valueOf(stored);
        return new AuthUser(loginId, username,
                Set.copyOf(SaTokenContextBridge.roles(loginId)),
                Set.copyOf(SaTokenContextBridge.permissions(loginId)));
    }
}

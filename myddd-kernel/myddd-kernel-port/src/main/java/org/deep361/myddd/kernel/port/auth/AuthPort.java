package org.deep361.myddd.kernel.port.auth;

import java.util.Optional;

/**
 * 鉴权端口：只抽象三件事——当前用户、登录登出、权限校验，由适配器实现
 * （Plan 4：satoken / security）。
 *
 * <p><b>刻意不做的</b>：不重实现鉴权算法、不管理 token 格式、不定义会话存储——这些由各适配器沿用各自官方
 * 推荐配置（Sa-Token 的 session/redis、Spring Security 的 SecurityContext），本端口只做统一抽象，
 * 以保证"换适配器 = 换依赖 + 改配置，业务代码零改动"。
 *
 * <p><b>实现契约</b>：未登录时 {@link #currentUser()} 返回 {@link Optional#empty()}、{@link #hasPermission(String)}
 * 返回 {@code false}（不抛异常）；登录失败抛 {@code BizException(CommonErrorCode.UNAUTHORIZED)}。
 */
public interface AuthPort {

    /** 当前登录用户；未登录返回 {@link Optional#empty()}。 */
    Optional<AuthUser> currentUser();

    /**
     * 登录。
     *
     * <p>token 的返回方式由适配器与 Web 层约定（如 Sa-Token 写入会话/响应头），本端口不做承诺。
     *
     * @param credentials 登录凭据
     */
    void login(AuthCredentials credentials);

    /** 登出当前用户；未登录时为空操作。 */
    void logout();

    /**
     * 当前用户是否具备指定权限；未登录返回 {@code false}。
     *
     * <p>{@code permission} 不可为空白（违反由实现快速失败）。
     */
    boolean hasPermission(String permission);
}

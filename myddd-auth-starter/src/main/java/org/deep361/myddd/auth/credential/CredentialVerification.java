package org.deep361.myddd.auth.credential;

import java.util.Objects;
import java.util.function.Predicate;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.auth.AuthCredentials;
import org.deep361.myddd.kernel.port.auth.AuthUser;

/**
 * 一次凭据校验的执行与异常归一（R9 在壳侧的那一半）：把 {@link CredentialVerifier} 的三种出口——
 * 正常返回、已经是框架身份的失败、厂商自己的失败——收敛成框架错误模型里的那一种。
 *
 * <p><b>为什么归一发生在壳而不是各家 provider 里</b>：本类是 provider 实现 {@code AuthPort.login} 时
 * 唯一的调用点（provider 依赖本壳，见 {@link CredentialVerifier} 的"为什么接缝在本壳"那段），
 * 两家各写一份 try/catch 会得到两套归一规则：一家把"用户表查不到"翻译成 401、另一家把它留给 500，
 * 而这两家在业务侧是同一个接口、同一个注解、同一份配置。归一规则只许有一份。
 *
 * <p><b>判厂商异常用的是业务递进来的谓词，不是 {@code instanceof}</b>：本壳的依赖面里零厂商坐标
 * （Sa-Token 与 Spring Security 的类名出现在这里就等于把"换 provider 不改业务"判死），
 * 所以"哪一族异常算凭据不对"这一事实由认识自己厂商的那一家交给框架：
 * Sa-Token 侧是 {@code NotLoginException.class::isInstance}、Spring Security 侧是
 * {@code AuthenticationException.class::isInstance}。
 *
 * <p><b>只归一谓词命中的那一支</b>，其余原样上抛。判据来自同一个方向的两条一手事实：
 * ① Sa-Token 的 {@code ApiDisabledException}（没引 {@code sa-token-jwt} 却调 {@code getExtra}）与
 * 框架配置错误同族，把它归一成"未登录"等于把一条会被立刻发现的错误压成一条永远不会被发现的行为异常；
 * ② Spring Security 的 {@code ProviderManager} 不吞 {@code RuntimeException}——用户表连不上抛的是
 * {@code DataAccess} 类异常而不是 {@code AuthenticationException}，归一成 401 会让运维以为是密码错了。
 * <b>"凭据不对"与"这件事办不了"必须始终是两种可观察结果。</b>
 *
 * <p><b>不做的两件事</b>：① 不判"两个 {@link CredentialVerifier} bean 怎么消歧"——那是容器的事，
 * 由 provider 的注入点与 {@code @Primary} 解决；② 不缓存结果、不比时间、不做重试。凭据校验的正确性
 * 归业务的数据源，本类只管一条调用的出口形状。
 */
public final class CredentialVerification {

    private CredentialVerification() {
    }

    /**
     * 执行一次校验并把出口收敛到框架错误模型。
     *
     * <p>四条出口，逐一对应 {@code CVN} 组的一条用例：① 正常返回 ⇒ 原样给出实现给的<b>同一个实例</b>
     * （不复制：复制会把凭据多带一份进堆，而 {@link AuthCredentials} 的 {@code toString()} 刻意不打密码
     * 这件事的保护范围只有原样传递才完整）；② 实现抛的是 {@link BizException} ⇒ 原样重抛，
     * 因为那已经是契约要求的身份（换成 401 会把实现特意给的别的错误码洗掉）；
     * ③ 抛的是 {@code vendorCredentialFailure} 命中的厂商异常 ⇒ 换成
     * {@link CommonErrorCode#UNAUTHORIZED} 的 {@link BizException}，<b>厂商异常作为 cause 保住</b>
     * （对外只有 401，排查时日志里仍看得见厂商原话）；④ 返回 {@code null} ⇒ 同样归一为
     * {@link CommonErrorCode#UNAUTHORIZED}（无 cause），因为"没人校验通过"与"凭据不对"对前端是同一句话。
     *
     * @param verifier                业务的凭据校验实现，不可为 {@code null}（缺席时由
     *                                {@link CredentialVerifierGuard} 在启动期拦下，到这里必定非空）
     * @param credentials             登录凭据，不可为 {@code null}（其紧凑构造器已拒绝空白字段）
     * @param vendorCredentialFailure "哪一类异常算凭据不对"的判据，由 provider 给出，不可为 {@code null}；
     *                                只在实现抛出异常时被调用一次，正常返回路径上一个字节都不执行
     * @return 校验通过的用户身份，非 {@code null}
     * @throws NullPointerException     三个参数中有 {@code null}
     * @throws BizException             凭据不通过（含被本类归一后的厂商异常）
     * @throws RuntimeException         未被 {@code vendorCredentialFailure} 承认的任何运行时异常，原样上抛
     */
    public static AuthUser verify(CredentialVerifier verifier, AuthCredentials credentials,
            Predicate<? super Throwable> vendorCredentialFailure) {
        Objects.requireNonNull(verifier,
                "verifier 不能为 null——容器里没有 CredentialVerifier 时 CredentialVerifierGuard 应当在启动期就失败");
        Objects.requireNonNull(credentials, "credentials 不能为 null");
        Objects.requireNonNull(vendorCredentialFailure,
                "vendorCredentialFailure 不能为 null——provider 必须自己说明哪一族异常算凭据不对");
        AuthUser user;
        try {
            user = verifier.verify(credentials);
        } catch (BizException frameworkShaped) {
            // 已经是框架身份：原样重抛，连实例都不换。
            throw frameworkShaped;
        } catch (RuntimeException vendorFailure) {
            if (vendorCredentialFailure.test(vendorFailure)) {
                throw BizException.causedBy(CommonErrorCode.UNAUTHORIZED, vendorFailure);
            }
            throw vendorFailure;
        }
        if (user == null) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
        return user;
    }
}

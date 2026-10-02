package org.deep361.myddd.auth.config;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * auth 壳的四个自有键（{@code myddd.auth.*}）。诊断三段式沿用 Plan 3：键名 + 实际值 + 期望形态。
 *
 * <p><b>没有 {@code myddd.auth.provider} 这一键的对应字段</b>：那一键由 provider 模块侧的
 * {@code @Conditional} 直接从 {@code Environment} 读——条件类在 bean 创建之前跑，拿不到属性 bean
 * （与 Task 45/46 的 {@code myddd.limiter.provider} 同一形状）。本类只在诊断消息里以字符串形式提到
 * 那个键名，不构成读写关系。
 *
 * <p><b>也没有 {@code myddd.auth.login.enabled} 这类键</b>：登录是 {@code AuthPort.login(...)} 的方法而不是
 * 一个开关，开一个"关掉登录"的键等于造第二条"配了却不生效"的路。业务不要登录就不要调端口，
 * 框架不为它预置空实现。
 *
 * <p><b>四个键里没有"当前用户从哪来"这一格</b>：身份的唯一的读法是 {@code AuthPort.currentUser()}，
 * 由 provider 实现决定它去读会话、读 token 还是读 SecurityContext。框架若开一个
 * {@code myddd.auth.principal-source} 之类的键，就等于承认端口之外还有第二个身份真源，
 * 而"换 provider 不改业务"这句承诺依赖的恰恰是只有一个读法。
 */
@ConfigurationProperties("myddd.auth")
public class MydddAuthProperties {

    /** 能力总开关：关掉之后壳一个 bean 都不产（切面、校验器、两个守卫都没有）。 */
    private boolean enabled = true;

    /** 声明校验开关：{@code false} 时 {@code PermissionDeclarationValidator} 不装。 */
    private boolean validateDeclarations = true;

    private final PermissionCheck permissionCheck = new PermissionCheck();

    private final Credential credential = new Credential();

    public static class PermissionCheck {

        /** 只关 {@code PermissionCheckAspect}，不影响校验器与端口守卫。 */
        private boolean enabled = true;

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * 凭据校验接缝（R14）的自有键。接缝本体在 kernel-port
     * （{@code org.deep361.myddd.kernel.port.auth.CredentialVerifier}，落点修正见 {@code 48g} 与偏差 D-176），
     * 本类只管"壳要不要在启动期拦下没人校验凭据的装配"。
     */
    public static class Credential {

        /** {@code true}（缺省）：容器里有 {@code AuthPort} 却没有 {@code CredentialVerifier} ⇒ 启动失败。 */
        private boolean verifierRequired = true;

        public boolean isVerifierRequired() {
            return this.verifierRequired;
        }

        public void setVerifierRequired(boolean verifierRequired) {
            this.verifierRequired = verifierRequired;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isValidateDeclarations() {
        return this.validateDeclarations;
    }

    public void setValidateDeclarations(boolean validateDeclarations) {
        this.validateDeclarations = validateDeclarations;
    }

    public PermissionCheck getPermissionCheck() {
        return this.permissionCheck;
    }

    public Credential getCredential() {
        return this.credential;
    }

    /**
     * 启动期校验。总开关关掉时整段跳过——与 {@code MydddResilienceProperties#validate()}（Task 44）、
     * {@code MydddLimiterSentinelProperties#validate()}（Task 45）、
     * {@code MydddLimiterResilience4jProperties#validate()}（Task 46）同一条裁定：跨模块一致优先于
     * "关着也严格"（{@code IQ6} 钉这条一致性）。
     */
    @PostConstruct
    public void validate() {
        if (!this.enabled) {
            return;
        }
        if (!this.permissionCheck.isEnabled() && this.validateDeclarations) {
            throw new FrameworkException("myddd.auth.permission-check.enabled=false 与 "
                    + "myddd.auth.validate-declarations=true 同时成立：声明校验器扫的就是切面将要消费的那些 "
                    + "@RequiresPermission，切面关了以后校验只会拦下一条永远不会执行的声明，"
                    + "并把一个本来能正常启动的应用拦在启动阶段。要么把切面开回来，"
                    + "要么把 myddd.auth.validate-declarations 也写成 false——两个键一起关，语义才是"
                    + "“这套能力我今天不用”");
        }
    }
}

package org.deep361.myddd.auth.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code myddd.auth.security.*}：本 provider 的<b>唯一</b>自有键。
 *
 * <p><b>为什么只有一个布尔</b>：Spring Security 的调参面在厂商手里（{@code spring.security.user.*}
 * 与 {@code spring.security.oauth2.*} 等，一手归属见各自 jar 的 {@code spring-configuration-metadata.json}），
 * 本类一个字都不翻译（§0.3-5）。而这一家也没有第二种运行参数需要暴露：认证链归业务给出的
 * {@code AuthenticationManager}（本模块只消费不生产，见 {@code config} 包说明），授权规则归业务的
 * {@code SecurityFilterChain}，权限源归 {@code UserDetails} 里的 {@code GrantedAuthority}——把它们各写成一个
 * {@code myddd.auth.security.*} 键，等于替厂商再开一层调参面，而厂商那 93+ 个键已经在场了。
 *
 * <p><b>没有"要不要强制有 {@code AuthenticationManager}"这一格键</b>：这是本家与 satoken 家最容易被
 * 误当成对称的一格。Sa-Token 的 {@code StpUtil.login(id)} 在没有任何权限源时<b>照样发得出 token</b>，
 * 所以那边必须有一个启动期守卫去说"你漏配了"；这一家漏配 {@code AuthenticationManager} 时
 * {@code authenticate()} 根本无处可调，<b>不存在静默成功的通道</b>，运行时抛 {@code FrameworkException}
 * 就是正确答案（判据全文见 {@code AuthenticationManagerLogin} 类注释）。给一个不可能出错的点
 * 加开关，是让人以为这里有一条规则。
 *
 * <p><b>没有 {@code validate()}</b>：与 satoken 侧同因——布尔没有"值对但形态错"的中间态，宽松绑定把
 * {@code off/no/0} 都接成 {@code false}（{@code IQ2} 一次跑四种写法钉住），空白与越界都不存在。
 *
 * <p><b>{@code myddd.auth.provider} 不在本类</b>：那一键两家共用，由各家的 {@code @Conditional} 直接从
 * {@code Environment} 读（{@link AuthSecurityProviderSelection}），理由与 satoken 侧逐字同（不做两处真源）。
 */
@ConfigurationProperties(prefix = MydddAuthSecurityProperties.PREFIX)
public class MydddAuthSecurityProperties {

    /** 属性前缀（诊断文本与本类共用这一个真源）。 */
    static final String PREFIX = "myddd.auth.security";

    private boolean enabled = true;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

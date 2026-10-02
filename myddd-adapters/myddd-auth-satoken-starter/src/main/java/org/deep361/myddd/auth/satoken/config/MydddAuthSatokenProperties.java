package org.deep361.myddd.auth.satoken.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code myddd.auth.satoken.*}：本 provider 的<b>唯一</b>自有键。
 *
 * <p><b>为什么只有一个布尔</b>：Sa-Token 的 93 个 {@code sa-token.*} 键由厂商自己绑定（一手：
 * 该 jar 的 {@code spring-configuration-metadata.json}，§0.1 那行已划过账），本类一个字都不翻译
 * （§0.3-5）。这个模块没有第三种运行参数需要暴露：会话存储归 DAO（部署面）、身份标识归 loginId、
 * 权限数据归业务的 {@code StpInterface}——把它们各写成一个键等于替厂商再开一层调参面。
 *
 * <p><b>没有 {@code validate()}</b>：布尔没有"值对但形态错"的中间态，宽松绑定把 {@code off/no/0}
 * 都接成 {@code false}（{@code IQ2} 一次跑四种写法钉住这件事），空白与越界都不存在。留一个空方法
 * 只会让下一个读它的人以为还欠一条规则。
 *
 * <p><b>{@code myddd.auth.provider} 不在本类</b>：那一键是两家共用的，由各家的 {@code @Conditional}
 * 直接从 {@code Environment} 读（{@code AuthSatokenProviderSelection}），做成属性字段就得让壳的键
 * 长进 provider 的属性类里，那是把两个真源缝在一起。
 */
@ConfigurationProperties(prefix = MydddAuthSatokenProperties.PREFIX)
public class MydddAuthSatokenProperties {

    /** 属性前缀（诊断文本与本类共用这一个真源）。 */
    static final String PREFIX = "myddd.auth.satoken";

    private boolean enabled = true;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

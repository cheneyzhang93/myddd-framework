package org.deep361.myddd.limiter.sentinel.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * Sentinel provider 的三个自有键（1.2.1 表）。诊断沿用 Plan 3 的三段式：键名 + 实际值 + 期望形态。
 *
 * <p><b>这里没有 dashboard 的键</b>（偏差 D-151）：本模块不引 {@code sentinel-transport-*}，
 * 心跳与命令中心的 {@code InitFunc} 因此根本不在场。要接控制台由业务自己加那个 jar 并给三方启动参数
 * （{@code csp.sentinel.dashboard.server}、{@code csp.sentinel.api.port}、{@code -Dproject.name}），
 * 框架不做键翻译（§0.3-5）。
 *
 * <p><b>这里也没有 provider 二选一的键</b>：{@code myddd.limiter.provider} 由
 * {@link MydddLimiterProviderCondition} 直接从 {@code Environment} 读——条件类在 bean 创建之前跑，
 * 拿不到属性 bean（与 Task 41/42 的 Job 侧同一形状）。
 */
@ConfigurationProperties("myddd.limiter.sentinel")
public class MydddLimiterSentinelProperties {

    /** 规则来源：只在内存里由 {@code SentinelRulePublisher} 下发。 */
    public static final String SOURCE_MEMORY = "memory";
    /** 规则来源：启动时从 {@code rule-location} 指向的 JSON 文件整张表装载。 */
    public static final String SOURCE_FILE = "file";

    /** 本 provider 的总开关（选中之后仍可关掉这一家，此时该能力零端口 bean）。 */
    private boolean enabled = true;

    /** {@code memory} 或 {@code file}，缺省前者。 */
    private String ruleSource = SOURCE_MEMORY;

    /** {@code file} 时必填且必须存在；{@code memory} 时必须留空。 */
    private String ruleLocation;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRuleSource() {
        return this.ruleSource;
    }

    public void setRuleSource(String ruleSource) {
        this.ruleSource = ruleSource;
    }

    public String getRuleLocation() {
        return this.ruleLocation;
    }

    public void setRuleLocation(String ruleLocation) {
        this.ruleLocation = ruleLocation;
    }

    /**
     * 启动期校验。总开关关掉时整段跳过——与 {@code MydddResilienceProperties#validate()}（Task 44）
     * 与 {@code MydddConsistencyProperties#validate()}（Plan 3）同一条裁定，跨模块一致优先于
     * "关着也严格"（{@code IQ7} 钉这条一致性）。
     */
    @PostConstruct
    public void validate() {
        if (!this.enabled) {
            return;
        }
        String source = this.ruleSource == null ? "" : this.ruleSource.trim();
        if (!SOURCE_MEMORY.equals(source) && !SOURCE_FILE.equals(source)) {
            throw new FrameworkException("myddd.limiter.sentinel.rule-source=" + this.ruleSource
                    + " 非法：期望 memory（规则只由 SentinelRulePublisher 在运行期下发）或 file"
                    + "（启动时从 rule-location 整张表装载）——写错一个字母的取值不会被任何一侧接受，"
                    + "而规则面一旦装配就不再有第二次报错机会，只能靠这条启动期快速失败。");
        }
        String location = this.ruleLocation == null ? "" : this.ruleLocation.trim();
        if (SOURCE_MEMORY.equals(source)) {
            if (!location.isEmpty()) {
                throw new FrameworkException("myddd.limiter.sentinel.rule-location=" + this.ruleLocation
                        + " 在 rule-source=memory 下没有任何消费者：文件不会被读，规则面看起来"
                        + "「配了路径却没生效」比不配更难排查。要么把 rule-source 写成 file，"
                        + "要么删掉这一键");
            }
            return;
        }
        if (location.isEmpty()) {
            throw new FrameworkException("myddd.limiter.sentinel.rule-location 必填（rule-source=file）："
                    + "文件来源的含义就是“以那份字节为准”，缺了它启动后是一张空规则表，"
                    + "所有资源全放行——限流配置看起来在、一条都不拦");
        }
        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new FrameworkException("myddd.limiter.sentinel.rule-location=" + location
                    + " 解析后为 " + path + "，该路径不存在：file 来源不许退化成“规则没配”，"
                    + "因为退化的结果是全部放行而不是全部拒绝，业务看不见");
        }
        if (Files.isDirectory(path)) {
            throw new FrameworkException("myddd.limiter.sentinel.rule-location=" + location
                    + " 解析后为 " + path + "，那是一个目录而不是规则文件");
        }
    }

    /** 校验过了才给到的绝对路径（{@code memory} 来源给 null）。 */
    public Path resolvedRuleLocation() {
        String location = this.ruleLocation == null ? "" : this.ruleLocation.trim();
        return SOURCE_FILE.equals(this.ruleSource == null ? "" : this.ruleSource.trim()) && !location.isEmpty()
                ? Paths.get(location).toAbsolutePath().normalize() : null;
    }
}

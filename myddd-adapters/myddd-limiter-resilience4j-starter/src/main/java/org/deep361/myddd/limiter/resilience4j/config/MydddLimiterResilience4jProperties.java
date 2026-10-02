package org.deep361.myddd.limiter.resilience4j.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * Resilience4j provider 的三个自有键（1.2.1 表）。诊断沿用 Plan 3 的三段式：键名 + 实际值 + 期望形态。
 *
 * <p><b>没有 {@code myddd.limiter.resilience4j.timeout-duration} 这类键</b>：端口 javadoc 承诺非阻塞，
 * 而 r4j 侧唯一能兑现它的写法就是把 {@code timeoutDuration} 钉成 {@code ZERO}（一手实测：同一发超大请求
 * {@code timeout=5s} 阻塞 1002ms、{@code timeout=0} 阻塞 0ms）。开一个键等于允许业务把
 * "非阻塞"这条契约关掉——要阻塞请去引 {@code resilience4j-spring-boot3} 的注解切面，不是我们的端口。
 *
 * <p><b>也没有 provider 二选一的键</b>：{@code myddd.limiter.provider} 由
 * {@link MydddLimiterProviderCondition} 直接从 {@code Environment} 读——条件类在 bean 创建之前跑，
 * 拿不到属性 bean（与 Task 45 的 Sentinel 侧同一形状）。
 */
@ConfigurationProperties("myddd.limiter.resilience4j")
public class MydddLimiterResilience4jProperties {

    /** 规则来源：只在内存里由 {@code Resilience4jRulePublisher} 下发。 */
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
     * 启动期校验。总开关关掉时整段跳过——与 {@code MydddLimiterSentinelProperties#validate()}（Task 45）、
     * {@code MydddResilienceProperties#validate()}（Task 44）同一条裁定，跨模块一致优先于"关着也严格"
     * （{@code IQ7} 钉这条一致性）。
     */
    @PostConstruct
    public void validate() {
        if (!this.enabled) {
            return;
        }
        String source = this.ruleSource == null ? "" : this.ruleSource.trim();
        if (!SOURCE_MEMORY.equals(source) && !SOURCE_FILE.equals(source)) {
            throw new FrameworkException("myddd.limiter.resilience4j.rule-source=" + this.ruleSource
                    + " 非法：期望 memory（规则只由 Resilience4jRulePublisher 在运行期下发）或 file"
                    + "（启动时从 rule-location 整张表装载）——写错一个字母的取值不会被任何一侧接受，"
                    + "而规则面一旦装配就不再有第二次报错机会，只能靠这条启动期快速失败。");
        }
        String location = this.ruleLocation == null ? "" : this.ruleLocation.trim();
        if (SOURCE_MEMORY.equals(source)) {
            if (!location.isEmpty()) {
                throw new FrameworkException("myddd.limiter.resilience4j.rule-location=" + this.ruleLocation
                        + " 在 rule-source=memory 下没有任何消费者：文件不会被读，规则面看起来"
                        + "「配了路径却没生效」比不配更难排查。要么把 rule-source 写成 file，"
                        + "要么删掉这一键");
            }
            return;
        }
        if (location.isEmpty()) {
            throw new FrameworkException("myddd.limiter.resilience4j.rule-location 必填（rule-source=file）："
                    + "文件来源的含义就是“以那份字节为准”，缺了它启动后是一张空规则表，"
                    + "所有资源全放行——限流配置看起来在、一条都不拦");
        }
        Path path = Paths.get(location).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new FrameworkException("myddd.limiter.resilience4j.rule-location=" + location
                    + " 解析后为 " + path + "，该路径不存在：file 来源不许退化成“规则没配”，"
                    + "因为退化的结果是全部放行而不是全部拒绝，业务看不见");
        }
        if (Files.isDirectory(path)) {
            throw new FrameworkException("myddd.limiter.resilience4j.rule-location=" + location
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

package org.deep361.myddd.resilience.config;

import java.time.Duration;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

/**
 * 弹性壳的三个自有键（1.2.1 表）。诊断沿用 Plan 3 已交付的三段式：键名 + 实际值 + 期望形态，
 * 并且每一段都只说"为什么静默失效比报错更糟"，不重复议据。
 *
 * <p><b>这里没有 provider 的键</b>：Sentinel 与 resilience4j 各自的调参面（规则文件、
 * {@code resilience4j.circuitbreaker.*}）由 Task 45/46 各自暴露，壳一条都不翻译（§0.3-5）。
 * 本类的三件事全在"注解与端口之间的那段执行语义"上：装不装、最多忙等多久、缺省降级方法怎么叫。
 */
@ConfigurationProperties("myddd.resilience")
public class MydddResilienceProperties {

    /** 两个切面的总开关。 */
    private boolean enabled = true;

    private final RateLimit rateLimit = new RateLimit();

    private final Fallback fallback = new Fallback();

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RateLimit getRateLimit() {
        return this.rateLimit;
    }

    public Fallback getFallback() {
        return this.fallback;
    }

    /** {@code myddd.resilience.rate-limit.*}。 */
    public static class RateLimit {

        /**
         * 注解 {@code timeout} 的上界护栏（R8）：注解写更大的值一律截到此值。
         *
         * <p>缺省 {@code 5s} 的口径是"一个 Web 请求线程最多为一次限流等待多久"，与容器线程池的
         * 典型超时相比要小一个量级——护栏的作用不是让等待更准，而是让<b>一个注解</b>不能把
         * 一次请求拖成一次事故。
         */
        private Duration timeoutMaxBusyWait = Duration.ofSeconds(5);

        public Duration getTimeoutMaxBusyWait() {
            return this.timeoutMaxBusyWait;
        }

        public void setTimeoutMaxBusyWait(Duration timeoutMaxBusyWait) {
            this.timeoutMaxBusyWait = timeoutMaxBusyWait;
        }
    }

    /** {@code myddd.resilience.fallback.*}。 */
    public static class Fallback {

        /**
         * 给 {@code @CircuitBreaker(fallbackMethod=…)} 显式写出的方法名加的前缀，缺省空白。
         *
         * <p>前缀只改<b>查找名</b>（候选变成 {@code 前缀 + 首字母大写原文} 与 {@code 原文}），不改契约：
         * 注解留空仍然是内核 javadoc 的原话"不降级"，前缀不会凭空给一个留空的注解造出降级路径。
         * 两个候选名各解出一个方法 ⇒ 启动后第一次真降级就快速失败，本框架不在两者之间做静默偏好
         * （见 {@code CB7}）。
         */
        private String methodPrefix = "";

        public String getMethodPrefix() {
            return this.methodPrefix;
        }

        public void setMethodPrefix(String methodPrefix) {
            this.methodPrefix = methodPrefix;
        }
    }

    /**
     * 启动期校验。总开关关掉时整段跳过——为一个不生效的键判应用起不来，比让它稍后在切回
     * {@code true} 时报错更糟（{@code MydddConsistencyProperties#validate()} 的同一条裁定）。
     */
    @PostConstruct
    public void validate() {
        if (!this.enabled) {
            return;
        }
        Duration ceiling = this.rateLimit.getTimeoutMaxBusyWait();
        if (ceiling == null || ceiling.isZero() || ceiling.isNegative()) {
            throw new FrameworkException("myddd.resilience.rate-limit.timeout-max-busy-wait=" + ceiling
                    + " 非法：它是注解 timeout 的上界护栏，零或负数会让每一次"
                    + " @RateLimit(timeout>0) 的等待预算都缩成零，于是"
                    + "「写了等待」与「没写等待」在运行期完全同形——注解看着还在，行为已经不做区分。"
                    + "请给一个正的时长（如 5s）");
        }
        if (ceiling.toNanos() % 1_000_000L != 0L) {
            throw new FrameworkException("myddd.resilience.rate-limit.timeout-max-busy-wait=" + ceiling
                    + " 带亚毫秒精度：限流的等待节拍以毫秒为单位（下限 1ms），"
                    + "亚毫秒的余量会在换算时静默抹掉，而这条键的作用恰恰是给等待一个说得清的上界。"
                    + "请写成整毫秒，如 1500ms 或 1.5s");
        }
        String prefix = this.fallback.getMethodPrefix();
        if (prefix == null) {
            throw new FrameworkException("myddd.resilience.fallback.method-prefix 不能为 null："
                    + "空白串是合法的（表示不启用命名约定），null 会让拼接降级方法名时抛出"
                    + "没有任何业务含义的空指针。请写 myddd.resilience.fallback.method-prefix=");
        }
        if (prefix.isBlank()) {
            return;
        }
        if (!prefix.equals(prefix.strip()) || prefix.chars().anyMatch(Character::isWhitespace)) {
            throw new FrameworkException("myddd.resilience.fallback.method-prefix=" + prefix
                    + " 含空白：它会与方法名拼成一个 Java 标识符，空白会让拼出来的名字"
                    + "永远匹配不到任何方法，于是所有留空的 @CircuitBreaker 都变成静默不降级。"
                    + "请给不含空白的标识符片段（如 fallback），或留空表示不启用约定");
        }
        if (!Character.isJavaIdentifierStart(prefix.charAt(0))) {
            throw new FrameworkException("myddd.resilience.fallback.method-prefix=" + prefix
                    + " 不能开头于一个合法的 Java 标识符首字符：拼出来的降级方法名同理不合法，"
                    + "而查找只能得到一个「找不到方法」的报错，报不上这条键的真实错处。"
                    + "请给如 fallback 这样的片段，或留空表示不启用约定");
        }
    }
}

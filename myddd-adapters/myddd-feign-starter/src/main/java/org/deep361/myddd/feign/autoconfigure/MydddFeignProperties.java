package org.deep361.myddd.feign.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code myddd.feign.*} 配置：出站调用适配层的唯一真源。
 *
 * <p><b>本类刻意没有 {@code @PostConstruct} 校验</b>（与 {@code MydddWebProperties} 的差异）：这里三个键全是
 * boolean，而 boolean 写错值（{@code myddd.feign.enabled=ture}）会在 Spring 的绑定阶段就失败，报错信息里
 * 自带键名与实际值——框架再加一层校验只会重复三方已经说清楚的话。{@code myddd.web.*} 需要校验的是
 * locale 标签与"开关与标题的组合"，那是真有语义风险的地方，两边不可比。
 *
 * <p>嵌套对象是 {@code final} 字段 + getter（Spring 通过 getter 绑定嵌套属性），业务侧只能改值、不能替换结构。
 */
@ConfigurationProperties(prefix = "myddd.feign")
public class MydddFeignProperties {

    /** 出站适配层总开关；关闭后 traceId 透传拦截器与错误解码器都不装配。 */
    private boolean enabled = true;

    private final Trace trace = new Trace();
    private final ErrorDecoderSettings errorDecoder = new ErrorDecoderSettings();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trace getTrace() {
        return trace;
    }

    /** 绑定键是 {@code myddd.feign.error-decoder.enabled}（relaxed binding 把 {@code errorDecoder} 写成中划线）。 */
    public ErrorDecoderSettings getErrorDecoder() {
        return errorDecoder;
    }

    /** {@code myddd.feign.trace.*}：出站链路信头透传。 */
    public static class Trace {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** {@code myddd.feign.error-decoder.*}：远程统一错误体还原。 */
    public static class ErrorDecoderSettings {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

package org.deep361.myddd.web.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code myddd.web.*} 配置：Web 适配层的唯一真源。
 *
 * <p>三方键（{@code spring.mvc.*}、{@code spring.jackson.*}、{@code spring.messages.*}、
 * {@code springdoc.*}）一律不重复定义、不改名、不重钉；本类只放框架自己的开关与文案。
 *
 * <p>嵌套对象是 {@code final} 字段 + getter（Spring 通过 getter 绑定嵌套属性），业务侧只能改值、
 * 不能替换结构。
 */
@ConfigurationProperties(prefix = "myddd.web")
public class MydddWebProperties {

    /** Web 适配层总开关；关闭后 traceId 过滤器、JSON 约定、全局异常处理、i18n 与 OpenAPI 全部不装配。 */
    private boolean enabled = true;

    private final Trace trace = new Trace();
    private final Json json = new Json();
    private final I18n i18n = new I18n();
    private final Openapi openapi = new Openapi();

    /**
     * 启动期校验：配置写错的后果必须在启动日志里出现，而不是等第一个请求。
     *
     * @throws FrameworkException {@code default-locale} 无法解析，或 OpenAPI 启用却没有标题
     */
    @PostConstruct
    public void validate() {
        if (i18n.enabled) {
            try {
                i18n.defaultLocale();
            } catch (IllegalArgumentException ex) {
                throw new FrameworkException("myddd.web.i18n.default-locale=" + i18n.defaultLocale
                        + " 不是合法的语言标签（形如 zh-CN / en-US）：" + ex.getMessage(), ex);
            }
        }
        if (openapi.enabled && openapi.title.isBlank()) {
            throw new FrameworkException("myddd.web.openapi.enabled=true 时 myddd.web.openapi.title 不能为空白；"
                    + "请给出文档标题，或关闭 myddd.web.openapi.enabled");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Trace getTrace() {
        return trace;
    }

    public Json getJson() {
        return json;
    }

    public I18n getI18n() {
        return i18n;
    }

    public Openapi getOpenapi() {
        return openapi;
    }

    /** traceId 链路边界。 */
    public static class Trace {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 全局 JSON 约定。 */
    public static class Json {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** 错误文案多语言渲染。 */
    public static class I18n {

        /** 合法语言标签的形状：字母数字段，段间用 {@code -} 或 {@code _}，最多五段。 */
        private static final Pattern LOCALE_TAG = Pattern.compile("[A-Za-z0-9]{2,8}([_-][A-Za-z0-9]{2,8}){0,4}");

        private boolean enabled = true;

        /** 请求语言无法匹配时的兜底语言（框架内置文案语言）。 */
        private String defaultLocale = "zh-CN";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDefaultLocale() {
            return defaultLocale;
        }

        public void setDefaultLocale(String defaultLocale) {
            this.defaultLocale = defaultLocale;
        }

        /**
         * 解析后的兜底语言。
         *
         * <p>先过形状关再解析：{@code StringUtils.parseLocale} 对任意字符串都切得开（它只按下划线分段、
         * 不做合法性校验），"不是语言标签"这种值会被解析成一个语言段同样叫"不是语言标签"的 Locale，
         * 于是永远匹配不上任何 bundle，而用户看到的只是"文案没生效"。BCP 49 的标签只允许字母数字与
         * {@code -}／{@code _} 分隔，这里按该字符集拒绝，把问题在启动期就说出来。
         *
         * @throws IllegalArgumentException 标签为空、含非法字符或段数超限
         */
        public Locale defaultLocale() {
            if (defaultLocale == null || !LOCALE_TAG.matcher(defaultLocale).matches()) {
                throw new IllegalArgumentException("只能由字母与数字段组成（形如 zh-CN / zh_CN，段间以 - 或 _ 分隔）");
            }
            // 形态白名单已经保证"至少一个非空字母数字段"，parseLocale 的第一段就是语言段，因此不再复查解析结果：
            // 那道复查永远为假（JaCoCo 实测 mi=5），留着等于往交付里塞一段演不到的分支。
            return StringUtils.parseLocale(defaultLocale);
        }
    }

    /** OpenAPI 文档元信息（只在 springdoc 在 classpath 上时被消费）。 */
    public static class Openapi {

        private boolean enabled = true;
        private String title = "myddd 应用";
        private String description = "";
        private String version = "1.0.0";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }
}

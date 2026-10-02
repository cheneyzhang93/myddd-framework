package org.deep361.myddd.web.i18n;

import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;
import java.util.MissingResourceException;

/**
 * 错误文案渲染器：把 {@link ErrorCode#messageKey()} 沿四级链解析成对外文案。
 *
 * <p>四级链＝业务消息源（Spring 约定的 {@code messageSource}）→ 框架内置 bundle @ 请求语言 →
 * 框架内置 bundle @ 兜底语言（{@code myddd.web.i18n.default-locale}）→ {@link ErrorCode#defaultMessage()}。
 * 前三级全 Miss 时给出的第 4 级文案是错误码自带的，因此"没配 i18n 的业务"依然有确定、可读的中文输出，
 * 而 {@code i18n.enabled=false} 直接跳到第 4 级——这是业务想完全自己接管文案时的开关，不是可选装饰。
 *
 * <p>本类是 public 且 {@link #render(ErrorCode, Object[])} 非 final：业务注册一个自己的
 * {@code ErrorMessageRenderer} bean 就能整条换掉话术（全局异常处理器按类型注入，拿到的就是它）。
 *
 * <p>请求语言取自 {@link LocaleContextHolder}，与 traceId 一样属于"每次调用的上下文"，
 * 因此本类无状态可变字段、可作单例被并发复用。
 */
public class ErrorMessageRenderer {

    /** 内置 bundle 基础名；同包的 Step 1 用例按它直接取 {@code ResourceBundle} 逐键检查覆盖面。 */
    static final String BUNDLE_BASENAME = "i18n/myddd-web-messages";

    private static final Logger log = LoggerFactory.getLogger(ErrorMessageRenderer.class);

    private final MessageSource businessMessages;
    private final boolean i18nEnabled;
    private final Locale fallbackLocale;
    private final MessageSource frameworkMessages;

    /**
     * @param businessMessages 业务消息源，可为 {@code null}（自动配置传进来的是 {@code ApplicationContext} 自身，
     *                         它必然非 null 且按 Spring 约定委派给名为 {@code messageSource} 的 bean）
     * @param i18nEnabled      {@code myddd.web.i18n.enabled}，{@code false} 时只渲染错误码自带文案
     * @param fallbackLocale   {@code myddd.web.i18n.default-locale} 解析结果，已在启动期校验过合法性
     */
    public ErrorMessageRenderer(MessageSource businessMessages, boolean i18nEnabled, Locale fallbackLocale) {
        this.businessMessages = businessMessages;
        this.i18nEnabled = i18nEnabled;
        this.fallbackLocale = fallbackLocale;
        this.frameworkMessages = frameworkBundle();
    }

    /**
     * 解析对外文案，任何一级都不得让异常逃出本方法（这是全局异常处理器的下游，再抛就等于把 500 变成没有响应体的 500）。
     *
     * @param errorCode   错误码，其 {@code messageKey/defaultMessage} 构成解析输入
     * @param messageArgs 占位符参数，{@code null} 或空数组都表示"不填参数"
     * @return 命中的那一级文案；四级全 Miss 时为 {@code errorCode.formatDefaultMessage(messageArgs)}
     */
    public String render(ErrorCode errorCode, Object[] messageArgs) {
        if (!i18nEnabled) {
            return errorCode.formatDefaultMessage(messageArgs);
        }
        // 空数组归一为 null：MessageFormat 与内核 {} 渲染器对"无参"的处理不同，归一后两级的输出都是"保留占位符原样"
        Object[] args = (messageArgs == null || messageArgs.length == 0) ? null : messageArgs;
        String message = resolve(errorCode.messageKey(), args);
        return message != null ? message : errorCode.formatDefaultMessage(messageArgs);
    }

    /**
     * 第 2、3 级的走法：先按请求语言查（每一级内部都是业务优先于框架内置），查不到再按 {@code default-locale} 查一次。
     *
     * <p>单列成方法是为了让 Task 11 新增的 {@code render(MessageSourceResolvable)} 重载复用同一条走法：
     * 两处各写一遍的话，其中一处被改动时另一处不会跟着变，四级链就静默分叉了。
     */
    /**
     * 渲染参数校验错误：{@link MessageSourceResolvable} 是 Spring 校验体系（{@code FieldError} /
     * Bean Validation 桥接结果）的公共载体，它的 {@code codes} 就是"最具体 → 最通用"的候选键序列。
     *
     * <p>与 {@link #render(ErrorCode, Object[])} 共用第 2、3 级走法，区别只在第 4 级：校验错误的兜底文案是
     * Bean Validation 自己插值出来的 {@code defaultMessage}，框架不去替换它（要点 6）。
     *
     * @param resolvable 校验错误，{@code codes} 可能为 {@code null}（实测 {@code FieldError} 三参构造器就是 null）
     * @return 命中的文案；四级全 Miss 时为 {@code defaultMessage}，它为 null 时为空串——本方法任何路径都不返回 null
     */
    public String render(MessageSourceResolvable resolvable) {
        if (i18nEnabled) {
            String[] codes = resolvable.getCodes();
            if (codes != null) {
                for (String code : codes) {
                    String message = resolve(code, resolvable.getArguments());
                    if (message != null) {
                        return message;
                    }
                }
            }
        }
        String defaultMessage = resolvable.getDefaultMessage();
        return defaultMessage != null ? defaultMessage : "";
    }

    private String resolve(String messageKey, Object[] args) {
        Locale requestLocale = LocaleContextHolder.getLocale();
        String message = lookup(messageKey, args, requestLocale);
        if (message == null && !requestLocale.equals(fallbackLocale)) {
            message = lookup(messageKey, args, fallbackLocale);
        }
        return message;
    }

    /** 同一个语言下先业务、后框架内置：这两级才是"业务可以覆盖框架话术"的入口。 */
    private String lookup(String messageKey, Object[] args, Locale locale) {
        String business = query(businessMessages, messageKey, args, locale);
        return business != null ? business : query(frameworkMessages, messageKey, args, locale);
    }

    /**
     * 只捕 {@link NoSuchMessageException} 与 {@link MissingResourceException}：它们是"这一级没有答案"的正规信号。
     * 内置 bundle 里的 MessageFormat 特殊字符（单引号、花括号）由 Step 1 的逐键用例与"英文文案不用缩写"约定禁绝；
     * 业务 bundle 自己把 pattern 写坏则应当暴露出来，静默降级只会让它更难发现。
     */
    private static String query(MessageSource source, String messageKey, Object[] args, Locale locale) {
        if (source == null) {
            return null;
        }
        try {
            return source.getMessage(messageKey, args, null, locale);
        } catch (NoSuchMessageException | MissingResourceException ex) {
            log.trace("消息键 {} 在 {} @ {} 无解，交给下一级：{}", messageKey,
                    source.getClass().getSimpleName(), locale.toLanguageTag(), ex.getMessage());
            return null;
        }
    }

    private static MessageSource frameworkBundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(BUNDLE_BASENAME);
        // properties 以 UTF-8 存：Spring 侧显式声明编码，不依赖容器默认字符集（JDK 的 ResourceBundle 自 9 起本身即 UTF-8）
        source.setDefaultEncoding("UTF-8");
        // 关键：不跟随 JVM 默认语言。否则"zh_CN 机器"与"en_US 机器"上线行为不同，且第 3 级兜底永远测不出来。
        source.setFallbackToSystemLocale(false);
        source.setBeanClassLoader(ErrorMessageRenderer.class.getClassLoader());
        return source;
    }
}

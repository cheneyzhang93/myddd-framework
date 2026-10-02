package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.web.error.GlobalExceptionHandler;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 错误文案渲染与全局异常处理的装配。
 *
 * <p>只挂总开关 {@code myddd.web.enabled}，不设 {@code myddd.web.error.enabled}：异常出口不是一个可摘除的附加
 * 能力，摘掉它的后果是"异常以 Spring 默认形状泄漏到线上"，这正是本脚手架要消灭的东西。文案能力另有子开关
 * {@code myddd.web.i18n.enabled}（关掉后仍然统一出口，只是不再做多语言解析）。
 *
 * <p>业务消息源用 {@link ApplicationContext} 承接而不是注入 {@code MessageSource} bean：
 * {@code AbstractApplicationContext} 本身实现 {@code MessageSource} 并按 Spring 约定委派给名为 {@code messageSource}
 * 的 bean（没有时它自己注册一个 {@code DelegatingMessageSource} 占位）。反过来，直接按类型注入 {@code MessageSource}
 * 会在"业务也注册了 messageSource"时撞上 {@code NoUniqueBeanDefinitionException}——这是实测出来的坑，不是假想。
 */
@AutoConfiguration(after = MydddWebAutoConfiguration.class)
@ConditionalOnProperty(prefix = "myddd.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MydddWebErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ErrorMessageRenderer mydddErrorMessageRenderer(ApplicationContext applicationContext,
                                                              MydddWebProperties properties) {
        return new ErrorMessageRenderer(applicationContext,
                properties.getI18n().isEnabled(), properties.getI18n().defaultLocale());
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler mydddGlobalExceptionHandler(ErrorMessageRenderer messageRenderer) {
        return new GlobalExceptionHandler(messageRenderer);
    }
}

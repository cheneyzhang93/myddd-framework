package org.deep361.myddd.web.autoconfigure;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.web.error.GlobalExceptionHandler;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误文案渲染与全局异常处理的装配语义：谁被装配、开关关掉后少装配什么、业务想替换时能不能替换。
 *
 * <p>这里不重复 Step 1 的四级链用例，只验"接进真实 Spring 上下文之后链子仍然接对"——
 * 尤其是第 1 级（业务消息源）走的是 {@code ApplicationContext} 自身的 {@code MessageSource} 委派，
 * 而不是我们自己去猜哪个 bean 是消息源（设计要点 5）。
 */
class MydddWebErrorAutoConfigurationTest {

    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");
    private static final Locale EN_US = Locale.forLanguageTag("en-US");
    private static final Locale FR_FR = Locale.forLanguageTag("fr-FR");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MydddWebAutoConfiguration.class, MydddWebErrorAutoConfiguration.class));

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void should_assemble_renderer_and_handler_by_default() {
        this.runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(ErrorMessageRenderer.class)
                .hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void should_render_framework_bundle_when_no_business_message_source() {
        LocaleContextHolder.setLocale(EN_US);

        // 上下文里必然存在名为 messageSource 的 bean（缺业务消息源时 Spring 自己注册 DelegatingMessageSource 占位），
        // 所以这里不能断言"没有 messageSource"，只能断言第 1 级 Miss 之后落到第 2 级：英文答案只可能来自内置 bundle。
        this.runner.run(context -> assertThat(context.getBean(ErrorMessageRenderer.class)
                .render(CommonErrorCode.ARGUMENT_INVALID, new Object[] {"age"}))
                .isEqualTo("Parameter age is invalid"));
    }

    @Test
    void should_prefer_business_message_source_registered_by_convention() {
        LocaleContextHolder.setLocale(ZH_CN);
        this.runner.withBean("messageSource", MessageSource.class, () -> {
            StaticMessageSource business = new StaticMessageSource();
            business.addMessage(CommonErrorCode.ARGUMENT_INVALID.messageKey(), ZH_CN, "自定义：{0} 不行");
            return business;
        }).run(context -> assertThat(context.getBean(ErrorMessageRenderer.class)
                .render(CommonErrorCode.ARGUMENT_INVALID, new Object[] {"age"}))
                .isEqualTo("自定义：age 不行"));
    }

    @Test
    void should_render_only_error_code_fallback_when_i18n_switch_off() {
        LocaleContextHolder.setLocale(EN_US);

        // 请求语言为英文时仍然得到内核中文文案：这才证明开关真的把第 2、3 级都跳过了
        this.runner.withPropertyValues("myddd.web.i18n.enabled=false")
                .run(context -> assertThat(context.getBean(ErrorMessageRenderer.class)
                        .render(CommonErrorCode.ARGUMENT_INVALID, new Object[] {"age"}))
                        .isEqualTo("参数 age 不合法"));
    }

    @Test
    void should_take_fallback_locale_from_default_locale_property() {
        LocaleContextHolder.setLocale(FR_FR);

        this.runner.withPropertyValues("myddd.web.i18n.default-locale=en-US")
                .run(context -> assertThat(context.getBean(ErrorMessageRenderer.class)
                        .render(CommonErrorCode.CONFLICT, null))
                        .isEqualTo("Data state conflict, please refresh and retry"));
    }

    @Test
    void should_skip_both_when_master_switch_off_without_error_sub_switch() {
        this.runner.withPropertyValues("myddd.web.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ErrorMessageRenderer.class)
                        .doesNotHaveBean(GlobalExceptionHandler.class));

        // 错误处理不设自己的子开关（文案开关已在 i18n 下）：属性类里不该出现 getErrorXxx 这类访问器
        assertThat(MydddWebProperties.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("getError"));
    }

    @Test
    void should_back_off_when_application_provides_its_own_handler() {
        GlobalExceptionHandler custom =
                new GlobalExceptionHandler(new ErrorMessageRenderer(null, false, ZH_CN));

        this.runner.withBean("businessHandler", GlobalExceptionHandler.class, () -> custom)
                .run(context -> assertThat(context).hasSingleBean(GlobalExceptionHandler.class)
                        .getBean(GlobalExceptionHandler.class).isSameAs(custom));
    }

    @Test
    void should_use_application_renderer_when_it_provides_one() {
        LocaleContextHolder.setLocale(ZH_CN);

        this.runner.withBean("businessRenderer", ErrorMessageRenderer.class, FixedTextRenderer::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(ErrorMessageRenderer.class);
                    assertThat(context.getBean(ErrorMessageRenderer.class)).isInstanceOf(FixedTextRenderer.class);

                    // 不打私有字段：直接调装配出来的 handler，看它吐出的文案来自业务渲染器——这证明注入的是同一个 bean
                    var entity = context.getBean(GlobalExceptionHandler.class)
                            .handleBizException(new BizException(CommonErrorCode.NOT_FOUND));
                    assertThat(entity.getBody().message()).isEqualTo("业务渲染器接管");
                    assertThat(entity.getStatusCode().value()).isEqualTo(404);
                });
    }

    /** 业务替换渲染器的夹具：{@code render} 必须是 public 非 final，才能被覆盖。 */
    static final class FixedTextRenderer extends ErrorMessageRenderer {

        FixedTextRenderer() {
            super(null, true, ZH_CN);
        }

        @Override
        public String render(ErrorCode errorCode, Object[] messageArgs) {
            return "业务渲染器接管";
        }
    }
}

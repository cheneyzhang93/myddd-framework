package org.deep361.myddd.web.i18n;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.validation.FieldError;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 错误文案的四级渲染链：业务消息源 → 框架内置 bundle @ 请求语言 → 内置 bundle @ 兜底语言 → 错误码自带文案。
 *
 * <p>每条用例都显式设定请求语言：本机 JVM 默认语言是 {@code zh_CN}，不设语言时"命中了中文内置文案"与
 * "跟随了 JVM 默认语言"两种实现都能跑绿，而那正是这条链最危险的分歧点。
 *
 * <p>断言一律挑"能区分链级"的取值（设计要点 4）：占位符形态（{@code {0}} 只可能来自 bundle）或语言不同。
 */
class ErrorMessageRendererTest {

    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");
    private static final Locale EN_US = Locale.forLanguageTag("en-US");
    private static final Locale FR_FR = Locale.forLanguageTag("fr-FR");

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static ErrorMessageRenderer renderer(MessageSource businessMessages, boolean enabled,
                                                     Locale fallbackLocale) {
        return new ErrorMessageRenderer(businessMessages, enabled, fallbackLocale);
    }

    @Test
    void should_render_only_error_code_fallback_when_i18n_off() {
        LocaleContextHolder.setLocale(EN_US);
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage(CommonErrorCode.ARGUMENT_INVALID.messageKey(), EN_US, "Parameter {0} is invalid");

        assertThat(renderer(business, false, ZH_CN)
                .render(CommonErrorCode.ARGUMENT_INVALID, new Object[] {"age"}))
                .isEqualTo("参数 age 不合法");
    }

    @Test
    void should_prefer_business_message_source_over_framework_bundle() {
        LocaleContextHolder.setLocale(ZH_CN);
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage(CommonErrorCode.NOT_FOUND.messageKey(), ZH_CN, "订单不存在");

        assertThat(renderer(business, true, ZH_CN).render(CommonErrorCode.NOT_FOUND, null))
                .isEqualTo("订单不存在");
    }

    @Test
    void should_fall_back_to_framework_bundle_when_business_lacks_key() {
        LocaleContextHolder.setLocale(ZH_CN);

        // 期望 {0} 形态：证明答案来自内置 bundle，而不是跳过前两级后的内核兜底文案（那是 {} 形态）
        assertThat(renderer(new StaticMessageSource(), true, ZH_CN)
                .render(CommonErrorCode.ARGUMENT_INVALID, null)).isEqualTo("参数 {0} 不合法");
    }

    @Test
    void should_hit_framework_english_bundle_for_english_request() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("en-GB"));

        assertThat(renderer(null, true, ZH_CN).render(CommonErrorCode.NOT_FOUND, null))
                .isEqualTo("Resource not found");
    }

    @Test
    void should_use_default_locale_when_request_locale_has_no_bundle() {
        LocaleContextHolder.setLocale(FR_FR);

        // en 兜底：与内核中文文案必然不同，这一条只有第 3 级能给出答案
        assertThat(renderer(null, true, EN_US).render(CommonErrorCode.CONFLICT, null))
                .isEqualTo("Data state conflict, please refresh and retry");
        // zh-CN 兜底：靠占位符形态区分（{} 来自内核、{0} 来自 bundle）
        assertThat(renderer(null, true, ZH_CN).render(CommonErrorCode.ARGUMENT_INVALID, null))
                .isEqualTo("参数 {0} 不合法");
    }

    @Test
    void should_use_error_code_fallback_when_no_bundle_at_all() {
        LocaleContextHolder.setLocale(FR_FR);

        assertThat(renderer(null, true, FR_FR).render(ProbeErrorCode.ORDER_NOT_FOUND, null))
                .isEqualTo("订单不存在");
    }

    @Test
    void should_format_arguments_per_locale_and_survive_missing_args() {
        LocaleContextHolder.setLocale(ZH_CN);
        ErrorMessageRenderer renderer = renderer(null, true, ZH_CN);

        assertThat(renderer.render(CommonErrorCode.ARGUMENT_INVALID, new Object[] {"age"}))
                .isEqualTo("参数 age 不合法");
        assertThat(renderer.render(CommonErrorCode.ARGUMENT_INVALID, new Object[0]))
                .isEqualTo("参数 {0} 不合法");
        assertThat(renderer.render(CommonErrorCode.ARGUMENT_INVALID, null)).isEqualTo("参数 {0} 不合法");
    }

    @Test
    void should_render_two_argument_code_in_each_language_word_order() {
        LocaleContextHolder.setLocale(ZH_CN);
        assertThat(renderer(null, true, ZH_CN).render(CommonErrorCode.ILLEGAL_STATE, new Object[] {"PAID", "退款"}))
                .isEqualTo("当前状态 PAID 不允许执行操作 退款");
        LocaleContextHolder.setLocale(EN_US);
        assertThat(renderer(null, true, ZH_CN).render(CommonErrorCode.ILLEGAL_STATE, new Object[] {"PAID", "refund"}))
                .isEqualTo("Operation refund is not allowed in current state PAID");
        // 关掉 i18n 走内核文案时结果必须同义，否则"关开关"等于换了一套话术
        assertThat(new BizException(CommonErrorCode.ILLEGAL_STATE, "PAID", "退款").getMessage())
                .isEqualTo("当前状态 PAID 不允许执行操作 退款");
    }

    @Test
    void should_provide_zh_and_en_messages_for_every_common_error_key() {
        ResourceBundle zh = bundle(ZH_CN);
        ResourceBundle en = bundle(EN_US);

        for (CommonErrorCode value : CommonErrorCode.values()) {
            assertThat(zh.getString(value.messageKey())).as("中文键 %s", value.messageKey()).isNotBlank();
            assertThat(en.getString(value.messageKey())).as("英文键 %s", value.messageKey()).isNotBlank();
        }
        // 键数必须与错误码数一致：既不能漏键，也不许悄悄塞进用不上的键
        assertThat(zh.keySet()).hasSize(CommonErrorCode.values().length);
        assertThat(en.keySet()).hasSize(CommonErrorCode.values().length);
        // 内置 bundle 用 MessageFormat 形态，内核用 {} 形态：这条同时守住"渲染链不能改成直接吐 defaultMessage"
        assertThat(zh.getString(CommonErrorCode.ARGUMENT_INVALID.messageKey())).isEqualTo("参数 {0} 不合法");
        assertThat(en.getString(CommonErrorCode.ILLEGAL_STATE.messageKey()))
                .isEqualTo("Operation {1} is not allowed in current state {0}");
    }

    @Test
    void should_degrade_to_fallback_when_message_source_throws() {
        LocaleContextHolder.setLocale(ZH_CN);
        MessageSource hostile = new ThrowingMessageSource();

        assertThatCode(() -> renderer(hostile, true, ZH_CN).render(CommonErrorCode.NOT_FOUND, null))
                .doesNotThrowAnyException();
        assertThat(renderer(hostile, true, ZH_CN).render(CommonErrorCode.NOT_FOUND, null))
                .isEqualTo("资源不存在");
    }

    @Test
    void should_prefer_business_message_for_validation_code() {
        LocaleContextHolder.setLocale(ZH_CN);
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage("NotBlank.orderForm.orderId", ZH_CN, "订单号必须填写");

        assertThat(renderer(business, true, ZH_CN).render(new DefaultMessageSourceResolvable(
                new String[] {"NotBlank.orderForm.orderId", "NotBlank.orderId", "NotBlank"},
                null, "must not be blank"))).isEqualTo("订单号必须填写");
    }

    @Test
    void should_try_codes_in_declaration_order_and_stop_at_first_hit() {
        LocaleContextHolder.setLocale(ZH_CN);
        StaticMessageSource business = new StaticMessageSource();
        // Spring 为字段错误排的 codes 是"最具体 → 最通用"，两条都有答案时只能取最具体那条
        business.addMessage("NotBlank.orderForm.orderId", ZH_CN, "精确键");
        business.addMessage("NotBlank", ZH_CN, "通用键");

        assertThat(renderer(business, true, ZH_CN).render(new DefaultMessageSourceResolvable(
                new String[] {"NotBlank.orderForm.orderId", "NotBlank"}, null, "must not be blank")))
                .isEqualTo("精确键");

        // 精确键无解时继续往下找，而不是因为第一条 Miss 就放弃整条链
        StaticMessageSource onlyGeneral = new StaticMessageSource();
        onlyGeneral.addMessage("NotBlank", ZH_CN, "通用键");
        assertThat(renderer(onlyGeneral, true, ZH_CN).render(new DefaultMessageSourceResolvable(
                new String[] {"NotBlank.orderForm.orderId", "NotBlank"}, null, "must not be blank")))
                .isEqualTo("通用键");
    }

    @Test
    void should_fall_back_to_interpolated_default_message_when_codes_miss() {
        LocaleContextHolder.setLocale(ZH_CN);

        assertThat(renderer(new StaticMessageSource(), true, ZH_CN).render(new DefaultMessageSourceResolvable(
                new String[] {"Size.orderForm.tag", "Size"}, null, "长度不能超过 3 个字符")))
                .isEqualTo("长度不能超过 3 个字符");
    }

    @Test
    void should_read_default_message_when_codes_are_null_instead_of_npe() {
        LocaleContextHolder.setLocale(ZH_CN);
        // 实测 FieldError(String,String,String) 把 codes 置为 null：这是业务手搭错误对象的常见写法
        FieldError noCodes = new FieldError("orderForm", "orderId", "字段无值");

        assertThat(renderer(null, true, ZH_CN).render(noCodes)).isEqualTo("字段无值");
        // 连默认文案都没有时给出空串：data 里不允许出现 null 值，"有键无文案"比"键被序列化成 null"更好诊断
        assertThat(renderer(null, true, ZH_CN)
                .render(new DefaultMessageSourceResolvable(new String[] {"unknown.code"}, null, null)))
                .isEmpty();
    }

    @Test
    void should_skip_validation_resolution_when_i18n_off() {
        LocaleContextHolder.setLocale(ZH_CN);
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage("NotBlank.orderForm.orderId", ZH_CN, "订单号必须填写");

        assertThat(renderer(business, false, ZH_CN).render(new DefaultMessageSourceResolvable(
                new String[] {"NotBlank.orderForm.orderId"}, null, "must not be blank")))
                .isEqualTo("must not be blank");
    }

    private static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle(ErrorMessageRenderer.BUNDLE_BASENAME, locale,
                ErrorMessageRendererTest.class.getClassLoader());
    }

    /** 业务自定义错误码夹具：框架内置 bundle 里没有它的键，只能落到自身兜底文案。 */
    enum ProbeErrorCode implements ErrorCode {

        ORDER_NOT_FOUND("ORDER_40401", "order.error.not-found", 404, "订单不存在");

        private final String code;
        private final String messageKey;
        private final int httpStatus;
        private final String defaultMessage;

        ProbeErrorCode(String code, String messageKey, int httpStatus, String defaultMessage) {
            this.code = code;
            this.messageKey = messageKey;
            this.httpStatus = httpStatus;
            this.defaultMessage = defaultMessage;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String messageKey() {
            return messageKey;
        }

        @Override
        public int httpStatus() {
            return httpStatus;
        }

        @Override
        public String defaultMessage() {
            return defaultMessage;
        }
    }

    /** 三个解析入口全部失败的假消息源：模拟"缺 bundle""缺键""实现方直接抛异常"三种上游行为。 */
    static final class ThrowingMessageSource implements MessageSource {

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            throw new MissingResourceException("no bundle", code, locale.toLanguageTag());
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
            throw new NoSuchMessageException(code, locale);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
            throw new NoSuchMessageException(String.valueOf(resolvable), locale);
        }
    }
}

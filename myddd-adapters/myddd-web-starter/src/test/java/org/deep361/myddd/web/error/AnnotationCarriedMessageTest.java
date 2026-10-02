package org.deep361.myddd.web.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.deep361.myddd.web.json.JsonConventions;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R12 的那一格：{@code BizException} 自带非空白文案时优先于 i18n 渲染，但状态码与错误码身份不变。
 *
 * <p>夹具全部在本类内部——web-starter 不依赖 auth/resilience 能力模块，两家拒绝类的端到端由"注解文案 ==
 * 异常自带文案"（那两家的用例）与"自带文案 == 响应文案"（本类）两格合成，不再起第三条链。
 */
class AnnotationCarriedMessageTest {

    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");
    private static final String BUSINESS_TEXT = "只有财务角色能看这张报表";
    private static final String FORBIDDEN_SOURCE_TEXT = "当前角色没有该报表的查看权限";
    private static final String ILLEGAL_STATE_SOURCE_TEXT = "状态 {0} 不能退款 {1}";

    @BeforeEach
    void useChineseRequest() {
        LocaleContextHolder.setLocale(ZH_CN);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** TX1：端到端优先序——响应文案逐字等于注解文案，且渲染器一次都没被调。 */
    @Test
    void should_carry_annotation_text_end_to_end_without_calling_renderer() throws Exception {
        CountingRenderer renderer = new CountingRenderer(businessSourceWithForbiddenKey());
        MvcResult result = mvc(new GlobalExceptionHandler(renderer)).perform(get("/carried")).andReturn();

        assertThat(content(result)).contains(BUSINESS_TEXT);
        assertThat(renderer.calls).isZero();
    }

    /** TX2：自带文案是空白时不顶替——响应取消息源那一条，而不是空白、也不是错误码缺省文案。 */
    @Test
    void should_fall_back_to_message_source_when_carried_text_is_blank() {
        CountingRenderer renderer = new CountingRenderer(businessSourceWithForbiddenKey());
        BizException exception = new Carrying(CommonErrorCode.FORBIDDEN, "   ");

        ResponseEntity<org.deep361.myddd.kernel.common.response.Response<Void>> response =
                new GlobalExceptionHandler(renderer).handleBizException(exception);

        assertThat(response.getBody().message()).isEqualTo(FORBIDDEN_SOURCE_TEXT);
        assertThat(renderer.calls).isEqualTo(1);
    }

    /**
     * TX3：自带文案恰好等于错误码缺省渲染时让位给 i18n——这是"优先序不需要按 {@code getClass()} 判"那半条
     * 推理的机读证据：把等值判断删掉，plain {@code BizException} 就会绕过消息源。
     */
    @Test
    void should_yield_to_message_source_when_carried_text_equals_default_rendering() {
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage(CommonErrorCode.ILLEGAL_STATE.messageKey(), ZH_CN, ILLEGAL_STATE_SOURCE_TEXT);
        CountingRenderer renderer = new CountingRenderer(business);
        BizException exception = new BizException(CommonErrorCode.ILLEGAL_STATE, "PAID", "退款");

        // 前提：plain 异常的 getMessage() 就是缺省渲染结果，否则这一格钉不到等值那一支。
        assertThat(exception.getMessage())
                .isEqualTo(CommonErrorCode.ILLEGAL_STATE.formatDefaultMessage("PAID", "退款"));

        ResponseEntity<org.deep361.myddd.kernel.common.response.Response<Void>> response =
                new GlobalExceptionHandler(renderer).handleBizException(exception);

        assertThat(response.getBody().message()).isEqualTo("状态 PAID 不能退款 退款");
        assertThat(renderer.calls).isEqualTo(1);
    }

    /** TX4：自带文案不进渲染器——它原样上线，花括号不许被当模板吃掉。 */
    @Test
    void should_not_rerender_the_carried_text_as_a_template() {
        String carried = "余额 {} 不足，请联系充值";
        CountingRenderer renderer = new CountingRenderer(businessSourceWithForbiddenKey());

        ResponseEntity<org.deep361.myddd.kernel.common.response.Response<Void>> response =
                new GlobalExceptionHandler(renderer).handleBizException(new Carrying(CommonErrorCode.FORBIDDEN, carried));

        assertThat(response.getBody().message()).isEqualTo(carried);
        assertThat(renderer.calls).isZero();
    }

    /** TX5：只换文案不换形状——关掉 i18n 之后状态码与错误码仍由唯一漏斗给出。 */
    @Test
    void should_keep_status_and_code_when_only_the_text_is_replaced() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
                new ErrorMessageRenderer(null, false, ZH_CN));

        MvcResult result = mvc(handler).perform(get("/carried")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(content(result)).contains("MYDDD_40301").contains(BUSINESS_TEXT);
    }

    /** TX6：日志通道与响应通道同源不同则——warn 记的一直是异常原文，不是渲染结果。 */
    @Test
    void should_log_the_carried_text_instead_of_the_rendered_one() {
        CountingRenderer renderer = new CountingRenderer(businessSourceWithForbiddenKey());
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler(renderer).handleBizException(
                    new Carrying(CommonErrorCode.FORBIDDEN, BUSINESS_TEXT));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.get(0).getFormattedMessage()).contains(BUSINESS_TEXT);
        assertThat(renderer.calls).isZero();
    }

    private static StaticMessageSource businessSourceWithForbiddenKey() {
        StaticMessageSource business = new StaticMessageSource();
        business.addMessage(CommonErrorCode.FORBIDDEN.messageKey(), ZH_CN, FORBIDDEN_SOURCE_TEXT);
        return business;
    }

    private static org.springframework.test.web.servlet.MockMvc mvc(GlobalExceptionHandler handler) {
        return MockMvcBuilders.standaloneSetup(new CarryingEndpoint())
                .setControllerAdvice(handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonConventions.frameworkMapper()))
                .build();
    }

    private static String content(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 拒绝形状的夹具：只覆写 {@code getMessage()}，与两家 provider 的拒绝类同形。 */
    static class Carrying extends BizException {

        private final String businessText;

        Carrying(ErrorCode errorCode, String businessText) {
            super(errorCode);
            this.businessText = businessText;
        }

        @Override
        public String getMessage() {
            return this.businessText;
        }
    }

    /** 计数渲染器：只覆盖 {@code render(ErrorCode, Object[])}——出口漏斗只走这一条。 */
    static class CountingRenderer extends ErrorMessageRenderer {

        private int calls;

        CountingRenderer(StaticMessageSource business) {
            super(business, true, ZH_CN);
        }

        @Override
        public String render(ErrorCode errorCode, Object[] messageArgs) {
            this.calls++;
            return super.render(errorCode, messageArgs);
        }
    }

    /** 只负责抛出夹具的嵌套端点：{@code standaloneSetup} 不需要 Web 容器。 */
    @RestController
    static class CarryingEndpoint {

        @GetMapping("/carried")
        String carried() {
            throw new Carrying(CommonErrorCode.FORBIDDEN, BUSINESS_TEXT);
        }
    }
}

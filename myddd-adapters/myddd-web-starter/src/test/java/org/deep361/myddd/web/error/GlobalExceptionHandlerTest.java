package org.deep361.myddd.web.error;

import jakarta.validation.ConstraintViolationException;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.deep361.myddd.web.json.JsonConventions;
import org.deep361.myddd.web.trace.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常分派：状态码语义、i18n 文案、不泄漏三件事各自有用例。
 *
 * <p>单元用例直接调 handler 方法（看状态码、响应体与响应头），端到端用例经真实 MockMvc（看写出到线上时的
 * 字节：中文还是不是中文、堆栈有没有跟出去）。两条链分开守：端到端用例覆盖的是"异常真的被这个 handler 接住"，
 * 单元用例覆盖的是"接住之后逐条性质成立"。
 */
class GlobalExceptionHandlerTest {

    private static final Locale ZH_CN = Locale.forLanguageTag("zh-CN");
    private static final Locale EN_US = Locale.forLanguageTag("en-US");

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ErrorMessageRenderer(null, true, ZH_CN));

    @BeforeEach
    void useChineseRequest() {
        LocaleContextHolder.setLocale(ZH_CN);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void should_take_status_and_message_from_biz_error_code() {
        ResponseEntity<org.deep361.myddd.kernel.common.response.Response<Void>> entity =
                handler.handleBizException(new BizException(CommonErrorCode.NOT_FOUND));

        assertThat(entity.getStatusCode().value()).isEqualTo(404);
        assertThat(entity.getBody()).isNotNull();
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40401");
        assertThat(entity.getBody().message()).isEqualTo("资源不存在");
        assertThat(entity.getBody().data()).isNull();
        assertThat(entity.getBody().successful()).isFalse();
    }

    @Test
    void should_render_biz_message_with_placeholder_arguments() {
        var entity = handler.handleBizException(new BizException(CommonErrorCode.ILLEGAL_STATE, "PAID", "退款"));

        assertThat(entity.getStatusCode().value()).isEqualTo(409);
        assertThat(entity.getBody().message()).isEqualTo("当前状态 PAID 不允许执行操作 退款");
    }

    @Test
    void should_keep_503_for_business_5xx_instead_of_collapsing_to_500() {
        var entity = handler.handleBizException(new BizException(CommonErrorCode.SERVICE_DEGRADED));

        assertThat(entity.getStatusCode().value()).isEqualTo(503);
        assertThat(entity.getBody().code()).isEqualTo(CommonErrorCode.SERVICE_DEGRADED.code());
        assertThat(entity.getBody().message()).isEqualTo("服务暂时不可用（熔断降级）");
    }

    @Test
    void should_localize_message_by_request_locale_without_changing_status() {
        LocaleContextHolder.setLocale(EN_US);

        var entity = handler.handleBizException(new BizException(CommonErrorCode.NOT_FOUND));

        assertThat(entity.getBody().message()).isEqualTo("Resource not found");
        assertThat(entity.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void should_hide_framework_diagnostics_behind_system_error() {
        var entity = handler.handleFrameworkException(
                new FrameworkException("myddd.web.i18n.default-locale=非法标签"));

        assertThat(entity.getStatusCode().value()).isEqualTo(500);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_50001");
        assertThat(entity.getBody().message()).isEqualTo("系统内部错误");
        assertThat(entity.getBody().message()).doesNotContain("myddd.web").doesNotContain("非法标签");
    }

    @Test
    void should_return_400_for_unreadable_body_without_echoing_content() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unexpected character at [Body: {\"cardNo\":\"6222021234567890123\"}]",
                new IllegalStateException("unexpected token"), null);

        var entity = handler.handleHttpMessageNotReadable(ex);

        assertThat(entity.getStatusCode().value()).isEqualTo(400);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40002");
        assertThat(entity.getBody().message()).isEqualTo("请求格式错误");
        assertThat(entity.getBody().message()).doesNotContain("cardNo").doesNotContain("6222");
    }

    @Test
    void should_return_405_with_allow_header_for_unsupported_method() {
        var entity = handler.handleHttpRequestMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST")));

        assertThat(entity.getStatusCode().value()).isEqualTo(405);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40501");
        assertThat(entity.getHeaders().getFirst("Allow").split(", ")).containsExactlyInAnyOrder("GET", "POST");
    }

    @Test
    void should_omit_allow_header_when_no_supported_methods() {
        var entity = handler.handleHttpRequestMethodNotSupported(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(entity.getStatusCode().value()).isEqualTo(405);
        assertThat(entity.getHeaders().getFirst("Allow")).isNull();
    }

    @Test
    void should_omit_allow_header_when_supported_methods_are_empty() {
        var entity = handler.handleHttpRequestMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE", List.of()));

        assertThat(entity.getStatusCode().value()).isEqualTo(405);
        assertThat(entity.getHeaders().getFirst("Allow")).isNull();
    }

    @Test
    void should_return_404_for_missing_resource_instead_of_500() {
        var entity = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/favicon.ico", "favicon.ico"));

        assertThat(entity.getStatusCode().value()).isEqualTo(404);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_40401");
    }

    @Test
    void should_return_500_without_technical_message_for_unexpected() {
        var entity = handler.handleUnexpected(new IllegalStateException("jdbc:mysql://root:secret@db/prod"));

        assertThat(entity.getStatusCode().value()).isEqualTo(500);
        assertThat(entity.getBody().code()).isEqualTo("MYDDD_50001");
        assertThat(entity.getBody().message()).isEqualTo("系统内部错误");
        assertThat(entity.getBody().message()).doesNotContain("jdbc").doesNotContain("secret");
    }

    @Test
    void should_declare_every_dedicated_handler_type() {
        Set<Class<?>> declared = adviceHandledTypes();

        // Task 11 追加三类校验异常。漏注册任何一类的后果是"校验错误以 Spring 默认的 ProblemDetail 出去"，
        // 响应形状当场分裂；而直调 handler 的行为用例对此完全无感（它照样能编译、照样绿），只能由这条钉子守。
        assertThat(declared).containsExactlyInAnyOrder(BizException.class, FrameworkException.class,
                HttpMessageNotReadableException.class, HttpRequestMethodNotSupportedException.class,
                NoResourceFoundException.class, BindException.class,
                HandlerMethodValidationException.class, ConstraintViolationException.class, Exception.class);
        // 不继承 ResponseEntityExceptionHandler（Spring 7 的 handleException 是 final），且必须是 advice
        assertThat(GlobalExceptionHandler.class.getSuperclass()).isEqualTo(Object.class);
        assertThat(GlobalExceptionHandler.class).hasAnnotation(RestControllerAdvice.class);
    }

    @Test
    void should_write_utf8_chinese_message_with_fixed_body_keys() throws Exception {
        MvcResult result = mockMvc().perform(get("/probe/biz-illegal-state")
                        .header("Accept-Language", "zh-CN")
                        .header("X-Trace-Id", "trace-for-error"))
                .andExpect(status().isConflict())
                .andExpect(header().string("X-Trace-Id", "trace-for-error"))
                .andReturn();

        Map<String, Object> body = jsonBody(result);
        assertThat(body.get("message")).isEqualTo("当前状态 PAID 不允许执行操作 退款");
        assertThat(body.get("code")).isEqualTo("MYDDD_40902");
        assertThat(body.get("traceId")).isEqualTo("trace-for-error");
        assertThat(body.keySet()).containsExactlyInAnyOrder("code", "message", "data", "traceId", "successful");
    }

    @Test
    void should_leak_neither_message_nor_stack_for_system_error() throws Exception {
        MvcResult result = mockMvc().perform(get("/probe/system").header("Accept-Language", "zh-CN"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String body = content(result);
        assertThat(body).contains("系统内部错误");
        assertThat(body).doesNotContain("jdbc").doesNotContain("secret")
                .doesNotContain("IllegalStateException").doesNotContain("org.springframework");
    }

    @Test
    void should_render_framework_and_technical_errors_identically() throws Exception {
        MockMvc mockMvc = mockMvc();

        Map<?, ?> viaFramework = withoutTraceId(jsonBody(mockMvc.perform(get("/probe/framework")
                .header("Accept-Language", "zh-CN")).andReturn()));
        Map<?, ?> viaUnexpected = withoutTraceId(jsonBody(mockMvc.perform(get("/probe/system")
                .header("Accept-Language", "zh-CN")).andReturn()));

        assertThat(viaFramework).isEqualTo(viaUnexpected);
    }

    @Test
    void should_keep_503_semantics_for_english_request() throws Exception {
        MvcResult result = mockMvc().perform(get("/probe/biz-unavailable").header("Accept-Language", "en-US"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();

        assertThat(jsonBody(result).get("message")).isEqualTo("Service temporarily unavailable");
    }

    @Test
    void should_expose_only_malformed_request_for_broken_json() throws Exception {
        MvcResult result = mockMvc().perform(post("/probe/json")
                        .header("Accept-Language", "zh-CN")
                        .contentType("application/json")
                        .content("{\"cardNo\":oops}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = content(result);
        assertThat(body).contains("请求格式错误").doesNotContain("cardNo").doesNotContain("oops");
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeEndpoint())
                .setControllerAdvice(handler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonConventions.frameworkMapper()))
                .addFilters(new TraceIdFilter())
                .build();
    }

    /** handler 方法上 {@code @ExceptionHandler} 声明的异常类型全集。 */
    private static Set<Class<?>> adviceHandledTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation != null) {
                types.addAll(Arrays.asList(annotation.value()));
            }
        }
        return types;
    }

    private static String content(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(MvcResult result) throws Exception {
        return JsonConventions.frameworkMapper().readValue(content(result), Map.class);
    }

    private static Map<?, ?> withoutTraceId(Map<?, ?> body) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(cast(body));
        copy.remove("traceId");
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> body) {
        return (Map<String, Object>) body;
    }

    /** 编译期引用一次 ErrorCode：错误码常量在断言里以 {@code CommonErrorCode} 出现，这行只为保留契约可读性。 */
    @SuppressWarnings("unused")
    private static ErrorCode errorCodeOf(BizException ex) {
        return ex.getErrorCode();
    }
}

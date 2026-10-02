package org.deep361.myddd.web.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.ErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.common.response.Response;
import org.deep361.myddd.web.i18n.ErrorMessageRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.TreeMap;

/**
 * 全局异常分派：把抛出的异常按类型换成"语义状态码 + 统一响应体"，并守住"技术细节不出进程"。
 *
 * <p>三条不变量，每条都有对应用例：
 * <ol>
 *   <li><b>状态码由错误码决定</b>：4xx 是调用方过错、5xx 是服务端过错，网关与前端的重试/告警策略全靠这一条；
 *       业务抛的 5xx 错误码（如熔断降级 503）不得被压成 500。</li>
 *   <li><b>对外只有 {@code Response} 一个形状</b>：框架异常、技术异常、静态资源缺失三者在响应体上完全同形，
 *       调用方无从分辨内部实现（Step 2 的"框架异常与技术异常对外同形"用例逐字段比对）。</li>
 *   <li><b>不透出内部信息</b>：{@code FrameworkException.getMessage()} 是配置诊断信息、
 *       {@code HttpMessageNotReadableException.getMessage()} 可能含请求体片段（手机号/卡号），
 *       技术异常原文带连接串——三者一律只进日志，不进响应。</li>
 * </ol>
 *
 * <p>刻意<b>不继承</b> {@code ResponseEntityExceptionHandler}：Spring 7 里它的
 * {@code handleException(Exception, WebRequest)} 是 {@code final}（本机 {@code javap} 实测），继承之后要么改不动它、
 * 要么被它的默认 ProblemDetail 输出与本类的 {@code Response} 输出打架。这里自建分派链，
 * 并由 Step 2 的清单钉子保证"专用 handler 一个都不能少"。
 *
 * <p>traceId 不需要本类处理：{@code Response} 的紧凑构造器在 {@code traceId} 为 null 时自动取
 * {@code TraceContext.currentTraceId()}（内核契约），而 {@link org.deep361.myddd.web.trace.TraceIdFilter}
 * 已在请求边界写入了它。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** HTTP 状态码分界：{@code >=} 该值即服务端过错，日志按 error 记（4xx 是调用方过错，按 warn 记，不刷 error 告警）。 */
    private static final int SERVER_ERROR_STATUS = 500;

    /** 跨参数约束（一次校验同时看多个参数）没有单一字段可挂，用固定键，前端按"表单级错误"呈现。 */
    private static final String CROSS_PARAMETER_KEY = "crossParameter";

    private final ErrorMessageRenderer messageRenderer;

    public GlobalExceptionHandler(ErrorMessageRenderer messageRenderer) {
        this.messageRenderer = messageRenderer;
    }

    /** 业务异常：语义完全由错误码给出，是量最大的一类，因此日志按状态码分级而不是全 error。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Response<Void>> handleBizException(BizException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode.httpStatus() >= SERVER_ERROR_STATUS) {
            log.error("业务异常 {}：{}", errorCode.code(), ex.getMessage(), ex);
        } else {
            log.warn("业务异常 {}：{}", errorCode.code(), ex.getMessage());
        }
        return renderedResponse(errorCode, AnnotationCarriedMessage.messageFor(ex, messageRenderer), null,
                new HttpHeaders());
    }

    /** 框架异常：message 是给开发者看的配置诊断，对外只有"系统内部错误"。 */
    @ExceptionHandler(FrameworkException.class)
    public ResponseEntity<Response<Void>> handleFrameworkException(FrameworkException ex) {
        log.error("框架内部异常：{}", ex.getMessage(), ex);
        return errorResponse(CommonErrorCode.SYSTEM_ERROR, null);
    }

    /**
     * 请求体不可读：Spring 的 message 里带请求体片段与字段名，可能含证件号/卡号，因此日志只记根因类型。
     * 对外也只有 {@code 请求格式错误}——不回显任何请求内容。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Response<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体不可读：cause={}", ex.getMostSpecificCause().getClass().getName());
        return errorResponse(CommonErrorCode.MALFORMED_REQUEST, null);
    }

    /** 方法不支持：405 必须带 {@code Allow}，这是 HTTP 语义的一部分；无候选方法时不写空头。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Response<Void>> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持：method={}", ex.getMethod());
        HttpHeaders headers = new HttpHeaders();
        // 实测（spring-web 7.0.9 运行期）：单参构造器 `HttpRequestMethodNotSupportedException(String)` 让
        // getSupportedMethods() 返回 null，双参构造器才给数组。null 与空数组两种"没有候选方法"都要挡住，
        // 否则响应里出现 `Allow: `（空头）比不写更糟——网关会把它当成"允许空方法"。
        String[] supportedMethods = ex.getSupportedMethods();
        if (supportedMethods != null && supportedMethods.length > 0) {
            headers.add(HttpHeaders.ALLOW, String.join(", ", supportedMethods));
        }
        return errorResponse(CommonErrorCode.METHOD_NOT_ALLOWED, null, headers);
    }

    /**
     * 静态资源/未映射路径缺失：Boot 4 把它作为 {@code NoResourceFoundException} 抛出，
     * 若被 {@code Exception} 兜底接走就成了 500——前端与网关会对"404 打错地址"发起服务端告警与重试。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Response<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("静态资源未找到：{}", ex.getResourcePath());
        return errorResponse(CommonErrorCode.NOT_FOUND, null);
    }

    /** 未预期异常兜底：原文与堆栈只进日志，响应里只有系统错误。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常：{}", ex.getClass().getName(), ex);
        return errorResponse(CommonErrorCode.SYSTEM_ERROR, null);
    }

    /**
     * 表单与请求体的字段校验失败。实测 {@code MethodArgumentNotValidException extends BindException}（javap），
     * 所以注册父类一条即覆盖两种来源，不再单独注册子类。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Response<Map<String, String>>> handleBindException(BindException ex) {
        log.warn("参数校验失败：{} 项", ex.getErrorCount());
        Map<String, String> fieldErrors = new TreeMap<>();
        for (ObjectError error : ex.getAllErrors()) {
            fieldErrors.putIfAbsent(objectErrorKey(error), messageRenderer.render(error));
        }
        return validationResponse(fieldErrors);
    }

    /**
     * 方法参数级校验失败（{@code @RequestParam @NotBlank} 这类直接写在方法形参上的约束，Spring 6.1+ 抛这个类型）。
     * 字段错误沿用 {@link ObjectError} 规则，其余按"参数名 + 容器位置"（要点 2 的表）。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Map<String, String>>> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        log.warn("方法参数校验失败：{} 个参数有错", ex.getParameterValidationResults().size());
        Map<String, String> fieldErrors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                fieldErrors.putIfAbsent(parameterKey(error, result), messageRenderer.render(error));
            }
        }
        for (MessageSourceResolvable error : ex.getCrossParameterValidationResults()) {
            fieldErrors.putIfAbsent(CROSS_PARAMETER_KEY, messageRenderer.render(error));
        }
        return validationResponse(fieldErrors);
    }

    /**
     * 直接抛出的 Bean Validation 异常（领域服务里 {@code validator.validate(...)} 之后手工抛，或任何不经 Spring
     * 数据绑定的场景）。它没有 Spring 的 codes 体系，因此按"约束名.路径 + 约束名"造两个候选键让业务仍可覆盖文案，
     * 兜底是 {@code violation.getMessage()}（HV 已完成插值，不再传参二次格式化）；{@code data} 的键用 propertyPath 原文。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Map<String, String>>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("约束校验失败：{} 项", ex.getConstraintViolations().size());
        Map<String, String> fieldErrors = new TreeMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
            MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                    new String[] {constraint + "." + path, constraint}, null, violation.getMessage());
            fieldErrors.putIfAbsent(path, messageRenderer.render(resolvable));
        }
        return validationResponse(fieldErrors);
    }

    /** 全局错误（类级约束／{@code reject(...)}）没有字段名，用对象名当键；字段错误用 Spring 算好的路径。 */
    private static String objectErrorKey(ObjectError error) {
        return error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
    }

    private static String parameterKey(MessageSourceResolvable error, ParameterValidationResult result) {
        if (error instanceof ObjectError objectError) {
            return objectErrorKey(objectError);
        }
        MethodParameter parameter = result.getMethodParameter();
        String parameterName = parameter.getParameterName();
        // 参数名拿不到（业务构建未开 -parameters）时用 arg+下标：键必须是可预期的位置标识，不能是字符串 "null"
        String base = parameterName != null ? parameterName : "arg" + parameter.getParameterIndex();
        if (result.getContainerIndex() != null) {
            return base + "[" + result.getContainerIndex() + "]";
        }
        if (result.getContainerKey() != null) {
            return base + "{" + result.getContainerKey() + "}";
        }
        return base;
    }

    private ResponseEntity<Response<Map<String, String>>> validationResponse(Map<String, String> fieldErrors) {
        return errorResponse(CommonErrorCode.INVALID_PARAMETER, null, fieldErrors, new HttpHeaders());
    }

    private ResponseEntity<Response<Void>> errorResponse(ErrorCode errorCode, Object[] messageArgs) {
        return errorResponse(errorCode, messageArgs, null, new HttpHeaders());
    }

    private ResponseEntity<Response<Void>> errorResponse(ErrorCode errorCode, Object[] messageArgs,
                                                        HttpHeaders headers) {
        return errorResponse(errorCode, messageArgs, null, headers);
    }

    /**
     * 唯一的出口漏斗：状态码、响应体、响应头三件事只在这一处拼装，杜绝各 handler 各写一套。
     *
     * <p>用 {@link Response} 的规范构造器而不是 {@code Response.fail(...)}：后者装不了 {@code data}，而校验类响应
     * 必须把逐字段错误带出去。{@code traceId} 传 {@code null} 即可——记录的紧凑构造器会自取
     * {@code TraceContext.currentTraceId()}（内核契约）；只有 {@code handleBizException} 会传非空
     * {@code messageArgs}，而"不含 Throwable"是 {@code BizException} 的构造契约，无需在这里复查。
     *
     * <p>R12 之后本方法不再是文案的唯一生成点：业务异常自带文案时优先用它，那一格由
     * {@link AnnotationCarriedMessage} 裁决，裁决结果经 {@link #renderedResponse} 进同一个漏斗
     * （{@code TX1}–{@code TX3}）。
     */
    private <T> ResponseEntity<Response<T>> errorResponse(ErrorCode errorCode, Object[] messageArgs, T data,
                                                         HttpHeaders headers) {
        return renderedResponse(errorCode, messageRenderer.render(errorCode, messageArgs), data, headers);
    }

    /**
     * 响应装配单点：文案已经生成好，这里只管状态码、响应体、响应头三件事。
     *
     * <p>方法体是原 {@code errorResponse(ErrorCode, Object[], T, HttpHeaders)} 的三行逐字搬过来的，
     * 一个字符未改（{@code TX5} 钉"搬家没有改变任何一条既有响应"）。分成两个方法是为了让
     * {@code handleBizException} 能带着已裁决的文案进来，而不是绕过漏斗自己拼响应。
     */
    private <T> ResponseEntity<Response<T>> renderedResponse(ErrorCode errorCode, String message, T data,
                                                            HttpHeaders headers) {
        Response<T> body = new Response<>(errorCode.code(), message, data, null);
        return ResponseEntity.status(errorCode.httpStatus()).headers(headers).body(body);
    }
}

package org.deep361.myddd.web.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只用来触发异常的探针端点：正常路径一个也没有，测的就是"抛出去之后线上长什么样"。
 */
@RestController
@RequestMapping("/probe")
class ProbeEndpoint {

    /** 4xx 业务异常：状态码语义 + 带参文案 + traceId 同源。 */
    @GetMapping("/biz-illegal-state")
    String bizIllegalState() {
        throw new BizException(CommonErrorCode.ILLEGAL_STATE, "PAID", "退款");
    }

    /** 5xx 业务异常：熔断降级对外是 503，不是 500。 */
    @GetMapping("/biz-unavailable")
    String bizUnavailable() {
        throw new BizException(CommonErrorCode.SERVICE_DEGRADED);
    }

    /** 技术异常：message 里刻意放连接串，验证它不会出现在响应里。 */
    @GetMapping("/system")
    String system() {
        throw new IllegalStateException("jdbc:mysql://root:secret@db/prod");
    }

    /** 框架异常：message 是配置诊断信息，同样不得外发。 */
    @GetMapping("/framework")
    String framework() {
        throw new FrameworkException("myddd.web.i18n.default-locale=非法标签 不是合法的语言标签");
    }

    /** 送进 Jackson 反序列化：坏 JSON 触发 HttpMessageNotReadableException。 */
    @PostMapping("/json")
    String json(@RequestBody JsonOrder body) {
        return body.orderId();
    }

    /** 交给 Bean Validation：字段级校验失败抛 {@code MethodArgumentNotValidException}（实测它是 {@code BindException} 子类）。 */
    @PostMapping("/valid-body")
    String validBody(@Valid @RequestBody ValidOrder order) {
        return order.orderId();
    }

    /** 三个约束各管一个字段；声明序（orderId, level, tag）刻意与字典序（level, orderId, tag）不同，用来咬住排序实现。 */
    record ValidOrder(@NotBlank String orderId, @NotBlank String level, @Size(max = 3) String tag) {
    }

    record JsonOrder(String orderId) {
    }
}

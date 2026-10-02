package org.deep361.myddd.feign.error;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteErrorCodeTest {

    @Test
    void should_project_the_remote_error_read_only() {
        RemoteErrorCode code = new RemoteErrorCode("STOCK_40901", "库存不足", 409, "abc123", "StockApi#deduct(POST)");

        assertThat(code.code()).isEqualTo("STOCK_40901");
        assertThat(code.httpStatus()).isEqualTo(409);
        assertThat(code.defaultMessage()).isEqualTo("库存不足");
        assertThat(code.remoteTraceId()).isEqualTo("abc123");
        assertThat(code.methodKey()).isEqualTo("StockApi#deduct(POST)");
        assertThat(code).hasToString(
                "RemoteErrorCode[code=STOCK_40901, httpStatus=409, methodKey=StockApi#deduct(POST), "
                        + "remoteTraceId=abc123]");
    }

    @Test
    void should_render_a_code_based_message_when_the_remote_text_is_missing_or_blank() {
        assertThat(new RemoteErrorCode("STOCK_40901", null, 500, null, null).defaultMessage())
                .isEqualTo("远程服务返回错误：STOCK_40901");
        assertThat(new RemoteErrorCode("STOCK_40901", "   ", 500, null, null).defaultMessage())
                .isEqualTo("远程服务返回错误：STOCK_40901");
    }

    @Test
    void should_use_a_fixed_message_key_that_has_no_bundle_behind_it() {
        RemoteErrorCode code = new RemoteErrorCode("STOCK_40901", "库存不足", 409, null, null);

        assertThat(code.messageKey()).isEqualTo("myddd.error.remote");
        assertThat(code.formatDefaultMessage("x"))
                .as("对端文案不参与本地占位符渲染：远程给的句子原样呈现")
                .isEqualTo("库存不足");
    }

    @Test
    void should_reject_a_missing_or_blank_code() {
        assertThatThrownBy(() -> new RemoteErrorCode(null, "m", 500, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("code");
        assertThatThrownBy(() -> new RemoteErrorCode("  ", "m", 500, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }
}

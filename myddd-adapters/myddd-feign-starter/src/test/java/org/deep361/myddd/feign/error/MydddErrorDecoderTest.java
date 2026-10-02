package org.deep361.myddd.feign.error;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.deep361.myddd.kernel.common.error.BizException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 错误体还原的单元测试：用真实的 {@code feign.Response} 夹具，不启容器、不发网络请求。
 */
class MydddErrorDecoderTest {

    private static final String METHOD_KEY = "OrderApi#getOrder(GET)";

    private final MydddErrorDecoder decoder = new MydddErrorDecoder(JsonMapper.builder().build());

    @Test
    void should_restore_remote_code_and_message_as_a_biz_exception() {
        Response response = response(500, "{\"code\":\"STOCK_40901\",\"message\":\"库存不足\",\"traceId\":\"abc123\"}");

        Exception decoded = decoder.decode(METHOD_KEY, response);

        assertThat(decoded).isInstanceOf(BizException.class);
        BizException biz = (BizException) decoded;
        assertThat(biz.getErrorCode().code()).isEqualTo("STOCK_40901");
        assertThat(biz.getMessage()).isEqualTo("库存不足");
        assertThat(((RemoteErrorCode) biz.getErrorCode()).remoteTraceId()).isEqualTo("abc123");
        assertThat(((RemoteErrorCode) biz.getErrorCode()).methodKey()).isEqualTo(METHOD_KEY);
        assertThat(biz.getCause()).as("原始 Feign 异常必须留在 cause 链上")
                .isInstanceOf(FeignException.class);
    }

    @Test
    void should_pass_the_remote_http_status_through_verbatim() {
        BizException biz = (BizException) decoder.decode(METHOD_KEY,
                response(404, "{\"code\":\"ORDER_40401\",\"message\":\"订单不存在\"}"));

        assertThat(biz.getErrorCode().httpStatus())
                .as("对端说 404 就是 404，压成 500 会让上游分不清哪一跳出的问题")
                .isEqualTo(404);
    }

    @Test
    void should_read_only_the_three_envelope_fields_and_ignore_the_rest() {
        String body = "{\"code\":\"ORDER_40001\",\"message\":\"参数不对\",\"traceId\":\"t1\","
                + "\"data\":null,\"extra\":{\"nested\":[1,2,3]}}";

        BizException biz = (BizException) decoder.decode(METHOD_KEY, response(400, body));

        assertThat(biz.getErrorCode().code()).isEqualTo("ORDER_40001");
    }

    @Test
    void should_survive_a_strict_business_mapper_that_rejects_unknown_fields() {
        JsonMapper strict = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        MydddErrorDecoder strictDecoder = new MydddErrorDecoder(strict);
        String body = "{\"code\":\"ORDER_40001\",\"message\":\"参数不对\",\"aFieldAddedDuringRollingRelease\":1}";

        assertThat(((BizException) strictDecoder.decode(METHOD_KEY, response(400, body)))
                .getErrorCode().code()).isEqualTo("ORDER_40001");
    }

    @Test
    void should_fall_back_when_the_body_is_not_json() {
        Exception decoded = decoder.decode(METHOD_KEY, response(500, "<html><body>502 Bad Gateway</body></html>"));

        assertThat(decoded).isInstanceOf(FeignException.class);
    }

    @Test
    void should_keep_the_original_body_visible_in_the_fallback_exception() {
        String body = "<html><body>502 Bad Gateway</body></html>";

        FeignException failure = (FeignException) decoder.decode(METHOD_KEY, streamResponse(500, body));

        assertThat(failure.contentUTF8())
                .as("本类先读过体，不回灌则 FeignException 里只剩空体")
                .contains("502 Bad Gateway");
    }

    @Test
    void should_fall_back_when_the_object_has_no_code_field() {
        assertThat(decoder.decode(METHOD_KEY, response(500, "{\"message\":\"只有文案\"}")))
                .isInstanceOf(FeignException.class);
    }

    @Test
    void should_fall_back_when_the_code_is_blank_or_not_a_string() {
        assertThat(decoder.decode(METHOD_KEY, response(500, "{\"code\":\"   \",\"message\":\"x\"}")))
                .isInstanceOf(FeignException.class);
        assertThat(decoder.decode(METHOD_KEY, response(500, "{\"code\":500,\"message\":\"x\"}")))
                .as("数字型 code 说明那不是我们的信封（我们的 code 是字符串）")
                .isInstanceOf(FeignException.class);
    }

    @Test
    void should_fall_back_when_the_json_is_not_an_object() {
        assertThat(decoder.decode(METHOD_KEY, response(500, "[1,2,3]"))).isInstanceOf(FeignException.class);
        assertThat(decoder.decode(METHOD_KEY, response(500, "\"just a string\""))).isInstanceOf(FeignException.class);
    }

    @Test
    void should_fall_back_when_the_body_is_empty_or_absent() {
        assertThat(decoder.decode(METHOD_KEY, response(500, ""))).isInstanceOf(FeignException.class);
        assertThat(decoder.decode(METHOD_KEY, responseWithoutBody(500)))
                .as("无体响应与读不出的体走同一条退路")
                .isInstanceOf(FeignException.class);
    }

    @Test
    void should_fall_back_when_the_body_cannot_be_read() {
        AtomicInteger delegateCalls = new AtomicInteger();
        MydddErrorDecoder decoderWithSpy = new MydddErrorDecoder((methodKey, response) -> {
            delegateCalls.incrementAndGet();
            return new IllegalStateException("delegate", null);
        }, JsonMapper.builder().build());

        Exception decoded = decoderWithSpy.decode(METHOD_KEY, responseWithUnreadableBody(500));

        assertThat(decoded).isInstanceOf(IllegalStateException.class);
        assertThat(delegateCalls).hasValue(1);
    }

    @Test
    void should_hand_the_delegate_a_replayable_body_whenever_there_was_one() {
        AtomicReference<String> observed = new AtomicReference<>("<未被读取>");
        ErrorDecoder measuring = (methodKey, response) -> {
            try {
                observed.set(new String(Util.toByteArray(response.body().asInputStream()),
                        StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            return new IllegalStateException("delegate");
        };
        String body = "{\"code\":\"ORDER_40001\",\"message\":\"参数不对\"}";
        MydddErrorDecoder spyDecoder = new MydddErrorDecoder(measuring, JsonMapper.builder().build());

        BizException decoded = (BizException) spyDecoder.decode(METHOD_KEY, streamResponse(400, body));

        assertThat(observed)
                .as("一次性流已被本类读干：不回灌字节则委托读到的永远是空体")
                .hasValue(body);
        assertThat(decoded.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void should_restore_the_envelope_that_the_kernel_response_actually_serializes() {
        org.deep361.myddd.kernel.common.response.Response<String> envelope =
                new org.deep361.myddd.kernel.common.response.Response<>(
                        "ORDER_40001", "参数不对", null, "0af7651916cd43dd8448eb211c80319c");
        String body = JsonMapper.builder().build().writeValueAsString(envelope);

        Exception decoded = decoder.decode(METHOD_KEY, streamResponse(502, body));

        assertThat(decoded).isInstanceOf(BizException.class);
        BizException biz = (BizException) decoded;
        RemoteErrorCode errorCode = (RemoteErrorCode) biz.getErrorCode();
        assertThat(errorCode.code()).isEqualTo(envelope.code());
        assertThat(biz.getMessage()).isEqualTo(envelope.message());
        assertThat(errorCode.remoteTraceId()).isEqualTo(envelope.traceId());
    }

    @Test
    void should_reject_null_collaborators() {
        assertThatThrownBy(() -> new MydddErrorDecoder((JsonMapper) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MydddErrorDecoder((ErrorDecoder) null, JsonMapper.builder().build()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MydddErrorDecoder(new ErrorDecoder.Default(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private static Response response(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("upstream said no")
                .request(request())
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    /**
     * 一次性流体质（真实容器给的就是这种：读一遍就干）。字节体夹具（{@link #response}）可重复读，
     * 用它测不出"回灌"这件事，也测不出 M108。
     */
    private static Response streamResponse(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return Response.builder()
                .status(status)
                .reason("upstream said no")
                .request(request())
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(new ByteArrayInputStream(bytes), bytes.length)
                .build();
    }

    private static Response responseWithoutBody(int status) {        return Response.builder().status(status).reason("upstream said no").request(request()).build();
    }

    private static Response responseWithUnreadableBody(int status) {
        return Response.builder()
                .status(status)
                .reason("upstream said no")
                .request(request())
                .body(new UnreadableBody())
                .build();
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.GET, "http://order-service/api/orders/1",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
    }

    /** 模拟"体在流上炸了"（连接被提前关闭、gzip 损坏等），而不是"根本没有体"。 */
    private static final class UnreadableBody implements Response.Body {

        @Override
        public Integer length() {
            return null;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public InputStream asInputStream() throws IOException {
            throw new IOException("connection reset while reading body");
        }

        @Override
        public Reader asReader(Charset charset) throws IOException {
            throw new IOException("connection reset while reading body");
        }

        @Override
        public void close() {
            // 流本来就没打开
        }
    }
}

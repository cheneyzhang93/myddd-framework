package org.deep361.myddd.feign.error;

import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.deep361.myddd.kernel.common.error.BizException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 把远程服务的统一错误体还原成本地可判别异常：对端返回 {@code {code, message, traceId}} 时抛
 * {@link BizException}（错误码为 {@link RemoteErrorCode}），否则交回 {@link ErrorDecoder.Default}。
 *
 * <p><b>为什么必须"读体再回灌"</b>：本类要先看体才能判定是不是我们的信封，而 {@code Response.Body} 的
 * 输入流是一次性的——读完再交给 {@code delegate} 时，{@code FeignException} 的消息里就没有响应体了
 * （表现为"异常在，内容空"，排障时最值钱的那一段丢了）。所以体先读成 {@code byte[]}，再用
 * {@code response.toBuilder().body(bytes)} 造一个可重放的响应交给 {@code delegate}。
 *
 * <p><b>三条刻意的宽松</b>，共同点是"猜错不如不猜"：
 * <ul>
 *   <li>体读不出来（{@link IOException}）、不是 JSON、不是 object、没有 {@code code}、{@code code} 为空白
 *       或不是字符串 —— 一律退回 {@code delegate}，得到 Feign 原生的 {@code FeignException}。对端可能是
 *       网关的 HTML 错误页、也可能是没接入本框架的老服务，这时保留原始异常比强行造一个错误码有用得多。</li>
 *   <li>{@code code} 只认字符串：我们的信封里 {@code code} 就是 {@code String}（形如 {@code ORDER_40001}），
 *       数字型的 {@code "code":500} 说明那不是我们的信封。</li>
 *   <li>解析用 {@code readTree} 而不是 {@code readValue(json, SomeRecord.class)}：绑定 POJO 会受 mapper
 *       配置影响——生产 mapper 开着 {@code FAIL_ON_UNKNOWN_PROPERTIES}（滚动发布期对端多出一个字段就会
 *       抛错），那等于让"对端加了个字段"这种事把错误还原打回 {@code FeignException}。{@code readTree}
 *       不做绑定，多余字段天然无害。</li>
 * </ul>
 *
 * <p><b>cause 链不丢</b>：{@link BizException#getCause()} 是 {@code delegate} 给出的原始
 * {@code FeignException}，所以状态码、响应头、原始体都还能从 cause 里查；日志与全局异常处理按
 * {@link BizException} 走语义化出口，两者各取所需。
 *
 * <p>不可变，可作为单例被多个 Feign 客户端共享。
 */
public class MydddErrorDecoder implements ErrorDecoder {

    private static final String CODE_FIELD = "code";
    private static final String MESSAGE_FIELD = "message";
    private static final String TRACE_ID_FIELD = "traceId";

    private final ErrorDecoder delegate;
    private final JsonMapper mapper;

    /**
     * 以 {@link ErrorDecoder.Default} 为兜底解码器。
     *
     * @param mapper 解析错误体用的 mapper，不可为 {@code null}
     * @throws NullPointerException {@code mapper} 为 {@code null}
     */
    public MydddErrorDecoder(JsonMapper mapper) {
        this(new ErrorDecoder.Default(), mapper);
    }

    /**
     * 完整构造：显式给出兜底解码器，便于业务在"不是我们的信封"这条分支上挂自己的逻辑。
     *
     * @param delegate 非信封响应交给它的解码器，不可为 {@code null}
     * @param mapper   解析错误体用的 mapper，不可为 {@code null}
     * @throws NullPointerException 任一参数为 {@code null}
     */
    public MydddErrorDecoder(ErrorDecoder delegate, JsonMapper mapper) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不能为 null");
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为 null");
    }

    @Override
    public Exception decode(String methodKey, Response response) {
        byte[] body = readBody(response);
        Response replayable = body == null ? response : response.toBuilder().body(body).build();
        Exception fallback = delegate.decode(methodKey, replayable);
        RemoteError remote = parse(body);
        return remote == null
                ? fallback
                : BizException.causedBy(new RemoteErrorCode(remote.code(), remote.message(),
                        response.status(), remote.traceId(), methodKey), fallback);
    }

    /** 一次性读出响应体；{@code null} 表示"没有体或体读不出来"，与"不是我们的信封"走同一条退路。 */
    private static byte[] readBody(Response response) {
        Response.Body body = response.body();
        if (body == null) {
            return null;
        }
        try {
            return Util.toByteArray(body.asInputStream());
        } catch (IOException failure) {
            return null;
        }
    }

    private RemoteError parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (JacksonException failure) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        String code = text(root.get(CODE_FIELD));
        if (code == null || code.isBlank()) {
            return null;
        }
        return new RemoteError(code, text(root.get(MESSAGE_FIELD)), text(root.get(TRACE_ID_FIELD)));
    }

    /** 只认字符串节点：缺失、{@code null} 值与别的 JSON 类型一律当"对端没给"。 */
    private static String text(JsonNode node) {
        return node == null || !node.isString() ? null : node.stringValue();
    }

    private record RemoteError(String code, String message, String traceId) {
    }
}

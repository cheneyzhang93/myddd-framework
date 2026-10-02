package org.deep361.myddd.web.trace;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * W3C Trace Context 的 {@code traceparent} 信头解析（只取 trace-id）。
 *
 * <p>格式：{@code version "-" trace-id "-" parent-id "-" flags ["-" vendor]}，各段小写十六进制，
 * 长度分别 2/32/16/2。规范里 {@code trace-id} 全零与 {@code version} 为 {@code ff} 都属非法值，
 * 上游若发来畸形值，本方法返回空而不是猜——调用方据此生成新 id。
 *
 * <p>刻意<b>不做</b>大小写宽容与前后空白裁剪：规范就是小写且无空格，宽容会把"上游实现不合规"这件事
 * 变成框架里的隐式修复。
 */
public final class W3cTraceParent {

    private static final Pattern LOWER_HEX = Pattern.compile("[0-9a-f]+");
    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_PARENT_ID = "0000000000000000";

    private W3cTraceParent() {
    }

    /** 解析出合法的 trace-id；信头缺失、格式不符或取值非法时返回空。 */
    public static Optional<String> traceId(String headerValue) {
        if (headerValue == null) {
            return Optional.empty();
        }
        // -1 保留尾部空段："00-id-0000000000000001-" 的 flags 是空串，必须当作非法而不是当成"只有三段"
        String[] fields = headerValue.split("-", -1);
        if (fields.length < 4 || !isLowerHex(fields[0], 2) || "ff".equals(fields[0])) {
            return Optional.empty();
        }
        String traceId = fields[1];
        if (!isLowerHex(traceId, 32) || ALL_ZERO_TRACE_ID.equals(traceId)) {
            return Optional.empty();
        }
        if (!isLowerHex(fields[2], 16) || ALL_ZERO_PARENT_ID.equals(fields[2])) {
            return Optional.empty();
        }
        return isLowerHex(fields[3], 2) ? Optional.of(traceId) : Optional.empty();
    }

    private static boolean isLowerHex(String value, int length) {
        return value.length() == length && LOWER_HEX.matcher(value).matches();
    }
}

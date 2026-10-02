package org.deep361.myddd.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.deep361.myddd.kernel.common.trace.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 链路边界：把入站 traceId 绑到当前线程、把最终 traceId 回写响应头，请求结束后归还上下文。
 *
 * <p>取值优先级：{@code X-Trace-Id}（框架统一信头）→ {@code traceparent}（与外部系统互通）→ 新生成。
 *
 * <p><b>入站信头按不可信输入处理</b>：只接受 {@code [A-Za-z0-9_-]{1,64}}，其余（超长、CRLF、空白、
 * 其它字符）一律丢弃并生成新值。这条校验同时挡住了响应拆分，也挡住了被污染的值进入日志与响应体。
 *
 * <p>线程上下文成对归还：进入时保存原值，退出时恢复；原值为 {@code null} 时清空。异步任务里的上下文
 * 传播不归本过滤器负责（内核 {@code TraceContext} javadoc 已把这条边界交给提交任务的一方）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 允许的 traceId 形态：可打印、无分隔符风险字符，且长度有界（防御超长头占用）。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String outerTraceId = TraceContext.currentTraceId();
        String traceId = resolveTraceId(request);
        TraceContext.set(traceId);
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            restore(outerTraceId);
        }
    }

    /** 三级取值，任何一级拿到非法值就继续往下走，最后兜底生成新 id。 */
    private static String resolveTraceId(HttpServletRequest request) {
        String headerTraceId = request.getHeader(TraceContext.TRACE_ID_HEADER);
        if (headerTraceId != null && SAFE_TRACE_ID.matcher(headerTraceId).matches()) {
            return headerTraceId;
        }
        return W3cTraceParent.traceId(request.getHeader("traceparent"))
                .filter(SAFE_TRACE_ID.asPredicate())
                .orElseGet(TraceContext::newTraceId);
    }

    private static void restore(String outerTraceId) {
        if (outerTraceId == null) {
            TraceContext.clear();
        } else {
            TraceContext.set(outerTraceId);
        }
    }
}

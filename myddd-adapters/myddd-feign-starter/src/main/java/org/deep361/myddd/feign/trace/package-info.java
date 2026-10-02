/**
 * 链路 traceId 的出站边界：把当前线程的 traceId 写进 Feign 请求信头。
 *
 * <p>与 web-starter 的 {@code TraceIdFilter}（入站）是一对：入站负责"从别人的信头里取出可信的 id"，
 * 出站负责"把自己的 id 交出去"。两边各自实现、不互相依赖（适配器模块禁止跨 starter 依赖），
 * 共享的只有内核的 {@code TraceContext#TRACE_ID_HEADER} 常量与 W3C 格式本身。
 */
package org.deep361.myddd.feign.trace;

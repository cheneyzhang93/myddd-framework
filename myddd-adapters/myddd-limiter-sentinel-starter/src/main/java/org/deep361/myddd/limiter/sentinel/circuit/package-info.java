/**
 * 熔断端口的 Sentinel 实现。
 *
 * <p>厂商入口是 {@code SphU}：熔断要求"动作执行完了、成功还是失败"被记进统计，那个"正在进行"的
 * 载体就是 {@code Entry}。两条不能改的顺序：{@code Tracer.trace(failure)} 必须在
 * {@code entry.close()} 之前（写反的形态是全部调用照常放行、熔断永不判定失败——实测同一份规则
 * 两次读数只差最后一个词）；{@code catch} 的是 {@code RuntimeException | Error} 而不是
 * {@code Exception}（{@code AssertionError} 也必须记账）。
 *
 * <p>只有厂商的 {@code BlockException} 翻译成 {@code BizException(SERVICE_DEGRADED)}（503）；
 * 业务自身的异常原样上抛。因为资源名带 {@code myddd:circuit:} 前缀，这一个资源上只可能存在
 * degrade 规则，所以不需要按拒绝原因分子类分叉。
 *
 * <p>开闸时长由规则文件的 {@code openSeconds} 决定，半开探测的放行时机不暴露成读口：
 * 本框架没有"这个熔断器现在什么状态"的端口方法（§0.4 冻结面），可见性归 Task 47 的管理面。
 */
package org.deep361.myddd.limiter.sentinel.circuit;

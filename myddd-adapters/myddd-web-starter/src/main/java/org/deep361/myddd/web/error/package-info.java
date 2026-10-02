/**
 * 全局异常分派与统一响应出口。
 *
 * <p><b>对外文案有两个来源，优先序固定</b>：① 异常自带的非空白文案（四个注解的 {@code message()}
 * 经由 {@code BizException} 子类覆写 {@code getMessage()} 携带过来）；② {@code ErrorMessageRenderer}
 * 的四级链（业务消息源 → 框架内置 bundle @ 请求语言 → 框架内置 bundle @ 兜底语言 → 错误码自带文案）。
 * ①存在且不等于错误码缺省渲染时压过②，判据单点在 {@code AnnotationCarriedMessage}，本包只有
 * {@code handleBizException} 一处读它（R12）。
 *
 * <p><b>选①意味着放弃多语言</b>：注解文案是业务手写的话术，原样输出、不进消息源、不交 {@code MessageFormat}。
 * 需要按语言切换的文案请走 i18n 键而不是 {@code message()}——两个都要就得在业务侧按语言取串再拼注解，
 * 而注解值是编译期常量，那条路不存在。
 *
 * <p><b>本包不认识任何能力模块的拒绝类型</b>：优先序的判据是"携带了与错误码缺省不同的非空白文案"这一
 * 结构事实，不是 {@code instanceof} 某一家。{@code FrameworkException}、{@code HttpMessageNotReadableException}
 * 等技术异常一律不进这条优先序（它们的 {@code message} 是给开发者看的，可能含连接串与请求体片段，
 * 对外只有各自的错误码文案）。
 *
 * <p><b>响应只有一个装配点</b>：{@code GlobalExceptionHandler#renderedResponse}。任何 handler 自己
 * {@code new Response<>(...)} 或自己 {@code ResponseEntity.status(...)} 都算破坏这条纪律，
 * 状态码分派（按 {@code errorCode.httpStatus()} 通用分派）与 traceId 自取都挂在那一处。
 */
package org.deep361.myddd.web.error;

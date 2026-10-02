/**
 * 远程统一错误体的还原：把对端返回的 {@code {code, message, traceId}} 变成携带原始错误码的
 * {@link org.deep361.myddd.kernel.common.error.BizException}。
 */
package org.deep361.myddd.feign.error;

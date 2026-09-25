package org.deep361.myddd.kernel.port.consistency;

import java.time.Duration;

/**
 * 幂等端口：由适配器实现（Plan 3：consistency-starter，Redis / DB 双实现，配置
 * {@code myddd.consistency.idempotent.store=redis|db} 切换）。
 *
 * <p><b>语义</b>：{@code tryAcquire} 是原子的"占位"操作——返回 {@code true} 表示首次请求、可继续执行；
 * {@code false} 表示重复请求，切面据此抛 {@code BizException(CommonErrorCode.IDEMPOTENT_CONFLICT)}（HTTP 409）。
 *
 * <p><b>实现契约</b>：{@code key} 不可为空白（违反抛 {@link IllegalArgumentException}）；实现须线程安全、
 * 且在分布式部署下互斥（跨实例）。
 */
public interface IdempotentPort {

    /**
     * 尝试占位。
     *
     * <p><b>原子性</b>：实现必须原子地完成"占位 + 绑定 token"（Redis 用 {@code SET key token NX PX}，
     * DB 用唯一键插入），token 由调用方（切面）为本次请求生成，实现只做存储与比对。
     *
     * @param key   幂等键，不可为空白
     * @param token 本次请求的占位凭据，不可为空白；释放时必须回传同一个值
     * @param ttl   占位存活时长，必须为正
     * @return {@code true} 首次请求；{@code false} 重复请求
     */
    boolean tryAcquire(String key, String token, Duration ttl);

    /**
     * 释放占位：业务方法失败且允许重试时由切面调用（对应
     * {@code @Idempotent(releaseOnError = true)}）。
     *
     * <p><b>实现契约</b>：仅当 {@code key} 上存放的 token 与传入 {@code token} 一致时才删除
     * （原子比对+删除）；token 不一致或占位不存在时视为空操作，不得抛异常。若动作超时后占位已被其他请求
     * 重新获取，无条件删除会误删他人占位、破坏幂等。
     */
    void release(String key, String token);
}

package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;
import java.util.List;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link IdempotentPort} 的 Redis 实现（{@code myddd.consistency.idempotent.store=redis}，缺省）。
 *
 * <p><b>两条 Redis 命令各有各的原子性义务，这是本类的设计核心</b>：
 * <ul>
 *   <li>{@code SET key token NX PX ttl}（{@code ValueOperations.setIfAbsent(key, token, ttl)}）：
 *       “占位 + 绑定 token + 设 TTL”必须由<b>一条</b>命令完成。拆开成 {@code SETNX} 再 {@code PSETEX} 会得到
 *       “占位成功但进程在两步之间崩溃 → 键永不过期”，那把键就是<b>永久拒绝</b>——业务坐标一旦被占，
 *       重试到永远。返回 {@code true} 才是首次请求。</li>
 *   <li>释放用 Lua 做“比对 token 再删”（{@link #RELEASE_SCRIPT}）：端口契约写明“token 不一致或占位不存在时
 *       空操作"，因为动作跑得太慢时占位已过期、<b>别的请求已经重新占上</b>，此时无条件 {@code DEL}
 *       会删掉别人的占位，让第三个请求畅通无阻——幂等被静默击穿，而且只在慢请求上出现。</li>
 * </ul>
 *
 * <p><b>本类不生成 token、不判重、不抛业务异常</b>：token 由切面为每次调用生成，重复请求的
 * {@code IDEMPOTENT_CONFLICT}（HTTP 409）也由切面抛出。这里的职责只有存储与两条命令的原子性，
 * 因此它可以被 {@code @Idempotent} 之外的场景（比如业务手写的分布式一次性标志）安全复用。
 *
 * <p><b>键名 = {@code keyPrefix + ":" + key}，不做任何规范化</b>：与 {@code DefaultLockPort} 同一条纪律。
 * 幂等记录常被 {@code redis-cli --scan --pattern 'myddd:idempotent:*'} 翻出来对账，任何加工都会让
 * “扫一眼键”变成“先猜框架改了什么”，而 Redis 侧与 DB 侧必须产出同一个键（R2）的前提就是没人加工它。
 *
 * <p><b>参数校验的分工</b>：空白键/空白 token/非正 TTL 抛 {@link IllegalArgumentException}（端口 javadoc
 * 定的是这一类型，因为它们都是<b>调用方接线错</b>，不是运行时条件）。规则与话术都在
 * {@link IdempotentStoreGuards}，与 DB 侧同源——两种存储对同一次非法调用必须给出同一个答案，
 * 而等价性用例（Task 27）就是拿这一点比对的。{@code release} 同样先校验再删——
 * “不得抛异常”那句约束的是 token 不一致与占位不存在这两种运行时结果，不是替调用方掩盖传错参数。
 *
 * <p><b>没有本地缓存、没有 {@code exists} 预判</b>：任何“先看一眼再决定”都在两步之间开了一个竞态窗口，
 * 而幂等的全部价值就在那个窗口里。
 */
public class RedisIdempotentStore implements IdempotentPort {

    /**
     * 按 token 释放：{@code GET} 与 {@code DEL} 在一个脚本里跑，Redis 保证脚本原子执行。
     *
     * <p>返回被删除的键数（0 或 1），本类按端口契约<b>不</b>使用它——不一致时“什么都没删”就是正确结果，
     * 不是错误。
     */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public RedisIdempotentStore(StringRedisTemplate redis, MydddConsistencyProperties properties) {
        if (redis == null) {
            throw new IllegalArgumentException("StringRedisTemplate 不能为 null：幂等端口没有存储后端"
                    + "就无法判定首次还是重复");
        }
        if (properties == null) {
            throw new IllegalArgumentException("MydddConsistencyProperties 不能为 null：幂等键前缀是它的职责");
        }
        this.redis = redis;
        this.keyPrefix = properties.getIdempotent().getKeyPrefix();
    }

    @Override
    public boolean tryAcquire(String key, String token, Duration ttl) {
        IdempotentStoreGuards.requireKey(key);
        IdempotentStoreGuards.requireToken(token);
        IdempotentStoreGuards.requireTtl(ttl);
        Boolean acquired = this.redis.opsForValue().setIfAbsent(idempotentKey(key), token, ttl);
        if (acquired == null) {
            // 一手实测的 API 形状：setIfAbsent 的返回类型是 Boolean（不是 boolean），流水线/事务模式下
            // RedisTemplate 给不出即时结果就是这个 null。这里既不能当首次（击穿幂等）也不能当重复
            // （把正常请求判成 409），所以报错而不是猜。
            throw new FrameworkException("Redis 没有给出 setIfAbsent 的即时结果（返回 null）：这通常意味着这次调用"
                    + "跑在 executePipelined / MULTI 里，而幂等占位必须知道“我是不是第一个”。"
                    + "请把 IdempotentPort 的调用移出流水线，或者改用 myddd.consistency.idempotent.store=db");
        }
        return acquired;
    }

    @Override
    public void release(String key, String token) {
        IdempotentStoreGuards.requireKey(key);
        IdempotentStoreGuards.requireToken(token);
        this.redis.execute(RELEASE_SCRIPT, List.of(idempotentKey(key)), (Object) token);
    }

    /**
     * Redis 里的幂等键名：{@code keyPrefix + “:” + key}。
     *
     * <p>公开是为了让”键名到底长什么样”可被断言（用例与真 Redis 对账都取这里的证据），
     * 而不必靠猜前缀拼接顺序。DB 侧的 {@link JdbcIdempotentStore#idempotentKey(String)} 走同一个
     * {@link IdempotentStoreGuards#prefixedKey}，所以同一次调用在两种存储里得到同一个串。
     */
    public String idempotentKey(String key) {
        return IdempotentStoreGuards.prefixedKey(this.keyPrefix, key);
    }
}

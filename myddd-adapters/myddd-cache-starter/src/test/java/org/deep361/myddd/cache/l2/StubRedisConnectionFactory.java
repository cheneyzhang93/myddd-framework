package org.deep361.myddd.cache.l2;

import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConnection;

/**
 * 不连网的 {@link RedisConnectionFactory} 测试替身：只让 {@code RedisCacheManager} 能被<b>构造出来</b>，
 * 任何真的键空间操作都立刻失败。
 *
 * <p>为什么需要它：{@code myddd.cache.type=multi} 的装配面（有没有 bean、有几个 bean、配置键有没有落进两层）
 * 在没有 Docker 的机器上也要能跑（§0.3-7）。真 Redis 的证据在 {@code CacheRedisIT}。
 *
 * <p>报错文案里带一句可辨认的暗号：用例失败时从异常就能看出"是哪一步去连了网"，
 * 而不是一个笼统的 {@code NullPointerException}。
 */
public final class StubRedisConnectionFactory implements RedisConnectionFactory {

    /** 失败信息里的暗号：出现在测试输出里就代表装配过程试图建立连接，那已超出本替身能覆盖的范围。 */
    public static final String MARKER = "STUB-REDIS-CONNECTION-USED";

    @Override
    public RedisConnection getConnection() {
        throw stubbed();
    }

    @Override
    public RedisClusterConnection getClusterConnection() {
        throw stubbed();
    }

    @Override
    public boolean getConvertPipelineAndTxResults() {
        return false;
    }

    @Override
    public RedisSentinelConnection getSentinelConnection() {
        throw stubbed();
    }

    /** 本替身从不建立连接，也就没有方言异常要翻译。 */
    @Override
    public org.springframework.dao.DataAccessException translateExceptionIfPossible(RuntimeException ex) {
        return null;
    }

    private static UnsupportedOperationException stubbed() {
        return new UnsupportedOperationException(MARKER
                + "：装配期不该触碰 Redis，请检查是哪一步要建连接（真 Redis 的验收在 CacheRedisIT）");
    }
}

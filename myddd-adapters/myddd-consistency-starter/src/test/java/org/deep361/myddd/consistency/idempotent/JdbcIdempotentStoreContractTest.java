package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;
import java.time.Instant;

import org.deep361.myddd.consistency.autoconfigure.MydddConsistencyProperties;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;

/**
 * 等价性契约（{@link AbstractIdempotentPortContractTest}）在 <b>{@code store=db} 这一支</b>上的执行：
 * H2(MySQL 模式) + 交付 DDL 的原文副本 + 真 SQL，离线可跑。
 *
 * <p>本类只回答基类留下的那两个"这一种存储里怎么观察"的问题：占位就是表里的一行，
 * 时间就是 {@link MutableClock} 上的一个刻度。判定语句（<b>几条</b>、<b>什么谓词</b>）全在基类，
 * 与真 Redis 那份子类共用同一份代码——这正是 Task 27 要交付的东西。
 *
 * <p>与 {@code IdempotentRedisIT} 的分工：那边跑同一组用例但需要 Docker（无 Docker 时整类跳过），
 * 所以本类是这七组断言<b>唯一有实测执行证据</b>的一半。
 */
class JdbcIdempotentStoreContractTest extends AbstractIdempotentPortContractTest {

    /** 整毫秒的起点：{@code datetime(3)} 是毫秒精度，测试数据不靠四舍五入活着。 */
    private static final Instant T0 = Instant.ofEpochMilli(1_700_000_000_000L);

    private final IdempotentH2 db = IdempotentH2.open();
    private final MutableClock clock = new MutableClock(T0);
    private final IdempotentPort port;

    JdbcIdempotentStoreContractTest() {
        MydddConsistencyProperties properties = new MydddConsistencyProperties();
        properties.getIdempotent().setKeyPrefix(configuredKeyPrefix());
        this.port = new JdbcIdempotentStore(this.db.dataSource(), properties,
                IdempotentH2.REFERENCE_TABLE, this.clock);
    }

    @Override
    protected IdempotentPort port() {
        return this.port;
    }

    @Override
    protected String configuredKeyPrefix() {
        return "myddd:contract:db";
    }

    @Override
    protected String physicalKeyOf(String key) {
        return ((JdbcIdempotentStore) this.port).idempotentKey(key);
    }

    @Override
    protected boolean placeholderExists(String physicalKey) {
        return this.db.hasRow(physicalKey);
    }

    @Override
    protected String tokenStoredAt(String physicalKey) {
        return this.db.tokenOf(physicalKey);
    }

    @Override
    protected void letTimePassBeyond(Duration span) {
        // 多拨 1 毫秒：到期判定用的是 expires_at <= now，正好落在到期那一刻也算到期，
        // 但用例要的是"确实过去了"，所以不押边界。
        this.clock.advance(span.plusMillis(1));
    }

    @Override
    protected void clearPlaceholders() {
        this.db.deleteAll();
    }
}

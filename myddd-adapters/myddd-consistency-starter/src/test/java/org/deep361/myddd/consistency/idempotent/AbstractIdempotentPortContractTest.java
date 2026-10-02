package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等占位存储的<b>等价性契约</b>：{@code RedisIdempotentStore}（Task 26）与
 * {@code JdbcIdempotentStore}（Task 27）必须对这七组问题给出同一个答案
 * （计划文档 §0.4 裁定 6：同一能力的双实现过同一组用例）。
 *
 * <p><b>为什么必须共享一个基类而不是两份手写用例</b>：换存储是<b>一个配置字</b>
 * （{@code myddd.consistency.idempotent.store=redis|db}）决定的，而两份各自演进的用例永远会漂移——
 * 漂移的症状不是测试红，而是"同一件事在 Redis 里算重复、在 DB 里算首次"，也就是幂等被静默击穿。
 * 子类只负责回答"这一种存储里，占位长什么样、时间怎么走"，判定全部在本类。
 *
 * <p><b>本类覆盖到的两种存储分别是谁</b>：
 * <ul>
 *   <li>{@code JdbcIdempotentStoreContractTest} —— H2(MySQL 模式) 上的真 SQL，离线可跑（surefire）；</li>
 *   <li>{@code IdempotentRedisIT} —— 真 Redis 容器（failsafe，无 Docker 时整类跳过）。</li>
 * </ul>
 * 两者跑的是下面一模一样的方法体，所以"离线全绿"这一句话对 DB 支路是完整的，对 Redis 支路是
 * "能干净跳过"（§0.3-7 的口径）。
 *
 * <p><b>刻意不在本类里的</b>：各存储自己的守卫与形状（Redis 的 Lua 与 pipeline 空值、DB 的表名/列宽/
 * 清理与语法提示），那些进不了等价性口径，留在各自的用例类里。
 */
public abstract class AbstractIdempotentPortContractTest {

    /**
     * 占位存活时长：所有用例统一取这一个值，到期由 {@link #letTimePassBeyond} 明确制造。
     *
     * <p>取 2 秒而不是 60 秒只有一个理由：Redis 那一半子类没有钟可拨，"让时间过去"就是真的等，
     * 而 {@code EQ6} 每跑一次就要等满这个时长。DB 侧无所谓（拨钟），所以约束来自慢的那一边。
     */
    protected static final Duration TTL = Duration.ofSeconds(2);

    /** 只服务 {@link #should_let_exactly_one_of_two_concurrent_claims_win} 与子类的声明式用例。 */
    protected final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** 被测端口。 */
    protected abstract IdempotentPort port();

    /** 本上下文配置的键前缀段（{@code myddd.consistency.idempotent.key-prefix}）。 */
    protected abstract String configuredKeyPrefix();

    /** 逻辑键对应的物理键（Redis 里那条键 / 表里 {@code idem_key} 的值）。 */
    protected abstract String physicalKeyOf(String key);

    /** 存储里此刻是否真有这一条占位（与"是否算有效占位"是两件事：过期的行在 DB 里仍然在）。 */
    protected abstract boolean placeholderExists(String physicalKey);

    /** 占位当前绑定的 token；不存在时返回 {@code null}。 */
    protected abstract String tokenStoredAt(String physicalKey);

    /** 让"从占位那一刻起 {@code span} 这段时间"真的过去：Redis 侧等，DB 侧拨钟。 */
    protected abstract void letTimePassBeyond(Duration span);

    /** 用例之间的隔离：清掉本上下文前缀下的全部占位。 */
    protected abstract void clearPlaceholders();

    @BeforeEach
    final void wipePlaceholders() {
        clearPlaceholders();
    }

    @AfterEach
    final void shutDownPool() {
        this.pool.shutdownNow();
    }

    /**
     * EQ1 首次占位成功、重复占位被拒，而且<b>被拒不改变原主人的 token</b>。
     *
     * <p>后半个断言是幂等最容易被写坏的地方：一种"看起来也对"的实现是占位冲突时顺手把 token 换成新的
     * （比如为了"让后来者能释放"），那会让第一次请求的 {@code releaseOnError} 释放掉第二次请求的占位。
     */
    @Test
    void should_acquire_once_and_refuse_a_second_claim_without_touching_the_owner() {
        assertThat(port().tryAcquire("claim:1", "token-a", TTL)).isTrue();
        assertThat(placeholderExists(physicalKeyOf("claim:1"))).isTrue();

        assertThat(port().tryAcquire("claim:1", "token-b", TTL)).isFalse();
        assertThat(tokenStoredAt(physicalKeyOf("claim:1"))).isEqualTo("token-a");
    }

    /**
     * EQ2 按 token 释放：<b>拿着别人 token 的释放必须无事发生</b>，真主人随后还能正常释放。
     *
     * <p>端口契约把这一条写成"token 不一致时空操作、不得抛异常"，理由在这里：动作跑得太慢时占位已过期、
     * 别的请求重新占了上来，此时无条件删除会让第三个请求畅通无阻，而它只在慢请求上出现。
     */
    @Test
    void should_release_only_for_the_token_that_owns_the_placeholder() {
        assertThat(port().tryAcquire("release:1", "token-a", TTL)).isTrue();

        port().release("release:1", "token-b");
        assertThat(placeholderExists(physicalKeyOf("release:1"))).isTrue();

        port().release("release:1", "token-a");
        assertThat(placeholderExists(physicalKeyOf("release:1"))).isFalse();
        assertThat(port().tryAcquire("release:1", "token-c", TTL)).isTrue();
    }

    /** EQ3 释放一条从来没占过的键：空操作，不抛异常（切面失败重试路径会走到这里）。 */
    @Test
    void should_treat_releasing_an_absent_placeholder_as_a_no_op() {
        assertThatCode(() -> port().release("absent:1", "token-a")).doesNotThrowAnyException();
        assertThat(placeholderExists(physicalKeyOf("absent:1"))).isFalse();

        // 同一条占位释放两次也是空操作：第二次不该把"刚刚被别的请求重新占上"的那条删掉。
        assertThat(port().tryAcquire("twice:1", "token-a", TTL)).isTrue();
        port().release("twice:1", "token-a");
        assertThat(port().tryAcquire("twice:1", "token-b", TTL)).isTrue();
        assertThatCode(() -> port().release("twice:1", "token-a")).doesNotThrowAnyException();
        assertThat(tokenStoredAt(physicalKeyOf("twice:1"))).isEqualTo("token-b");
    }

    /**
     * EQ4 非法入参：两种存储必须抛<b>同一个类型的同一个话术</b>。
     *
     * <p>这是等价性最硬的一条：接线错（空白键、空白 token、非正 ttl）如果只在一种存储里报出来，
     * 换一次存储就等于换一套契约。类型是 {@link IllegalArgumentException}（端口 javadoc 定的），
     * 不是 {@code BizException}，因为它不是运行时条件而是调用方写错了。
     */
    @Test
    void should_reject_the_same_illegal_arguments_with_the_same_words() {
        for (String blank : List.of("", " ", "\t")) {
            assertThatThrownBy(() -> port().tryAcquire(blank, "token-a", TTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等键");
            assertThatThrownBy(() -> port().release(blank, "token-a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等键");
        }
        assertThatThrownBy(() -> port().tryAcquire(null, "token-a", TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幂等键");
        for (Duration illegal : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> port().tryAcquire("guard:1", "token-a", illegal))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ttl 必须为正");
        }
        assertThatThrownBy(() -> port().tryAcquire("guard:1", "token-a", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl 必须为正");
        for (String blankToken : List.of("", " ")) {
            assertThatThrownBy(() -> port().tryAcquire("guard:2", blankToken, TTL))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("token");
            assertThatThrownBy(() -> port().release("guard:2", blankToken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("token");
        }
        assertThatThrownBy(() -> port().tryAcquire("guard:3", null, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token");

        // 守卫必须是"什么都不写"：非法 ttl 之后同一条键还能正常占上。
        assertThat(port().tryAcquire("guard:3", "token-a", TTL)).isTrue();
    }

    /**
     * EQ5 两个进程抢同一条键，<b>恰好一个拿到占位</b>。
     *
     * <p>会合点放在抢之前：两个线程同时到起跑线，才把"同时占位"这件事真的发生出来。Redis 侧靠
     * {@code SET NX}，DB 侧靠主键冲突；这条用例是两种原子性唯一的共同验收点。
     */
    @Test
    void should_let_exactly_one_of_two_concurrent_claims_win() throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        Callable<Boolean> claim = () -> {
            startLine.await(20, TimeUnit.SECONDS);
            return port().tryAcquire("race:1", UUID.randomUUID().toString(), TTL);
        };

        Future<Boolean> first = this.pool.submit(claim);
        Future<Boolean> second = this.pool.submit(claim);

        assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(placeholderExists(physicalKeyOf("race:1"))).isTrue();
    }

    /**
     * EQ6 占位到点以后同一条键<b>可以再被占上</b>（Redis 靠 TTL 自己消失，DB 靠"过期行视为不存在"）。
     *
     * <p>端口 javadoc 那句"DB 实现须把过期行视为占位不存在，与 Redis 的 TTL 语义保持一致"就是本条。
     * 断言里刻意不带"存储里那一行已经不在了"——DB 的行还在（清理是另一件事），关键是它不再挡路。
     */
    @Test
    void should_let_the_same_key_be_claimed_again_once_the_placeholder_expired() {
        assertThat(port().tryAcquire("expiring:1", "token-a", TTL)).isTrue();
        assertThat(port().tryAcquire("expiring:1", "token-b", TTL)).isFalse();

        letTimePassBeyond(TTL);

        assertThat(port().tryAcquire("expiring:1", "token-b", TTL)).isTrue();
        assertThat(tokenStoredAt(physicalKeyOf("expiring:1"))).isEqualTo("token-b");
    }

    /**
     * EQ7 物理键就是 {@code keyPrefix + ":" + key}，<b>没有任何别的加工</b>。
     *
     * <p>换存储不能换键空间：R2 的缺省键派生（{@code IdempotentKeys}）本来就为"两种存储拿到同一个串"
     * 而存在，而这一条钉的是派生之后的那一步也没被加工。物理键形状由本条 + 两个子类的实现共同给出。
     */
    @Test
    void should_use_the_prefixed_key_verbatim_as_the_physical_key() {
        assertThat(physicalKeyOf("order:1")).isEqualTo(configuredKeyPrefix() + ":order:1");
        assertThat(physicalKeyOf("a:b:c")).isEqualTo(configuredKeyPrefix() + ":a:b:c");

        assertThat(port().tryAcquire("nested:key:1", "token-a", TTL)).isTrue();
        assertThat(placeholderExists(configuredKeyPrefix() + ":nested:key:1")).isTrue();
    }

    /**
     * EQ8 <b>同一个 token</b> 在占位有效期内重复 {@code tryAcquire} 仍然是"重复请求"。
     *
     * <p>切面每次调用都生成新 UUID，所以这条不会从注解路径走到；它钉的是存储侧的不变式：
     * "到期之前谁都不能再占，包括上一个主人"。少了这条，实现可以写成"token 相同就当续期成功"，
     * 于是重试与首次不可区分，幂等只剩一半。
     */
    @Test
    void should_refuse_a_repeated_claim_even_when_it_carries_the_same_token() {
        assertThat(port().tryAcquire("same:1", "token-a", TTL)).isTrue();
        assertThat(port().tryAcquire("same:1", "token-a", TTL)).isFalse();
        assertThat(placeholderExists(physicalKeyOf("same:1"))).isTrue();
    }
}

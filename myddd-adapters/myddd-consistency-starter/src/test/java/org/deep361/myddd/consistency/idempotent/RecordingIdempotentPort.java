package org.deep361.myddd.consistency.idempotent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.deep361.myddd.kernel.port.consistency.IdempotentPort;

/**
 * 记录型 {@link IdempotentPort}：<b>照端口契约实现真占位语义</b>，而不是只把调用抄进日志。
 *
 * <p>为什么带状态：切面最容易写错的两件事——"重复请求的判据"和"释放用的是哪个 token"——都需要端口能回答
 * "这条占位现在还在不在、属主是谁"。纯记录型端口只能证明"端口被叫过一次"，证明不了"叫对了一次"：
 * 例如把 token 每次重新生成，记录上看是"acquire 与 release 都发生了"，而真端口会因为比对不上而什么都不删。
 * 这里的 {@link #holds(String)} 与 {@link #ownerOf(String)} 就是那条断言的落点。
 *
 * <p>三条端口契约逐条落实（{@code IdempotentPort} javadoc）：① {@code tryAcquire} 原子地"占位 + 绑定 token"，
 * 已有占位则返回 {@code false}；② {@code release} 只在 token 一致时删除，不一致或不存在时<b>空操作、不抛异常</b>；
 * ③ 空白键/空白 token/非正 TTL 抛 {@link IllegalArgumentException}。第③条保留的原因与锁侧同一条纪律：
 * 切面<b>不该</b>自己加工键，空白值的拒绝必须由端口发出，用例才分得出"谁在拒绝"。
 *
 * <p>与 {@code RecordingLockPort} 一样支持<b>共享</b>有序日志：幂等排在锁与事务之外这条顺序判据，
 * 只有把三份证据写在同一条时间轴上才取得出来。
 */
public class RecordingIdempotentPort implements IdempotentPort {

    /** 一次 {@code tryAcquire} 的入参，切面算出来的东西全在这三个字段里。 */
    public record Acquisition(String key, String token, Duration ttl) {
    }

    /** 一次 {@code release} 的入参：键 + 声称是属主的 token。 */
    public record Release(String key, String token) {
    }

    private final List<String> calls;
    private final List<Acquisition> acquisitions = new ArrayList<>();
    private final List<Release> releases = new ArrayList<>();
    private final Map<String, String> placeholders = new HashMap<>();

    private boolean rejectBlankArguments = true;
    private RuntimeException onFailure;

    public RecordingIdempotentPort() {
        this(new ArrayList<>());
    }

    /** 往共享时间轴上写 {@code idempotent-acquire:}/{@code idempotent-release:} 两笔。 */
    public RecordingIdempotentPort(List<String> sharedLog) {
        this.calls = sharedLog;
    }

    /** 关掉空白拒绝：用于"端口放行空白键"的反面取证（默认开着，因为真端口就是开着的）。 */
    public RecordingIdempotentPort withoutBlankArgumentRejection() {
        this.rejectBlankArguments = false;
        return this;
    }

    /** 让下一次占位直接抛这个异常（端口自己出错，与"重复请求返回 false"是两回事）。 */
    public RecordingIdempotentPort throwingOnAcquire(RuntimeException failure) {
        this.onFailure = failure;
        return this;
    }

    public List<String> calls() {
        return List.copyOf(this.calls);
    }

    public List<Acquisition> acquisitions() {
        return List.copyOf(this.acquisitions);
    }

    public List<Release> releases() {
        return List.copyOf(this.releases);
    }

    public Acquisition lastAcquisition() {
        return this.acquisitions.get(this.acquisitions.size() - 1);
    }

    /** 这条键上现在有没有占位。 */
    public boolean holds(String key) {
        return this.placeholders.containsKey(key);
    }

    /** 这条键上占位的属主 token（没有占位时为 {@code null}）。 */
    public String ownerOf(String key) {
        return this.placeholders.get(key);
    }

    @Override
    public boolean tryAcquire(String key, String token, Duration ttl) {
        requireArguments(key, token);
        if (this.rejectBlankArguments && (ttl == null || ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("ttl 必须为正（与 RedisIdempotentStore 同一条拒绝，收到 " + ttl + "）");
        }
        this.calls.add("idempotent-acquire:" + key);
        this.acquisitions.add(new Acquisition(key, token, ttl));
        if (this.onFailure != null) {
            throw this.onFailure;
        }
        if (this.placeholders.containsKey(key)) {
            return false;
        }
        this.placeholders.put(key, token);
        return true;
    }

    @Override
    public void release(String key, String token) {
        requireArguments(key, token);
        this.calls.add("idempotent-release:" + key);
        this.releases.add(new Release(key, token));
        if (token.equals(this.placeholders.get(key))) {
            this.placeholders.remove(key);
        }
        // token 不一致或占位不存在：空操作，按端口契约不得抛异常。
    }

    private void requireArguments(String key, String token) {
        if (!this.rejectBlankArguments) {
            return;
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("幂等键空白（与 RedisIdempotentStore 同一条拒绝）");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("幂等 token 空白（与 RedisIdempotentStore 同一条拒绝）");
        }
    }
}

package org.deep361.myddd.resiliencefixture;

import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;

/**
 * 限流端口的替身：按内核 javadoc 的实现契约自己拒空白资源名。
 *
 * <p>这一句"替身也要守契约"是 {@code RS9} 能成立的唯一条件——它要的判据是"空白串被<b>端口</b>拒绝
 * 而不是被切面拒绝"，异常类型 {@link IllegalArgumentException} 就是"谁拒的"的物证。
 * 替身若放行空白串，那条用例就退化成"切面没报错"，什么也没钉住。
 */
public final class RecordingRateLimitPort implements RateLimitPort {

    /** 每次调用的 {@code 资源名/许可数}，顺序即调用顺序。 */
    public final List<String> calls = new ArrayList<>();

    /** 第几次调用起放行（1 起）；0 表示一条都不放。 */
    public int grantFromCall = 1;

    public RecordingRateLimitPort neverGrant() {
        this.grantFromCall = 0;
        return this;
    }

    public RecordingRateLimitPort grantAfter(int refusals) {
        this.grantFromCall = refusals + 1;
        return this;
    }

    @Override
    public boolean tryAcquire(String resource, int permits) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("RateLimitPort 的实现契约：resource 不可为空白，拿到 ["
                    + resource + "]");
        }
        if (permits < 1) {
            throw new IllegalArgumentException("RateLimitPort 的实现契约：permits 必须 ≥ 1，拿到 " + permits);
        }
        this.calls.add(resource + "/" + permits);
        return this.grantFromCall > 0 && this.calls.size() >= this.grantFromCall;
    }

    /** 只看资源名那一半。 */
    public List<String> resources() {
        return this.calls.stream().map(each -> each.substring(0, each.lastIndexOf('/'))).toList();
    }
}

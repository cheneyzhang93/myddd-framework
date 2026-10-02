package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.port.resilience.RateLimitPort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link RateLimitPortContractTestBase} 的进程内夹具（Task 32 Step 13 要求的配套交付物）。
 *
 * <p>{@code awaitFreshWindow()} 把"已用"归零，这就是"窗口翻篇"的等价形状；两个真 provider 各自用真窗口实现
 * 同一个缝，基类因此不必睡上十秒。
 */
class RateLimitPortFakeContractTest extends RateLimitPortContractTestBase {

    /** 资源名 → {@code [额度, 已用]}。 */
    private final Map<String, int[]> budgets = new LinkedHashMap<>();

    private final RateLimitPort port = this::tryAcquire;

    private boolean tryAcquire(String resource, int permits) {
        // 参数校验必须先于规则查询：否则 RL-C6 的"已下发规则后再喂非法值"会退化成命中"资源不存在"那条退路
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource 不能为 null 或空白");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("permits 必须为正数，实际：" + permits);
        }
        int[] budget = budgets.get(resource);
        if (budget == null) {
            // "未配置规则的资源怎么办"不在端口契约里，夹具取"放行"，免得基类多出一条没人承诺的断言
            return true;
        }
        if (budget[1] + permits > budget[0]) {
            return false;
        }
        budget[1] += permits;
        return true;
    }

    @Override
    protected RateLimitPort rateLimitPort() {
        return port;
    }

    @Override
    protected void configure(String resource, int permits) {
        budgets.put(resource, new int[]{permits, 0});
    }

    @Override
    protected void awaitFreshWindow() {
        budgets.values().forEach(budget -> budget[1] = 0);
    }
}

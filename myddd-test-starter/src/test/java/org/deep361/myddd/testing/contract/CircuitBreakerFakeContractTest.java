package org.deep361.myddd.testing.contract;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreakerPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link CircuitBreakerPortContractTestBase} 的进程内夹具（Task 32 Step 13 要求的配套交付物）。
 *
 * <p>状态按资源分桶（{@code failures} 与 {@code open} 都以资源名为键），CB-C6 的隔离语义才有东西可验。
 * 只有 {@link RuntimeException} 计为失败，与两个真 provider 的默认记录口径一致。
 */
class CircuitBreakerFakeContractTest extends CircuitBreakerPortContractTestBase {

    private final Map<String, Integer> thresholds = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Set<String> open = new HashSet<>();

    private final CircuitBreakerPort port = new CircuitBreakerPort() {

        @Override
        public <T> T execute(String resource, Supplier<T> action) {
            if (resource == null || resource.isBlank()) {
                throw new IllegalArgumentException("resource 不能为 null 或空白");
            }
            if (open.contains(resource)) {
                // 开闸后根本不碰动作：这条顺序是 CB-C4 的判据所在
                throw new BizException(CommonErrorCode.SERVICE_DEGRADED);
            }
            try {
                T result = action.get();
                failures.remove(resource);
                return result;
            } catch (RuntimeException failure) {
                if (failures.merge(resource, 1, Integer::sum) >= threshold(resource)) {
                    open.add(resource);
                }
                throw failure;
            }
        }
    };

    private int threshold(String resource) {
        return thresholds.getOrDefault(resource, tripThreshold());
    }

    @Override
    protected CircuitBreakerPort circuitBreakerPort() {
        return port;
    }

    @Override
    protected int tripThreshold() {
        return 4;
    }

    @Override
    protected void configure(String resource, int slidingWindowSize) {
        thresholds.put(resource, slidingWindowSize);
        failures.remove(resource);
        open.remove(resource);
    }
}

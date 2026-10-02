package org.deep361.myddd.testing.contract;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 契约基类的轮询等待：不引入 Awaitility 之类的新依赖，也不让用例靠"睡固定时长"通过。
 *
 * <p>失败消息必须同时含描述、超时值和"条件从未成立"这件事本身——集成测试在慢机器上红的时候，
 * 这三段就是全部线索。
 */
public final class Awaits {

    private static final long POLL_INTERVAL_MILLIS = 20L;

    private Awaits() {
    }

    /**
     * 轮询 {@code condition} 直到成立或超时。
     *
     * @throws AssertionError 超时仍未成立
     */
    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError("等待超时（" + timeout + "）：条件始终未成立 —— " + description);
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}

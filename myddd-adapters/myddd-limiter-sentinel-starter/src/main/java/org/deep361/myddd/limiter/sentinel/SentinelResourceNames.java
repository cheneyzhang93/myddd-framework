package org.deep361.myddd.limiter.sentinel;

/**
 * Sentinel 资源名的唯一拼接点：{@code myddd:rate:} 与 {@code myddd:circuit:} 两个内部前缀。
 *
 * <p><b>为什么必须有前缀</b>：Sentinel 的资源名是<b>一个进程级全局命名空间</b>，两份规则表
 * （flow 与 degrade）按同一个字符串键去找各自的规则。探针读数（P45-I）：同名裸资源
 * {@code same-biz} 先下发 flow 规则、再走熔断端口，第一次熔断调用就被 flow 规则拦成
 * {@code FlowException}（{@code D_same_resource_rate_then_circuit=true/FlowException}）——
 * 也就是说<b>一个接口的 QPS 配置会把它的熔断语义改掉</b>，拒绝原因从 503 变成 429，
 * 而这两件事在 web 侧的处置完全不同（Task 47）。加上前缀以后两条互不影响
 * （{@code D_prefixed_names_are_independent=true/PASSED}）。
 *
 * <p><b>为什么前缀不外露</b>：业务写的是注解上的 {@code key}，它不该为了适配某个厂商的
 * 命名空间而改自己的标识符（R2）。所以端口内部拼、规则文件内部拼，不提供"剥回裸名"的
 * 方法——我们的每一条文案都直接用裸名拼（见 {@code SentinelRateLimitPort} 与
 * {@code BizException} 那两处），前缀根本没有机会出现在我们自己产出的文案里；钉子是
 * {@code AB13}（拒绝消息只含裸名）与 {@code RS11}（回读的厂商对象才带前缀，两处各断各的）。
 *
 * <p>前缀不进规则文件的字段面：文件里写的也是裸名（见 Step 5），段名（{@code flow} /
 * {@code degrade}）本身就携带了端口归属，这里再写一遍前缀等于把内部实现细节交给配置文件维护。
 */
public final class SentinelResourceNames {

    /** 限流资源前缀（{@code RateLimitPort} 的唯一命名空间）。 */
    public static final String RATE_PREFIX = "myddd:rate:";

    /** 熔断资源前缀（{@code CircuitBreakerPort} 的唯一命名空间）。 */
    public static final String CIRCUIT_PREFIX = "myddd:circuit:";

    private SentinelResourceNames() {
    }

    /**
     * 限流侧的资源全名。
     *
     * @param resource 业务裸名（注解 {@code key} 或规则文件里的 {@code resource}）
     * @throws IllegalArgumentException 裸名为 null 或空白（端口契约，与厂商无关）
     */
    public static String rate(String resource) {
        return RATE_PREFIX + requireValid(resource, "RateLimitPort.tryAcquire 的 resource");
    }

    /** 熔断侧的资源全名，判据与 {@link #rate(String)} 逐字相同。 */
    public static String circuit(String resource) {
        return CIRCUIT_PREFIX + requireValid(resource, "CircuitBreakerPort.execute 的 resource");
    }

    /**
     * 唯一一处形态校验：两个端口、规则文件的两个段都走这里，所以"空白资源名"只有一套判据
     * 与一条消息（变异 {@code SRM3} 的最小杀手就是这一句）。
     */
    private static String requireValid(String resource, String where) {
        if (resource == null || resource.trim().isEmpty()) {
            throw new IllegalArgumentException("资源名不可为 null 或空白（" + where
                    + "）：Sentinel 的资源按名注册，空白名会落到一个谁都读不回来的资源上——"
                    + "规则下发不进去，调用却照样放行");
        }
        return resource;
    }
}

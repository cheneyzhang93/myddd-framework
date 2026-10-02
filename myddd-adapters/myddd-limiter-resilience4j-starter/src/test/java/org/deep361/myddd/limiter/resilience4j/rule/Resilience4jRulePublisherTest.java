package org.deep361.myddd.limiter.resilience4j.rule;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 规则面的语义与下发面（{@code RS6}–{@code RS12}）：契约级校验拦什么、回读核验拦什么、
 * "先 {@code remove} 再建"这条唯一路线拦什么。
 *
 * <p>三条网各管一段，本组刻意同时钉住"网与网不重叠"：{@code RS9} 的第二格断的是<b>厂商</b>拒而框架不重复
 * 实现区间，{@code RS6}/{@code RS8} 断的是厂商能 build 出来但我们用不了的形态。
 */
class Resilience4jRulePublisherTest {

    private final Resilience4jRuleDocumentReader reader = new Resilience4jRuleDocumentReader(null);
    private final RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(
            RateLimiterConfig.custom().limitForPeriod(1)
                    .limitRefreshPeriod(java.time.Duration.ofDays(3650))
                    .timeoutDuration(java.time.Duration.ZERO).build());
    private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults());
    private final Resilience4jRulePublisher publisher = new Resilience4jRulePublisher(
            rateLimiterRegistry, circuitBreakerRegistry, reader);

    /** RS6：亚秒的 TIME_BASED 窗口会被向下取整 ⇒ 拒收并给出取整结果；整秒（2000ms）通过。 */
    @Test
    void should_reject_a_sliding_window_that_is_not_a_whole_number_of_seconds() throws IOException {
        byte[] fixture;
        try (java.io.InputStream in = getClass().getResourceAsStream("/resilience4j-rules/sub-second-window.json")) {
            fixture = in.readAllBytes();
        }
        MydddResilience4jRuleDocument subSecond = reader.readJson(new String(fixture,
                java.nio.charset.StandardCharsets.UTF_8));

        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> publisher.publish(subSecond));
        assertThat(failure.getMessage())
                .contains("degrade[0]", "资源 payment:capture", "windowMs=1200", "向下取整成 1 秒");

        assertThatCode(() -> publisher.publish(reader.readJson(
                "{\"degrade\":[{\"resource\":\"ok.window\",\"threshold\":\"error-ratio\",\"ratio\":50,"
                        + "\"minCalls\":2,\"windowMs\":2000,\"openSeconds\":30}]}"))).doesNotThrowAnyException();
        assertThat(circuitBreakerRegistry.find("ok.window").orElseThrow().getCircuitBreakerConfig()
                .getSlidingWindowSize()).isEqualTo(2);
    }

    /** RS7：同段重名不去重报错——厂商语义就是"后一条覆盖前一条"，回读的名字集因此是 1。 */
    @Test
    void should_keep_the_last_entry_when_a_resource_is_written_twice() {
        int published = publisher.publish(reader.readJson(
                "{\"flow\":[{\"resource\":\"dup\",\"count\":2},{\"resource\":\"dup\",\"count\":9}]}"));
        assertThat(published).isEqualTo(2);
        assertThat(rateNames()).containsExactly("dup");
        assertThat(rateLimiterRegistry.find("dup").orElseThrow().getRateLimiterConfig().getLimitForPeriod())
                .isEqualTo(9);
    }

    /** RS8：{@code minCalls<=0} 是我们这一格判的（厂商对 0 的态度是另一条消息）。 */
    @Test
    void should_reject_a_minimumNumberOfCalls_that_is_not_positive() {
        FrameworkException failure = assertThrows(FrameworkException.class,
                () -> publisher.publish(reader.readJson(
                        "{\"degrade\":[{\"resource\":\"z\",\"threshold\":\"error-ratio\",\"ratio\":50,"
                                + "\"minCalls\":0,\"windowMs\":1000,\"openSeconds\":30}]}")));
        assertThat(failure.getMessage()).contains("degrade[0]", "minCalls=0", "最小调用数必须是正数");
    }

    /**
     * RS9：99.99 通过、0 由厂商拒——两道网不重叠就靠这一条钉。
     *
     * <p><b>厂商那句 {@code IllegalArgumentException} 在这里是被翻译的，不是被重复实现的</b>：
     * 框架不抄阈值区间（抄了就有第二个真源），只把厂商的读数补上"哪一段哪一条"并换成统一错误面。
     * 于是断言的两格：类型是 {@code FrameworkException}（换 provider 时启动期响的是同一个类型，
     * Task 47 的等价关系），cause 与消息里的判据仍是厂商的（{@code ProbeR4jQuota} 的
     * {@code "LimitForPeriod should be greater than 0"} 与 {@code failureRateThreshold} 两句）。
     */
    @Test
    void should_accept_a_threshold_the_vendor_allows_and_reject_one_the_vendor_throws_on() {
        assertThatCode(() -> publisher.publish(reader.readJson(
                "{\"degrade\":[{\"resource\":\"near.hundred\",\"threshold\":\"error-ratio\",\"ratio\":99.99,"
                        + "\"minCalls\":2,\"windowMs\":1000,\"openSeconds\":30}]}"))).doesNotThrowAnyException();

        FrameworkException ratio = assertThrows(FrameworkException.class,
                () -> publisher.publish(reader.readJson(
                        "{\"degrade\":[{\"resource\":\"zero\",\"threshold\":\"error-ratio\",\"ratio\":0,"
                                + "\"minCalls\":2,\"windowMs\":1000,\"openSeconds\":30}]}")));
        assertThat(ratio).hasMessageContaining("degrade[0]", "资源 zero", "failureRateThreshold");
        assertThat(ratio.getCause()).isInstanceOf(IllegalArgumentException.class);

        FrameworkException quota = assertThrows(FrameworkException.class,
                () -> publisher.publish(reader.readJson("{\"flow\":[{\"resource\":\"zero.rate\",\"count\":0}]}")));
        assertThat(quota).hasMessageContaining("flow[0]", "资源 zero.rate", "LimitForPeriod");
        assertThat(assertThrows(FrameworkException.class, () -> publisher.publish(reader.readJson(
                "{\"flow\":[{\"resource\":\"negative.rate\",\"count\":-3}]}"))))
                .hasMessageContaining("flow[0]", "LimitForPeriod");
        assertThat(rateLimiterRegistry.find("zero.rate").isPresent()).isFalse();
    }

    /** RS10：空白资源名 ⇒ 我们拒（厂商侧没有任何报错，而空名会共享同一个桶）。 */
    @Test
    void should_reject_a_blank_resource_name() {
        for (String resource : new String[]{"", "   "}) {
            FrameworkException failure = assertThrows(FrameworkException.class,
                    () -> publisher.publish(reader.readJson(
                            "{\"flow\":[{\"resource\":\"" + resource + "\",\"count\":1}]}")));
            assertThat(failure.getMessage()).contains("资源名不能为空", "共享一个桶");
        }
    }

    /** RS11：重发即换实例（publisher 层看实例身份，端口层的额度视角是 AB6）。 */
    @Test
    void should_hand_out_a_brand_new_limiter_when_a_resource_is_republished() {
        publisher.publish(reader.readJson("{\"flow\":[{\"resource\":\"hot\",\"count\":1}]}"));
        RateLimiter before = rateLimiterRegistry.find("hot").orElseThrow();
        assertThat(before.acquirePermission(1)).isTrue();
        assertThat(before.getMetrics().getAvailablePermissions()).isEqualTo(0);

        publisher.publish(reader.readJson("{\"flow\":[{\"resource\":\"hot\",\"count\":9}]}"));
        RateLimiter after = rateLimiterRegistry.find("hot").orElseThrow();
        assertThat(after).isNotSameAs(before);
        assertThat(after.acquirePermission(9)).isTrue();
    }

    /** RS12：表外资源必须被清除——两张注册表各断一次（否则"删掉一条规则"在 r4j 侧等于没删）。 */
    @Test
    void should_drop_resources_that_are_absent_from_the_new_document() {
        publisher.publish(reader.readJson(
                "{\"flow\":[{\"resource\":\"keep\",\"count\":2},{\"resource\":\"drop\",\"count\":3}],"
                        + "\"degrade\":[{\"resource\":\"keep\",\"threshold\":\"error-ratio\",\"ratio\":50,"
                        + "\"minCalls\":2,\"windowMs\":1000,\"openSeconds\":30},"
                        + "{\"resource\":\"drop\",\"threshold\":\"error-ratio\",\"ratio\":60,"
                        + "\"minCalls\":2,\"windowMs\":1000,\"openSeconds\":30}]}"));
        assertThat(rateNames()).containsExactlyInAnyOrder("keep", "drop");
        assertThat(circuitNames()).containsExactlyInAnyOrder("keep", "drop");

        publisher.publish(reader.readJson(
                "{\"flow\":[{\"resource\":\"keep\",\"count\":2}],"
                        + "\"degrade\":[{\"resource\":\"keep\",\"threshold\":\"error-ratio\",\"ratio\":50,"
                        + "\"minCalls\":2,\"windowMs\":1000,\"openSeconds\":30}]}"));
        assertThat(rateNames()).containsExactly("keep");
        assertThat(rateLimiterRegistry.find("drop")).isEmpty();
        assertThat(circuitNames()).containsExactly("keep");
        assertThat(circuitBreakerRegistry.find("drop")).isEmpty();
    }

    private Set<String> rateNames() {
        return rateLimiterRegistry.getAllRateLimiters().stream().map(RateLimiter::getName)
                .collect(Collectors.toSet());
    }

    private Set<String> circuitNames() {
        return circuitBreakerRegistry.getAllCircuitBreakers().stream().map(CircuitBreaker::getName)
                .collect(Collectors.toSet());
    }
}

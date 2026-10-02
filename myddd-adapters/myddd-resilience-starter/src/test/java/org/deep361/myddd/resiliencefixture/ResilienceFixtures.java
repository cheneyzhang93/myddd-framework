package org.deep361.myddd.resiliencefixture;

import java.io.IOException;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.port.resilience.CircuitBreaker;
import org.deep361.myddd.kernel.port.resilience.RateLimit;

/** 熔断侧的业务夹具。每个方法的<b>名字</b>就是判据的一部分（缺省资源名、fallback 名规则）。 */
public class ResilienceFixtures {

    /** fallback 的声明位置：证明查找要跨继承链（探针 F7 的读数面）。 */
    public static class BreakerBase {

        /** 只存在于父类的 fallback：`getDeclaredMethod` 找不到、`ReflectionUtils.findMethod` 找得到。 */
        protected String fallbackInherited() {
            return "from-base";
        }
    }

    public static class BreakerService extends BreakerBase {

        @CircuitBreaker
        public String plain() {
            return "ran";
        }

        @CircuitBreaker
        public String throwsChecked() throws IOException {
            throw new IOException("熔断里的业务受检异常");
        }

        /** 业务自己在动作里抛出端口"该抛的那种"异常：与真拒绝同类型，只有动作是否跑起来能区分。 */
        @CircuitBreaker(fallbackMethod = "wouldBeWrongFallback")
        public String businessThrowsDegradedItself() {
            throw new BizException(CommonErrorCode.SERVICE_DEGRADED);
        }

        public String wouldBeWrongFallback() {
            return "绝不该被调到";
        }

        @CircuitBreaker(fallbackMethod = "onPlain")
        public String withFallback() {
            return "ran";
        }

        public String onPlain() {
            return "degraded";
        }

        /** 注解写 `onPlain`、父类没有同名、本类有 → 与上一条同规则，这里只用于对照"跨方法借用 fallback"。 */
        @CircuitBreaker(fallbackMethod = "fallbackInherited")
        public String usesInheritedFallback() {
            return "ran";
        }

        @CircuitBreaker(fallbackMethod = "needsCause")
        public String withCauseTakingFallback(String argument) {
            return "ran";
        }

        /** 只存在"原参数 + 一个 `Throwable` 尾参"的那一版：内核 javadoc 说"签名兼容"，本框架把它定死成这一种。 */
        public String needsCause(String argument, Throwable cause) {
            return "cause:" + (cause == null ? "null" : cause.getClass().getSimpleName());
        }

        @CircuitBreaker(fallbackMethod = "overloaded")
        public String picksBySignature(int count) {
            return "ran";
        }

        /** 探针 F8：只按名字找会得到两个签名。本框架按<b>参数类型精确匹配</b>，所以 `int` 那一版才是答案。 */
        public String overloaded(int count) {
            return "int:" + count;
        }

        public String overloaded(String text) {
            return "string:" + text;
        }

        @CircuitBreaker(fallbackMethod = "both")
        public String ambiguousPrefix() {
            return "ran";
        }

        /** 带前缀与不带前缀各有一个可用方法（都签名兼容）→ 歧义，必须快速失败。 */
        public String both() {
            return "raw";
        }

        public String cbBoth() {
            return "prefixed";
        }
        @CircuitBreaker(fallbackMethod = "privately")
        public String privateFallback() {
            return "ran";
        }

        /** 私有 fallback：本类与切面<b>不同包</b>，不 `setAccessible` 就抛 `IllegalAccessException`（F9）。 */
        private String privately() {
            return "private-ok";
        }

        @CircuitBreaker(fallbackMethod = "returnsWrongType")
        public String wrongReturnTypeFallback() {
            return "ran";
        }

        public Integer returnsWrongType() {
            return 42;
        }

        @CircuitBreaker(fallbackMethod = "explodes")
        public String fallbackExplodes() {
            return "ran";
        }

        public String explodes() {
            throw new IllegalStateException("fallback 自己炸了");
        }

        @CircuitBreaker(fallbackMethod = "annotated")
        public String fallbackIsAnnotated() {
            return "ran";
        }

        /** fallback 自己带注解：在<b>裸目标</b>上调用时它不会被第二次拦（探针 F3 的读数面）。 */
        @RateLimit
        public String annotated() {
            return "annotated";
        }

        /** 前缀夹具：注解写的是去前缀后的名字，实际方法名带 `cb` 前缀。 */
        @CircuitBreaker(fallbackMethod = "prefixedTarget")
        public String usesPrefix() {
            return "ran";
        }

        public String cbPrefixedTarget() {
            return "prefixed";
        }

        /** 阈值三项全合法（非零）：`CB15` 用这一格证"它们不改变任何一次端口调用"。 */
        @CircuitBreaker(failureRateThreshold = 50, slidingWindowSize = 20, waitDurationInOpenState = 3000)
        public String thresholdsDeclaredButUnused() {
            return "ran";
        }

        @CircuitBreaker(failureRateThreshold = -1)
        public String negativeFailureRate() {
            return "unreachable";
        }

        /** 百分比写成了 101（把"万分比"当成了"百分比"是最常见的一类手误）。 */
        @CircuitBreaker(failureRateThreshold = 101)
        public String failureRateAboveHundred() {
            return "unreachable";
        }

        @CircuitBreaker(slidingWindowSize = -1)
        public String negativeWindow() {
            return "unreachable";
        }

        @CircuitBreaker(waitDurationInOpenState = -1)
        public String negativeWaitDuration() {
            return "unreachable";
        }
    }

    /** 限流与熔断并存的夹具：`OR` 组的四条全跑在这一格上。 */
    public static class CombinedService {

        @RateLimit
        @CircuitBreaker
        public String limitedAndBroken() {
            return "ran";
        }

        @RateLimit(timeout = 8)
        @CircuitBreaker(fallbackMethod = "safe")
        public String retryThenBreak() {
            return "ran";
        }

        public String safe() {
            return "safe";
        }
    }
}

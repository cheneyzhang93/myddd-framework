package org.deep361.myddd.consistency.idempotent;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.deep361.myddd.consistency.lock.DistributedLock;
import org.deep361.myddd.consistency.lock.DistributedLockAspect;
import org.deep361.myddd.consistency.lock.RecordingLockPort;
import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.consistency.Idempotent;
import org.deep361.myddd.kernel.port.consistency.IdempotentPort;
import org.deep361.myddd.kernel.port.consistency.LockPort;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link IdempotentAspect} 的行为取证：全部通过<b>真 Spring 代理</b>调用，没有一个用例直接调切面方法。
 *
 * <p>与 {@code DistributedLockAspectTest} 的分工同一条：这里钉的是"切面算出的键/token/时长是否原样落到端口、
 * 释放发生在什么时机、异常怎么透传"，占位存储的语义（NX、TTL、按 token 比对删除）在
 * {@link RedisIdempotentStoreTest} 与 {@code IdempotentRedisIT} 那边钉。端口用 {@link RecordingIdempotentPort}
 * ——它带真占位语义，所以"重复请求""token 一致性"这两类判据能在这里取证，而不必等真 Redis。
 */
class IdempotentAspectTest {

    static class OrderInfo {

        private final String id;

        OrderInfo(String id) {
            this.id = id;
        }

        public String getId() {
            return this.id;
        }
    }

    /** 没有任何"稳定文案"的 DTO：缺省键遇到它必须当场说话。 */
    static class BarePayload {
    }

    /**
     * 注解<b>只</b>写在接口方法上（JDK 代理形态）：切点的注解解析要沿层级找得到它。
     *
     * <p>这条取代了原稿的"组合注解"用例。原稿那两个夹具（把 {@code @Idempotent} 元标注在自己的
     * {@code @interface} 上）根本无法声明——{@code @Idempotent} 的 {@code @Target} 只有 {@code METHOD}，
     * javac 当场拒绝（{@code 批注接口不适用于此类型的声明}）。内核 javadoc 那句"以便组合注解生效"因此
     * 在业务侧是空的，登记为偏差；而"注解写在接口方法上"是同一句 javadoc 背后真实可达的那一半。
     */
    interface InterfaceDeclaredOperations {

        @Idempotent(key = "'order:from-interface'")
        String submit(String orderNo);
    }

    /** 实现类一个注解都不写。 */
    static class InterfaceDeclaredService implements InterfaceDeclaredOperations {

        @Override
        public String submit(String orderNo) {
            return "submitted:" + orderNo;
        }
    }

    /** 注解<b>只</b>写在父类方法上、子类没有覆写（CGLIB 形态）。 */
    static class AnnotationDeclaringBase {

        @Idempotent(key = "'order:from-superclass'")
        public String withdraw() {
            return "withdrew";
        }
    }

    static class WithoutOwnAnnotations extends AnnotationDeclaringBase {
    }

    /** 业务替身：动作证据写在共享时间轴上（CGLIB 代理实例不跑构造函数，写在自家字段上会读到 null）。 */
    static class OrderService {

        private final Timeline timeline;

        OrderService(Timeline timeline) {
            this.timeline = timeline;
        }

        @Idempotent(key = "'order:create'")
        public String literalKey() {
            this.timeline.events.add("run:literalKey");
            return "created";
        }

        @Idempotent(key = "#userId")
        public String byParam(String userId) {
            return "charged:" + userId;
        }

        @Idempotent(key = "#order.id")
        public String byProperty(OrderInfo order) {
            return "shipped:" + order.getId();
        }

        @Idempotent(key = "#p0")
        public String byIndex(String userId) {
            return "refund:" + userId;
        }

        @Idempotent(key = "'order:second'")
        public String otherLiteralKey() {
            return "second";
        }

        @Idempotent
        public String defaultedNoArg() {
            return "ok";
        }

        @Idempotent
        public String defaultedWithArg(String orderNo) {
            this.timeline.events.add("run:defaultedWithArg:" + orderNo);
            return "submitted:" + orderNo;
        }

        @Idempotent(key = "   ")
        public String whitespaceKey() {
            return "ok";
        }

        @Idempotent(key = "#nope")
        public String unknownParam(String userId) {
            return "unreachable";
        }

        @Idempotent(key = "''")
        public String blankEvaluatedKey() {
            return "unreachable";
        }

        @Idempotent
        public String defaultKeyNeedsStableToString(BarePayload payload) {
            return "unreachable";
        }

        @Idempotent(ttl = 120)
        public String longTtl() {
            return "ok";
        }

        @Idempotent(ttl = 0)
        public String zeroTtl() {
            return "unreachable";
        }

        @Idempotent(ttl = -5)
        public String negativeTtl() {
            return "unreachable";
        }

        @Idempotent(key = "'order:checked'")
        public String checkedFailure() throws IOException {
            this.timeline.events.add("run:checkedFailure");
            throw new IOException("下游挂断");
        }

        @Idempotent(key = "'order:runtime'")
        public String runtimeFailure() {
            this.timeline.events.add("run:runtimeFailure");
            throw new IllegalStateException("业务炸了");
        }

        @Idempotent(key = "'order:kept'", releaseOnError = false)
        public String keepsPlaceholderOnFailure() {
            throw new IllegalStateException("失败也不许立刻重试");
        }

        @Idempotent(key = "'order:text'", message = "这张订单已经提交过了")
        public String customText() {
            return "ok";
        }

        @Idempotent(key = "'order:text'")
        public String defaultText() {
            return "ok";
        }

        public String notAnnotated() {
            this.timeline.events.add("run:notAnnotated");
            return "plain";
        }

        @Idempotent(key = "'order:both'")
        @DistributedLock(key = "'order:both'")
        public String insideLockAndTransaction() {
            this.timeline.events.add("run:both");
            return "ok";
        }
    }

    /** JDK 代理形态：接口形参名 {@code accountId}、实现类形参名 {@code accountCode}。 */
    interface AccountOperations {

        String close(String accountId);
    }

    static class AccountService implements AccountOperations {

        @Override
        @Idempotent(key = "#accountCode")
        public String close(String accountCode) {
            return "closed:" + accountCode;
        }
    }

    /** 共享时间轴：幂等端口、锁端口与"事务替身"往同一个列表里各写一笔，交错判据才有共同坐标。 */
    static final class Timeline {

        final List<String> events = new ArrayList<>();
    }

    /** 事务通知的替身：与 Spring 缺省事务 advisor 同为最低优先级，只记进出。 */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @org.aspectj.lang.annotation.Aspect
    static class TransactionStandIn {

        private final Timeline timeline;

        TransactionStandIn(Timeline timeline) {
            this.timeline = timeline;
        }

        @org.aspectj.lang.annotation.Around("@annotation(org.deep361.myddd.kernel.port.consistency.Idempotent)")
        Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            this.timeline.events.add("tx-open");
            try {
                return joinPoint.proceed();
            } finally {
                this.timeline.events.add("tx-close");
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class CglibConfig {

        @Bean
        OrderService orderService(Timeline timeline) {
            return new OrderService(timeline);
        }

        @Bean
        WithoutOwnAnnotations withoutOwnAnnotations() {
            return new WithoutOwnAnnotations();
        }

        @Bean
        IdempotentAspect idempotentAspect(ApplicationContext context) {
            return new IdempotentAspect(context.getBeanProvider(IdempotentPort.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = false)
    static class JdkConfig {

        @Bean
        AccountService accountService() {
            return new AccountService();
        }

        @Bean
        InterfaceDeclaredService interfaceDeclaredService() {
            return new InterfaceDeclaredService();
        }

        @Bean
        IdempotentAspect idempotentAspect(ApplicationContext context) {
            return new IdempotentAspect(context.getBeanProvider(IdempotentPort.class));
        }
    }

    /** 容器里没有任何 {@code IdempotentPort} 时的形态：切面照样建得出来，第一次调用时说话。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class NoIdempotentPortConfig {

        @Bean
        OrderService orderService(Timeline timeline) {
            return new OrderService(timeline);
        }

        @Bean
        IdempotentAspect idempotentAspect(ApplicationContext context) {
            return new IdempotentAspect(context.getBeanProvider(IdempotentPort.class));
        }
    }

    /** 三个通知同时在场：幂等（-1）、锁（0）、事务替身（最低优先级）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class OrderingConfig {

        @Bean
        OrderService orderService(Timeline timeline) {
            return new OrderService(timeline);
        }

        @Bean
        TransactionStandIn transactionStandIn(Timeline timeline) {
            return new TransactionStandIn(timeline);
        }

        @Bean
        IdempotentAspect idempotentAspect(ApplicationContext context) {
            return new IdempotentAspect(context.getBeanProvider(IdempotentPort.class));
        }

        @Bean
        DistributedLockAspect distributedLockAspect(ApplicationContext context) {
            return new DistributedLockAspect(context.getBeanProvider(LockPort.class));
        }
    }

    private static AnnotationConfigApplicationContext contextWith(Timeline timeline, RecordingIdempotentPort port,
            Class<?>... configs) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("idempotentPort", port);
        context.getBeanFactory().registerSingleton("timeline", timeline);
        context.register(configs);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext contextWith(RecordingIdempotentPort port) {
        return contextWith(new Timeline(), port, CglibConfig.class);
    }

    /** 容器里<b>没有</b> {@code IdempotentPort} 的形态：走不了 {@link #contextWith}，那个重载一定会注册端口。 */
    private static AnnotationConfigApplicationContext contextWithoutPort(Class<?> config) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("timeline", new Timeline());
        context.register(config);
        context.refresh();
        return context;
    }

    private static OrderService service(AnnotationConfigApplicationContext context) {
        return context.getBean(OrderService.class);
    }

    @Test
    void should_acquire_the_placeholder_before_running_the_method_and_keep_it_after_success() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThat(service(context).literalKey()).isEqualTo("created");
        }
        assertThat(port.acquisitions()).singleElement()
                .satisfies(call -> assertThat(call.key()).isEqualTo("order:create"));
        assertThat(port.calls()).containsExactly("idempotent-acquire:order:create");
        assertThat(timeline.events).containsExactly("run:literalKey");
        // 成功以后占位必须留着：这才是"防重复提交"。释放只在失败那条分支上。
        assertThat(port.holds("order:create")).isTrue();
    }

    @Test
    void should_take_the_key_from_a_spel_expression_when_the_annotation_writes_one() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).byParam("u-7")).isEqualTo("charged:u-7");
            assertThat(service(context).byProperty(new OrderInfo("o-9"))).isEqualTo("shipped:o-9");
            assertThat(service(context).byIndex("u-3")).isEqualTo("refund:u-3");
        }
        assertThat(port.acquisitions()).extracting(RecordingIdempotentPort.Acquisition::key)
                .containsExactly("u-7", "o-9", "u-3");
    }

    @Test
    void should_keep_the_key_expressions_of_several_methods_apart_in_one_aspect() {
        // 一个切面实例服务整批 bean：两串不同的 key 字面量共用同一个实例，绝不能互相顶。
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            OrderService locked = service(context);
            assertThat(locked.literalKey()).isEqualTo("created");
            assertThat(locked.byParam("u-5")).isEqualTo("charged:u-5");
            assertThat(locked.otherLiteralKey()).isEqualTo("second");
        }
        assertThat(port.acquisitions()).extracting(RecordingIdempotentPort.Acquisition::key)
                .containsExactly("order:create", "u-5", "order:second");
    }

    @Test
    void should_fall_back_to_the_default_digest_key_when_the_annotation_leaves_key_blank() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).defaultedNoArg()).isEqualTo("ok");
        }
        assertThat(port.lastAcquisition().key()).matches("^OrderService\\.defaultedNoArg:[0-9a-f]{16}$");
    }

    @Test
    void should_treat_a_whitespace_only_key_the_same_way_as_leaving_it_blank() {
        // 判据是 isBlank() 而不是 isEmpty()：写了三个空格的人意图是"我没打算自己指定"，
        // 把它交给 SpEL 只会得到一句指向表达式写错的报话。
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).whitespaceKey()).isEqualTo("ok");
        }
        assertThat(port.lastAcquisition().key()).matches("^OrderService\\.whitespaceKey:[0-9a-f]{16}$");
    }

    @Test
    void should_reject_a_second_call_with_equal_arguments_as_a_duplicate_under_the_default_key() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            OrderService idempotent = service(context);
            assertThat(idempotent.defaultedWithArg("o-1")).isEqualTo("submitted:o-1");
            assertThatThrownBy(() -> idempotent.defaultedWithArg("o-1"))
                    .isInstanceOf(BizException.class)
                    .hasMessage("重复请求，请勿重复提交");
            // 换一个业务坐标就是另一次请求：缺省键必须按参数区分，否则整个接口只能提交一次。
            assertThat(idempotent.defaultedWithArg("o-2")).isEqualTo("submitted:o-2");
        }
        assertThat(timeline.events).containsExactly("run:defaultedWithArg:o-1", "run:defaultedWithArg:o-2");
        assertThat(port.calls()).containsExactly("idempotent-acquire:" + port.acquisitions().get(0).key(),
                "idempotent-acquire:" + port.acquisitions().get(1).key(),
                "idempotent-acquire:" + port.acquisitions().get(2).key());
    }

    @Test
    void should_not_run_the_method_and_not_release_anything_when_the_placeholder_belongs_to_another_request() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThat(service(context).literalKey()).isEqualTo("created");
            assertThatThrownBy(() -> service(context).literalKey()).isInstanceOf(BizException.class);
        }
        // 拒绝重复请求时不能把别人的占位删掉——那等于让第三个请求畅通无阻。
        assertThat(port.calls()).containsExactly("idempotent-acquire:order:create", "idempotent-acquire:order:create");
        assertThat(port.releases()).isEmpty();
        assertThat(timeline.events).containsExactly("run:literalKey");
        assertThat(port.holds("order:create")).isTrue();
    }

    @Test
    void should_release_the_placeholder_when_the_business_method_fails() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort port = new RecordingIdempotentPort(timeline.events);
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThatThrownBy(() -> service(context).runtimeFailure()).isInstanceOf(IllegalStateException.class);
        }
        // releaseOnError 缺省 true：校验类失败以后用户可以改正重试。
        // 中间那笔 run:runtimeFailure 是这条用例的一部分证据：释放发生在动作真的跑过之后，而不是之前。
        assertThat(port.calls()).containsExactly("idempotent-acquire:order:runtime", "run:runtimeFailure",
                "idempotent-release:order:runtime");
        assertThat(port.holds("order:runtime")).isFalse();
    }

    @Test
    void should_release_with_the_same_token_that_acquired_the_placeholder() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).runtimeFailure()).isInstanceOf(IllegalStateException.class);
        }
        // 每次重新生成 token 会得到"释放永远比对不上"的形状（真端口按 token 比对后删除），
        // 而它只在慢请求上显形——这里用端口状态直接钉住。
        assertThat(port.releases()).singleElement().satisfies(release -> {
            assertThat(release.key()).isEqualTo("order:runtime");
            assertThat(release.token()).isEqualTo(port.lastAcquisition().token());
        });
    }

    @Test
    void should_keep_the_placeholder_after_a_failure_when_release_on_error_is_off() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).keepsPlaceholderOnFailure())
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(port.calls()).containsExactly("idempotent-acquire:order:kept");
        assertThat(port.releases()).isEmpty();
        // 占位还在，所以同键的第二次调用会被拒绝——"失败也不许立刻重试"的全部含义。
        assertThat(port.holds("order:kept")).isTrue();
    }

    @Test
    void should_propagate_a_checked_exception_from_the_method_unchanged() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            // 这里不装箱：切面直接调端口，所以 IOException 到调用方还是 IOException，
            // catch (IOException) 与 throws 声明都不受影响。
            assertThatThrownBy(() -> service(context).checkedFailure())
                    .isInstanceOf(IOException.class)
                    .hasMessage("下游挂断");
        }
        assertThat(port.calls()).containsExactly("idempotent-acquire:order:checked", "idempotent-release:order:checked");
    }

    @Test
    void should_propagate_a_runtime_exception_from_the_method_after_releasing() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).runtimeFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("业务炸了")
                    .hasNoCause();
        }
        assertThat(port.releases()).hasSize(1);
    }

    @Test
    void should_send_the_annotated_ttl_as_a_positive_duration_of_seconds() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).longTtl()).isEqualTo("ok");
        }
        // 注解上的数字单位是秒（内核契约写死），换算错了表现是"占位只活 120 毫秒"这类查不出来的错。
        assertThat(port.lastAcquisition().ttl()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void should_use_the_port_default_ttl_when_the_annotation_leaves_ttl_untouched() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            service(context).literalKey();
        }
        assertThat(port.lastAcquisition().ttl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void should_reject_a_non_positive_ttl_naming_the_attribute_and_method() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).zeroTtl())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("ttl=0")
                    .hasMessageContaining("zeroTtl");
            assertThatThrownBy(() -> service(context).negativeTtl())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("ttl=-5")
                    .hasMessageContaining("negativeTtl");
        }
        // 端口一次都没被叫： ttl 不合法时"占位"这件事根本没发生，而不是占了一个永不过期的位。
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_fail_with_the_shared_error_text_when_no_message_is_written() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            OrderService idempotent = service(context);
            assertThat(idempotent.defaultText()).isEqualTo("ok");
            assertThatThrownBy(idempotent::defaultText)
                    .isInstanceOf(BizException.class)
                    .hasMessage("重复请求，请勿重复提交");
        }
    }

    @Test
    void should_report_the_business_text_but_keep_the_error_identity_when_a_message_is_written() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            OrderService idempotent = service(context);
            assertThat(idempotent.customText()).isEqualTo("ok");
            assertThatThrownBy(idempotent::customText)
                    .isInstanceOf(BizException.class)
                    .hasMessage("这张订单已经提交过了")
                    .satisfies(failure -> {
                        // 业务改文案不该把 409 变成 500，也不该让按错误码聚合的监控曲线断掉。
                        BizException conflict = (BizException) failure;
                        assertThat(conflict.getErrorCode()).isSameAs(CommonErrorCode.IDEMPOTENT_CONFLICT);
                        assertThat(conflict.getErrorCode().httpStatus()).isEqualTo(409);
                    });
        }
    }

    @Test
    void should_fail_when_the_key_expression_names_an_unknown_parameter() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).unknownParam("u-1"))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("@Idempotent")
                    .hasMessageContaining("#nope")
                    .hasMessageContaining("unknownParam")
                    .hasMessageContaining("幂等键")
                    .hasMessageContaining("#p0")
                    // 拒绝空白键的是端口而不是切面，那句话必须指名是哪个端口：写成 LockPort 的话
                    // 会把人送去查锁。
                    .hasMessageContaining("IdempotentPort");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_send_a_blank_evaluated_key_to_the_port_unchanged_and_let_the_port_refuse_it() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            // 谁拒的要分得清：抛的是端口那句 IllegalArgumentException，不是框架替端口加工过的串。
            assertThatThrownBy(() -> service(context).blankEvaluatedKey())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("幂等键空白");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_reject_the_default_key_of_an_argument_that_cannot_be_rendered_stably_before_calling_the_port() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).defaultKeyNeedsStableToString(new BarePayload()))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("toString()");
        }
        // 键都算不出来，端口就不该被叫——否则"注解静默不生效"会换成"端口静默收了个坏键"。
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_not_intercept_a_bean_whose_annotation_sits_only_on_the_interface_method() {
        // 一手实测：这条与上一轮的预期相反——Spring 的 @annotation 切点在代理式 AOP 下看的是目标类那个方法，
        // 接口方法上的注解不参与匹配，于是容器里交出的是<b>一个没被代理过的裸实例</b>：连 JDK 代理都没开。
        // 也就是说 @Idempotent 写在接口上是彻底静默失效（不报错、不日志、不抛），这是幂等最坏的失效方式，
        // 所以它必须钉在明面上而不是藏在注释里：注解要写在实现类（或它没覆写时真正声明该方法的父类）上。
        // 与切面把方法折算到实现类（mostSpecificMethod）不矛盾：折算解决的是"形参名在实现类的字节码里"，
        // 前提是这条调用本来就被拦下来了。
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(new Timeline(), port, JdkConfig.class)) {
            Object service = context.getBean(InterfaceDeclaredService.class);
            assertThat(service.getClass()).isEqualTo(InterfaceDeclaredService.class);
            assertThat(((InterfaceDeclaredOperations) service).submit("o-1")).isEqualTo("submitted:o-1");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_intercept_when_only_the_superclass_method_declares_the_annotation() {
        // 与上一条只差一个方向：父类方法上的注解<b>能</b>命中（子类没覆写，连接点方法就是父类那一个，
        // Spring 沿类层级找得到），接口方法上的不能。这条不对称是代理式 AOP 的既有行为，不是本切面的选择。
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(context.getBean(WithoutOwnAnnotations.class).withdraw()).isEqualTo("withdrew");
        }
        assertThat(port.lastAcquisition().key()).isEqualTo("order:from-superclass");
    }

    @Test
    void should_target_methods_only_so_a_composed_idempotent_cannot_be_declared() {
        // 内核 @Idempotent 的 javadoc 写着"切面须用 AnnotationUtils/MergedAnnotations 解析，以便组合注解生效"。
        // 切面这一侧照做了（切点用参数绑定，走的是 Spring 的 find-merged 解析），但业务侧连一个组合注解都声明
        // 不出来：@Target 里没有 ANNOTATION_TYPE，把它元标注在自己的 @interface 上会被 javac 拒绝。
        // 这条守卫钉住的是那个缺口本身——内核哪天补上 ElementType.ANNOTATION_TYPE，这里立刻变红，
        // 提醒补的是"组合注解 + @AliasFor 属性覆盖"那两条用例，而不是让这句 javadoc 继续空转。
        assertThat(Idempotent.class.getAnnotation(java.lang.annotation.Target.class).value())
                .containsExactly(ElementType.METHOD);
    }

    @Test
    void should_not_intercept_a_method_without_the_annotation() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThat(service(context).notAnnotated()).isEqualTo("plain");
        }
        assertThat(port.calls()).isEmpty();
        assertThat(timeline.events).containsExactly("run:notAnnotated");
    }

    @Test
    void should_work_through_a_jdk_proxy_with_the_annotation_on_the_implementation() {
        RecordingIdempotentPort port = new RecordingIdempotentPort();
        try (AnnotationConfigApplicationContext context = contextWith(new Timeline(), port, JdkConfig.class)) {
            AccountOperations service = context.getBean(AccountOperations.class);
            assertThat(Proxy.isProxyClass(service.getClass()))
                    .as("这条用例的前提是 JDK 动态代理：连接点拿到的是接口方法").isTrue();
            assertThat(service.close("a-1")).isEqualTo("closed:a-1");
        }
        // 键按实现类的形参名算出：接口方法上没有 -parameters 写进字节码的那份名字。
        assertThat(port.lastAcquisition().key()).isEqualTo("a-1");
    }

    @Test
    void should_check_idempotency_outside_the_lock_and_the_transaction() {
        Timeline timeline = new Timeline();
        RecordingIdempotentPort idempotentPort = new RecordingIdempotentPort(timeline.events);
        RecordingLockPort lockPort = new RecordingLockPort(timeline.events);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("idempotentPort", idempotentPort);
            context.getBeanFactory().registerSingleton("lockPort", lockPort);
            context.getBeanFactory().registerSingleton("timeline", timeline);
            context.register(OrderingConfig.class);
            context.refresh();

            assertThat(context.getBean(OrderService.class).insideLockAndTransaction()).isEqualTo("ok");

            // 幂等在最外：重复请求应当在排队等锁之前就被拒绝，白占一段临界区正是幂等要省掉的开销。
            assertThat(timeline.events).containsExactly("idempotent-acquire:order:both", "acquire:order:both",
                    "tx-open", "run:both", "tx-close", "release:order:both");
        }
    }

    @Test
    void should_fail_at_call_time_with_an_actionable_message_when_no_idempotent_port_exists() {
        try (AnnotationConfigApplicationContext context = contextWithoutPort(NoIdempotentPortConfig.class)) {
            assertThatThrownBy(() -> service(context).literalKey())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("IdempotentPort")
                    // 开关名要给全两半：只说"=false 会关掉实现"而不说"=true 是缺省"，
                    // 读话的人不知道该把它拨回哪一边。
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=true")
                    .hasMessageContaining("myddd.consistency.idempotent.enabled=false");
        }
    }

    @Test
    void should_refuse_the_call_when_the_port_itself_failed_to_place_the_placeholder() {
        // 端口自己出错（连接挂断）时：业务方法一次都不该跑，也不该有释放动作。
        RecordingIdempotentPort port = new RecordingIdempotentPort()
                .throwingOnAcquire(new IllegalStateException("Redis 挂了"));
        Timeline timeline = new Timeline();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThatThrownBy(() -> service(context).literalKey()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Redis 挂了");
        }
        assertThat(timeline.events).isEmpty();
        assertThat(port.releases()).isEmpty();
    }

    @Test
    void should_reject_a_null_idempotent_port_provider_at_construction_time() {
        // 与 DefaultLockPort / RedisIdempotentStore 同一条纪律：装配接线错当场说话。
        assertThatThrownBy(() -> new IdempotentAspect(null))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ObjectProvider");
    }

    @Test
    void should_bind_the_annotation_parameter_of_the_pointcut() {
        // 参数绑定的切点是一段字符串 + 一个形参名，两边写歪一个字编译器都不响（字符串里那个名字只是个引用名），
        // 表现是"注解标着、切面就是不切"。这条把字符串与真实形参名钉在一起。
        Method around = null;
        try {
            around = IdempotentAspect.class.getMethod("around", ProceedingJoinPoint.class, Idempotent.class);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError("IdempotentAspect#around(ProceedingJoinPoint, Idempotent) 的签名变了", failure);
        }
        assertThat(around.getAnnotation(org.aspectj.lang.annotation.Around.class).value())
                .isEqualTo("@annotation(idempotent)");
        assertThat(around.getParameters()[1].getName()).isEqualTo("idempotent");
    }

    @Test
    void should_order_idempotency_strictly_outside_the_distributed_lock_aspect() {
        // 时序用例（同一个容器里跑幂等+锁+事务替身）在两个 @Order 相等时照样能过：Spring 对同序 advisor
        // 按注册次序排，"幂等在最外"就退化成装配顺序的巧合——业务自带一个负数序的切面就能悄悄翻过来。
        // 所以这里钉的是数值关系本身：严格小于锁，而不是"现在恰好是这样"。
        assertThat(orderValueOf(IdempotentAspect.class)).isLessThan(orderValueOf(DistributedLockAspect.class));
    }

    private static int orderValueOf(Class<?> aspect) {
        Order declared = aspect.getAnnotation(Order.class);
        return declared == null ? Ordered.LOWEST_PRECEDENCE : declared.value();
    }
}

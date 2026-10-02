package org.deep361.myddd.consistency.lock;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.deep361.myddd.kernel.common.error.BizException;
import org.deep361.myddd.kernel.common.error.CommonErrorCode;
import org.deep361.myddd.kernel.common.error.FrameworkException;
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
 * {@link DistributedLockAspect} 的行为取证：全部通过<b>真 Spring 代理</b>调用，没有一个用例直接调切面方法。
 *
 * <p>为什么不用 {@code AspectJProxyFactory} 手搓代理：这个类的判据有一半在切点与代理形态里
 * （"注解必须在被调用的方法上"、"接口代理时参数名要折算到实现类"），手搓工厂会把这些一起绕过去。
 * 因此这里用 {@code @EnableAspectJAutoProxy} 的真实上下文，CGLIB 与 JDK 两种代理形态各测一遍。
 *
 * <p>锁的<b>语义</b>（等待、租约、watchdog、释放前是否持锁）不在这组用例里——那是 {@code LockPort} 实现的事
 * （Task 24 的 D 组）。这里只钉"切面算出的三元组是否原样落到端口"，端口用 {@link RecordingLockPort}。
 */
class DistributedLockAspectTest {

    /** SpEL 走 {@code getId()} 的业务坐标类型。 */
    static class OrderInfo {

        private final String id;

        OrderInfo(String id) {
            this.id = id;
        }

        public String getId() {
            return this.id;
        }
    }

    /** 业务替身：既是返回值来源，也是"动作真的跑了"的证据（证据写在共享时间轴上）。 */
    static class OrderService {

        private final Timeline timeline;

        OrderService(Timeline timeline) {
            this.timeline = timeline;
        }

        @DistributedLock(key = "'order:create'")
        public String literal() {
            this.timeline.events.add("run:literal");
            return "created";
        }

        @DistributedLock(key = "#userId")
        public String byParam(String userId) {
            this.timeline.events.add("run:byParam");
            return "charged:" + userId;
        }

        @DistributedLock(key = "#order.id")
        public String byProperty(OrderInfo order) {
            return "shipped:" + order.getId();
        }

        @DistributedLock(key = "#p0")
        public String byIndex(String userId) {
            return "refund:" + userId;
        }

        @DistributedLock(key = "#tenant + ':' + #orderNo")
        public String composed(String tenant, String orderNo) {
            return tenant + "/" + orderNo;
        }

        @DistributedLock(key = "#userId")
        public String sameExpressionOtherArg(String userId) {
            this.timeline.events.add("run:sameExpressionOtherArg");
            return userId;
        }

        @DistributedLock(key = "#nope")
        public String unknownParam(String userId) {
            return "unreachable";
        }

        @DistributedLock(key = "#order.id")
        public String nullKey(OrderInfo order) {
            return "unreachable";
        }

        @DistributedLock(key = "#order.total")
        public String missingProperty(OrderInfo order) {
            return "unreachable";
        }

        @DistributedLock(key = "''")
        public String blankKey() {
            return "unreachable";
        }

        @DistributedLock(key = "'order:single'")
        public String onlyKeyWritten() {
            return "ok";
        }

        @DistributedLock(key = "'order:watchdog'", waitTime = "200ms", leaseTime = "0s")
        public String watchdog() {
            return "ok";
        }

        @DistributedLock(key = "'order:iso'", waitTime = "PT2.5S", leaseTime = "PT1M")
        public String iso8601Timings() {
            return "ok";
        }

        @DistributedLock(key = "'order:bad'", waitTime = "30 seconds")
        public String badWaitTime() {
            return "unreachable";
        }

        @DistributedLock(key = "'order:bad'", leaseTime = "30 seconds")
        public String badLeaseTime() {
            return "unreachable";
        }

        @DistributedLock(key = "#userId +")
        public String unparsableKey(String userId) {
            return "unreachable";
        }

        @DistributedLock(key = "'order:checked'")
        public String checkedFailure() throws IOException {
            throw new IOException("下游挂断");
        }

        @DistributedLock(key = "'order:runtime'")
        public String runtimeFailure() {
            throw new IllegalStateException("业务炸了");
        }

        public String notAnnotated() {
            this.timeline.events.add("run:notAnnotated");
            return "plain";
        }

        @DistributedLock(key = "'order:txprobe'")
        public String insideTransaction() {
            this.timeline.events.add("run:insideTransaction");
            return "ok";
        }
    }

    /**
     * 接口形态的业务 bean：接口那边叫 {@code skuId}、实现类那边叫 {@code skuCode}。
     *
     * <p>这不是笔误，是这条用例的前提：注解写在实现类上，表达式按<b>实现类</b>的形参名写；而 JDK 代理下
     * 连接点拿到的是接口方法（它自己的 {@code -parameters} 名字是另一个）。不折算到最具体方法，
     * {@code #skuCode} 就求值成 null——变异 M5 实测的正是这件事。
     */
    interface InventoryOperations {

        String reserve(String skuId);
    }

    static class InventoryService implements InventoryOperations {

        @Override
        @DistributedLock(key = "#skuCode")
        public String reserve(String skuCode) {
            return "reserved:" + skuCode;
        }
    }

    /**
     * 共享时间轴：端口与"事务替身"往同一个列表里各写一笔，锁包住事务这条顺序断言才有共同时间轴可比。
     *
     * <p>为什么不直接传 {@code List<String>}：Spring 对集合类型的注入点是"收集该类型的全部 bean"，
     * 一个空的 {@code List<String>} 依赖会静默解析成空列表，测试就记到了没人看的那份日志上。
     *
     * <p>为什么业务替身的证据也写在这里而不是自己的字段上：CGLIB 代理实例<b>不跑构造函数</b>
     * （Objenesis 直接分配），代理对象上那个字段的初始化从没执行过，读它得到 null——
     * 本类第一版就是这么炸的（三条 {@code Expecting actual not to be null}）。被代理的是目标对象，
     * 能跨代理形态看到的只有注入进去的协作者。
     */
    static final class Timeline {

        final List<String> events = new ArrayList<>();
    }

    /**
     * 事务通知的替身：与 Spring 缺省的事务 advisor 同为最低优先级，只往共享时间轴里记进出。
     *
     * <p>两份独立日志各自排序再拼起来证明不了任何交错关系，所以这里必须与端口共用一条轴。
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @org.aspectj.lang.annotation.Aspect
    static class TransactionStandIn {

        private final Timeline timeline;

        TransactionStandIn(Timeline timeline) {
            this.timeline = timeline;
        }

        @org.aspectj.lang.annotation.Around("@annotation(org.deep361.myddd.consistency.lock.DistributedLock)")
        Object around(org.aspectj.lang.ProceedingJoinPoint joinPoint) throws Throwable {
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
        DistributedLockAspect distributedLockAspect(ApplicationContext context) {
            return new DistributedLockAspect(context.getBeanProvider(LockPort.class));
        }
    }

    /** JDK 代理形态：只装这一个业务 bean，代理形态本身用 {@link Proxy#isProxyClass} 事前核验。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = false)
    static class JdkConfig {

        @Bean
        InventoryService inventoryService() {
            return new InventoryService();
        }

        @Bean
        DistributedLockAspect distributedLockAspect(ApplicationContext context) {
            return new DistributedLockAspect(context.getBeanProvider(LockPort.class));
        }
    }

    /** 容器里没有任何 LockPort 时的形态：切面照样建得出来，问题在第一次调用时说话。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class NoLockPortConfig {

        @Bean
        OrderService orderService(Timeline timeline) {
            return new OrderService(timeline);
        }

        @Bean
        DistributedLockAspect distributedLockAspect(ApplicationContext context) {
            return new DistributedLockAspect(context.getBeanProvider(LockPort.class));
        }
    }

    /** 交错判据专用的上下文：被测切面与替身通知同时在场。 */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TxOrderConfig {

        @Bean
        OrderService orderService(Timeline timeline) {
            return new OrderService(timeline);
        }

        @Bean
        TransactionStandIn transactionStandIn(Timeline timeline) {
            return new TransactionStandIn(timeline);
        }

        @Bean
        DistributedLockAspect distributedLockAspect(ApplicationContext context) {
            return new DistributedLockAspect(context.getBeanProvider(LockPort.class));
        }
    }

    private static AnnotationConfigApplicationContext contextWith(Timeline timeline, RecordingLockPort port,
            Class<?> config) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("lockPort", port);
        context.getBeanFactory().registerSingleton("timeline", timeline);
        context.register(config);
        context.refresh();
        return context;
    }

    /** 只看端口收到什么、不看动作证据的用例走这个重载。 */
    private static AnnotationConfigApplicationContext contextWith(RecordingLockPort port) {
        return contextWith(new Timeline(), port, CglibConfig.class);
    }

    /** 容器里<b>没有</b> {@code LockPort} 的形态：走不了 {@link #contextWith}，那个重载一定会注册一个端口。 */
    private static AnnotationConfigApplicationContext contextWithoutLockPort(Class<?> config) {
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
    void should_lock_under_the_literal_key_from_a_spel_string_constant() {
        Timeline timeline = new Timeline();
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThat(service(context).literal()).isEqualTo("created");
        }
        assertThat(port.invocations()).containsExactly(
                new RecordingLockPort.Invocation("order:create", Duration.ZERO, Duration.ofSeconds(30)));
        assertThat(port.calls()).containsExactly("acquire:order:create", "release:order:create");
        assertThat(timeline.events).containsExactly("run:literal");
    }

    @Test
    void should_take_the_lock_key_from_a_method_parameter_by_name() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).byParam("u-7")).isEqualTo("charged:u-7");
        }
        assertThat(port.last().key()).isEqualTo("u-7");
    }

    @Test
    void should_take_the_lock_key_from_a_nested_parameter_property() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).byProperty(new OrderInfo("o-9"))).isEqualTo("shipped:o-9");
        }
        assertThat(port.last().key()).isEqualTo("o-9");
    }

    @Test
    void should_accept_positional_variables_when_parameter_names_are_not_used() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).byIndex("u-3")).isEqualTo("refund:u-3");
        }
        assertThat(port.last().key()).isEqualTo("u-3");
    }

    @Test
    void should_compose_the_lock_key_from_several_parameters() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).composed("t1", "o2")).isEqualTo("t1/o2");
        }
        assertThat(port.last().key()).isEqualTo("t1:o2");
    }

    @Test
    void should_reuse_one_parsed_expression_without_leaking_arguments_between_calls() {
        Timeline timeline = new Timeline();
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            OrderService locked = service(context);
            assertThat(locked.sameExpressionOtherArg("a")).isEqualTo("a");
            assertThat(locked.byParam("b")).isEqualTo("charged:b");
            assertThat(locked.sameExpressionOtherArg("c")).isEqualTo("c");
        }
        // 两个方法共用同一串 "#userId"（因而共用一棵语法树），求值上下文必须每次新建
        assertThat(port.invocations()).extracting(RecordingLockPort.Invocation::key).containsExactly("a", "b", "c");
        assertThat(timeline.events).containsExactly("run:sameExpressionOtherArg", "run:byParam",
                "run:sameExpressionOtherArg");
    }

    @Test
    void should_keep_the_cached_expressions_of_several_methods_apart_in_one_aspect() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            OrderService locked = service(context);
            assertThat(locked.literal()).isEqualTo("created");
            assertThat(locked.byParam("u-5")).isEqualTo("charged:u-5");
            assertThat(locked.composed("t1", "o2")).isEqualTo("t1/o2");
        }
        // 与上一条配成一对：那条钉"共用一棵树时实参不串"，这条钉"三棵树各归各的"。
        // 缓存的键是表达式原文，所以键写死成别的（变异 M32）会让第二次调用拿到第一棵语法树。
        assertThat(port.invocations()).extracting(RecordingLockPort.Invocation::key)
                .containsExactly("order:create", "u-5", "t1:o2");
    }

    @Test
    void should_fail_when_the_key_expression_names_a_property_that_does_not_exist() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            // 与上一条分岔的证据：属性路径写错时 SpEL 抛 SpelEvaluationException，而陌生变量名只给 null，
            // 两条分支各有各的话——合成一句就会丢掉"到底写错了什么"。
            assertThatThrownBy(() -> service(context).missingProperty(new OrderInfo("o-1")))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("#order.total")
                    .hasMessageContaining("missingProperty")
                    .hasMessageContaining("#p0");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_fail_with_an_actionable_message_when_the_key_names_an_unknown_parameter() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).unknownParam("u-1"))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("#nope")
                    .hasMessageContaining("unknownParam")
                    .hasMessageContaining("#p0");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_fail_when_the_key_expression_evaluates_to_null() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).nullKey(new OrderInfo(null)))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("null")
                    .hasMessageContaining("nullKey");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_send_a_blank_evaluated_key_to_the_port_unchanged_and_let_the_port_refuse_it() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).blankKey())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("锁键空白（与 DefaultLockPort 同一条拒绝）");
        }
        // 判据是"谁拒的"：切面不加工键、也不替端口放行，拒绝发生在端口那一侧
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_send_the_default_pair_to_the_port_when_only_key_is_written() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).onlyKeyWritten()).isEqualTo("ok");
        }
        assertThat(port.last()).isEqualTo(
                new RecordingLockPort.Invocation("order:single", Duration.ZERO, Duration.ofSeconds(30)));
    }

    @Test
    void should_keep_the_annotation_defaults_equal_to_the_port_default_timings() {
        RecordingLockPort port = new RecordingLockPort();
        LockPort raw;
        OrderService locked;
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            raw = context.getBean(LockPort.class);
            locked = service(context);
            locked.onlyKeyWritten();
            // 端口的两参重载是"缺省时序"的另一半真源，两条路径必须落到同一个三元组
            raw.executeLocked("order:single", () -> null);
            locked.onlyKeyWritten();
        }
        List<RecordingLockPort.Invocation> invocations = port.invocations();
        assertThat(invocations).hasSize(3);
        assertThat(invocations.get(1)).isEqualTo(invocations.get(0));
        assertThat(invocations.get(2)).isEqualTo(invocations.get(0));
    }

    @Test
    void should_ask_for_the_watchdog_when_lease_time_is_written_as_zero() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).watchdog()).isEqualTo("ok");
        }
        assertThat(port.last()).isEqualTo(
                new RecordingLockPort.Invocation("order:watchdog", Duration.ofMillis(200), Duration.ZERO));
    }

    @Test
    void should_parse_iso_8601_durations_as_well_as_the_simple_form() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThat(service(context).iso8601Timings()).isEqualTo("ok");
        }
        assertThat(port.last()).isEqualTo(
                new RecordingLockPort.Invocation("order:iso", Duration.ofMillis(2500), Duration.ofMinutes(1)));
    }

    @Test
    void should_reject_an_unparsable_wait_time_naming_the_attribute_and_method() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).badWaitTime())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("waitTime")
                    .hasMessageContaining("30 seconds")
                    .hasMessageContaining("badWaitTime");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_reject_an_unparsable_lease_time_naming_the_attribute_and_method() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).badLeaseTime())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("leaseTime")
                    .hasMessageContaining("badLeaseTime");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_reject_an_unparsable_key_expression_naming_the_method() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).unparsableKey("u-1"))
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("unparsableKey")
                    .hasMessageContaining("#userId +");
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void should_propagate_a_checked_exception_from_the_method_unchanged_after_releasing() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            // 调用方 catch 的必须是 IOException：装箱/解箱任何一环漏了，这里看到的都是包装异常
            assertThatThrownBy(() -> service(context).checkedFailure())
                    .isInstanceOf(IOException.class)
                    .hasMessage("下游挂断");
        }
        assertThat(port.calls()).containsExactly("acquire:order:checked", "release:order:checked");
    }

    @Test
    void should_propagate_a_runtime_exception_from_the_method_after_releasing() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).runtimeFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("业务炸了");
        }
        assertThat(port.calls()).containsExactly("acquire:order:runtime", "release:order:runtime");
    }

    @Test
    void should_not_lock_a_method_without_the_annotation() {
        Timeline timeline = new Timeline();
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, CglibConfig.class)) {
            assertThat(service(context).notAnnotated()).isEqualTo("plain");
        }
        assertThat(port.calls()).isEmpty();
        assertThat(timeline.events).containsExactly("run:notAnnotated");
    }

    @Test
    void should_acquire_the_lock_outside_the_lowest_priority_transaction_advisor() {
        Timeline timeline = new Timeline();
        RecordingLockPort port = new RecordingLockPort(timeline.events);
        try (AnnotationConfigApplicationContext context = contextWith(timeline, port, TxOrderConfig.class)) {
            service(context).insideTransaction();
            // 锁必须在事务替身之外：加锁 → 开事务 → 跑动作 → 提交 → 解锁。
            // 反了就是"提交前已放锁"那个故障——第二个线程能在未提交的数据上开事务
            assertThat(timeline.events).containsExactly("acquire:order:txprobe", "tx-open", "run:insideTransaction",
                    "tx-close", "release:order:txprobe");
        }
    }

    @Test
    void should_work_through_a_jdk_proxy_with_the_annotation_on_the_implementation() {
        RecordingLockPort port = new RecordingLockPort();
        try (AnnotationConfigApplicationContext context = contextWith(new Timeline(), port, JdkConfig.class)) {
            InventoryOperations bean = context.getBean(InventoryOperations.class);
            assertThat(Proxy.isProxyClass(bean.getClass()))
                    .as("这个用例的前提就是 JDK 代理形态，先钉住它").isTrue();
            assertThat(bean.reserve("sku-1")).isEqualTo("reserved:sku-1");
        }
        // 表达式按实现类的形参名（#skuCode）写，连接点上的接口方法叫 skuId——折算到最具体方法才求得到值
        assertThat(port.last().key()).isEqualTo("sku-1");
    }

    @Test
    void should_fail_at_call_time_with_an_actionable_message_when_no_lock_port_exists() {
        try (AnnotationConfigApplicationContext context = contextWithoutLockPort(NoLockPortConfig.class)) {
            assertThatThrownBy(() -> context.getBean(OrderService.class).literal())
                    .isInstanceOf(FrameworkException.class)
                    .hasMessageContaining("myddd.consistency.lock.enabled")
                    .hasMessageContaining("LockPort");
        }
    }

    @Test
    void should_reject_a_null_lock_port_provider_at_construction_time() {
        // 与 DefaultLockPort 的构造期拒绝同一条纪律：装配里给不出 provider 就是接线错，
        // 让它当场说话，而不是等第一次调用时读到 null。
        assertThatThrownBy(() -> new DistributedLockAspect(null))
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("ObjectProvider");
    }

    @Test
    void should_not_unlock_when_the_port_itself_refused_to_acquire() {
        RecordingLockPort port = new RecordingLockPort().throwingOnAcquire(
                BizException.causedBy(CommonErrorCode.LOCK_ACQUIRE_FAILED, new IllegalStateException("拿不到锁")));
        try (AnnotationConfigApplicationContext context = contextWith(port)) {
            assertThatThrownBy(() -> service(context).literal()).isInstanceOf(BizException.class);
        }
        assertThat(port.calls()).containsExactly("acquire:order:create");
    }
}

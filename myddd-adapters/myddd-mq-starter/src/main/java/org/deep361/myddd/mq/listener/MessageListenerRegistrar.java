package org.deep361.myddd.mq.listener;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * {@code @MessageListener} 注册表：全部单例实例化之后（{@link SmartInitializingSingleton}）扫描上下文里的 bean，
 * 把标注方法冻结成一份有序快照，供 {@link InboundDispatcher} 与
 * {@link org.deep361.myddd.mq.route.MessageConsumptionPlan} 读取。
 *
 * <p><b>扫描范围</b>：类型可知的 bean（{@code getBeanNamesForType(Object.class, false, false)}），跳过 scope 代理的
 * 目标 bean（{@code scopedTarget.*}，避免与代理重复注册）。方法按"本类 → 父类 → 接口"收集，同一签名只注册一次
 * （最具体优先）。<b>类型只到接口的 bean（JDK 动态代理形态）扫不到实现类上的注解</b>——与
 * {@code DomainEventListenerRegistrar} 同一个已记录的能力边界，Boot 缺省是 CGLIB，不影响默认用法。
 *
 * <p><b>启动期失败</b>：静态／private／返回值非 void／参数个数不是 1／{@code channel} 为空白，以及
 * <b>同一通道声明了两个不同的 {@code group}</b>，一律抛 {@link FrameworkException} 让应用起不来。
 * group 冲突必须失败的理由：一个通道在一个进程里只能落进一个消费者组，两个组意味着两个消费者实例，
 * 而"谁收到哪一条"没有定义——这是配置写错，不是运行时能猜的事。
 *
 * <p><b>代价</b>：带监听方法的 bean 会在启动期被取实例（{@code getBean}）——监听器 bean 不应声明为懒加载
 * （与 {@code DomainEventListenerRegistrar} 同一条已知代价）。
 *
 * <p><b>线程安全</b>：快照写进 {@code volatile} 字段后只读；写发生在容器回调里，读发生在消费者线程里。
 */
public class MessageListenerRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MessageListenerRegistrar.class);

    /** 全序：通道 → 组 → bean 名 → 方法名 → 方法签名（最后一级保证同名重载也有确定顺序）。 */
    private static final Comparator<RegisteredMessageListener> LISTENER_ORDER = Comparator
            .comparing(RegisteredMessageListener::channel)
            .thenComparing(RegisteredMessageListener::group)
            .thenComparing(RegisteredMessageListener::beanName)
            .thenComparing(listener -> listener.method().getName())
            .thenComparing(listener -> listener.method().toGenericString());

    private final ListableBeanFactory beanFactory;
    private volatile List<RegisteredMessageListener> listeners;

    /**
     * @param beanFactory 容器，不可为 {@code null}
     */
    public MessageListenerRegistrar(ListableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory 不能为 null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<RegisteredMessageListener> frozen = List.copyOf(collect());
        requireOneGroupPerChannel(frozen);
        this.listeners = frozen;
        log.info("MQ 监听器注册完成：共 {} 个方法，覆盖通道 {} 个", frozen.size(), channels().size());
    }

    /** 已注册监听方法数（诊断用：排查"消息没被消费"先看这个数）。 */
    public int listenerCount() {
        return snapshot().size();
    }

    /**
     * 冻结的监听器快照（{@link #LISTENER_ORDER} 升序，不可修改）。
     *
     * @throws IllegalStateException 上下文尚未刷新完成
     */
    public List<RegisteredMessageListener> snapshot() {
        List<RegisteredMessageListener> frozen = this.listeners;
        if (frozen == null) {
            throw new IllegalStateException("监听器快照尚未冻结：请在上下文刷新完成（afterSingletonsInstantiated）后再读取");
        }
        return frozen;
    }

    /**
     * 某通道的监听方法（{@link #LISTENER_ORDER} 顺序）。
     *
     * @param channel 通道名，不可为空白
     * @return 命中零条时返回空列表——"该不该报错"属于分发与订阅计划的职责，不在这里
     */
    public List<RegisteredMessageListener> listenersFor(String channel) {
        requireChannel(channel);
        return snapshot().stream().filter(listener -> listener.channel().equals(channel)).toList();
    }

    /** 被监听的通道（升序去重，不可修改）。 */
    public Set<String> channels() {
        return snapshot().stream().map(RegisteredMessageListener::channel)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * 某通道的消费组（同通道已校验只有一个组）。
     *
     * @return 空串表示"用适配器的缺省消费组"
     * @throws IllegalArgumentException 通道没有任何监听器
     */
    public String groupOf(String channel) {
        List<RegisteredMessageListener> targets = listenersFor(channel);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("通道 " + channel + " 没有任何 @MessageListener，"
                    + "已注册通道：" + channels());
        }
        return targets.get(0).group();
    }

    private static void requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel 不能为空白");
        }
    }

    private static void requireOneGroupPerChannel(List<RegisteredMessageListener> frozen) {
        Map<String, Set<String>> groupsPerChannel = new LinkedHashMap<>();
        for (RegisteredMessageListener listener : frozen) {
            groupsPerChannel.computeIfAbsent(listener.channel(), key -> new LinkedHashSet<>()).add(listener.group());
        }
        groupsPerChannel.forEach((channel, groups) -> {
            if (groups.size() > 1) {
                throw new FrameworkException("通道 " + channel + " 上的 @MessageListener 声明了两个不同的 group："
                        + groups + "。一个通道在一个进程里只能属于一个消费者组，请把它们改成同一个组"
                        + "（涉及的监听器：" + frozen.stream()
                            .filter(listener -> listener.channel().equals(channel))
                            .map(listener -> listener.id() + "→"
                                + (listener.group().isEmpty() ? "缺省组" : listener.group()))
                            .collect(Collectors.joining("、")) + "）");
            }
        });
    }

    private List<RegisteredMessageListener> collect() {
        List<RegisteredMessageListener> collected = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                continue;
            }
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null) {
                continue;
            }
            List<Method> annotated = annotatedMethodsOf(beanType);
            if (annotated.isEmpty()) {
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            for (Method method : annotated) {
                collected.add(create(beanName, bean, method));
            }
        }
        collected.sort(LISTENER_ORDER);
        return collected;
    }

    private static RegisteredMessageListener create(String beanName, Object bean, Method method) {
        MessageListener annotation =
                Objects.requireNonNull(AnnotationUtils.getAnnotation(method, MessageListener.class));
        validate(beanName, method, annotation);
        ReflectionUtils.makeAccessible(method);
        return new RegisteredMessageListener(beanName, annotation.channel(), annotation.group(),
                annotation.description(), bean, method);
    }

    private static void validate(String beanName, Method method, MessageListener annotation) {
        String target = beanName + "#" + method.getName();
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            throw invalid(target, "必须是实例方法（非 static）");
        }
        if (Modifier.isPrivate(modifiers)) {
            throw invalid(target, "不能是 private（装配层需要能调用它）");
        }
        if (method.getReturnType() != void.class) {
            throw invalid(target, "返回值必须是 void，实际 " + method.getReturnType().getName());
        }
        if (method.getParameterCount() != 1) {
            throw invalid(target, "必须恰好声明 1 个参数，实际 " + method.getParameterCount() + " 个");
        }
        if (annotation.channel().isBlank()) {
            throw invalid(target, "@MessageListener 的 channel 必填且不能为空白"
                    + "（订阅关系由它决定，见 myddd.mq.channels.<channel>.provider）");
        }
    }

    private static FrameworkException invalid(String target, String reason) {
        return new FrameworkException("非法的 @MessageListener 方法 " + target + "：" + reason);
    }

    /** 有注解的方法，按"最具体优先"去重（子类覆写与基类/接口声明同签名只注册一次）。 */
    private static List<Method> annotatedMethodsOf(Class<?> beanType) {
        Map<String, Method> selected = new LinkedHashMap<>();
        for (Method method : candidateMethodsOf(beanType)) {
            if (AnnotationUtils.getAnnotation(method, MessageListener.class) != null) {
                selected.putIfAbsent(signatureOf(method), method);
            }
        }
        return List.copyOf(selected.values());
    }

    /** 候选方法：本类 → 父类（最具体优先），再全部接口。 */
    private static List<Method> candidateMethodsOf(Class<?> beanType) {
        List<Method> candidates = new ArrayList<>();
        for (Class<?> current = beanType; current != null && current != Object.class;
                current = current.getSuperclass()) {
            Collections.addAll(candidates, ReflectionUtils.getDeclaredMethods(current));
        }
        for (Class<?> implemented : ClassUtils.getAllInterfacesForClass(beanType)) {
            Collections.addAll(candidates, ReflectionUtils.getDeclaredMethods(implemented));
        }
        return candidates;
    }

    private static String signatureOf(Method method) {
        StringBuilder signature = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameterType : method.getParameterTypes()) {
            signature.append(parameterType.getName()).append(',');
        }
        return signature.append(')').toString();
    }
}

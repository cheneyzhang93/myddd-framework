package org.deep361.myddd.domain.event;

import org.deep361.myddd.domain.context.DomainModuleRegistry;
import org.deep361.myddd.kernel.ddd.event.DomainEvent;
import org.deep361.myddd.kernel.ddd.event.DomainEventListener;
import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 监听器注册器：全部单例实例化之后（{@link SmartInitializingSingleton}）扫描上下文中的 bean，把
 * {@code @DomainEventListener} 方法冻结成一份有序快照供 {@link DomainEventDispatcher} 使用。
 *
 * <p><b>扫描范围</b>：类型可知的 bean（{@code getBeanNamesForType(Object.class, false, false)}），跳过
 * scope 代理的目标 bean（{@code scopedTarget.*}，避免与代理重复注册）。类型不可知的 bean（未实例化前无法
 * 判定类型的 FactoryBean 产物）不参与注册——这是接受的能力边界。
 *
 * <p><b>启动期失败</b>：方法签名非法（静态/private/返回值非 void/参数个数或类型不符/async 与事务内组合）、
 * 监听器 bean 不属于任何 {@code @Domain} 模块，一律抛异常让应用启动失败。
 *
 * <p><b>归属判定用目标类</b>（{@link AopUtils#getTargetClass(Object)}）：代理不改变 bean 的模块归属。
 *
 * <p><b>代价</b>：带监听器方法的 bean 会在启动期被取实例（{@code getBean}）——监听器 bean 不应声明为懒加载。
 */
public class DomainEventListenerRegistrar implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DomainEventListenerRegistrar.class);

    private static final Comparator<RegisteredListener> LISTENER_ORDER = Comparator
            .comparingInt((RegisteredListener listener) -> listener.stage().ordinal())
            .thenComparingInt(RegisteredListener::order)
            .thenComparing(RegisteredListener::beanName)
            .thenComparing(listener -> listener.method().getName())
            .thenComparing(listener -> listener.method().toGenericString());

    private final ListableBeanFactory beanFactory;
    private final DomainModuleRegistry moduleRegistry;
    private volatile List<RegisteredListener> listeners;

    public DomainEventListenerRegistrar(ListableBeanFactory beanFactory, DomainModuleRegistry moduleRegistry) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "BeanFactory 不能为 null");
        this.moduleRegistry = Objects.requireNonNull(moduleRegistry, "领域模块注册表不能为 null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<RegisteredListener> frozen = List.copyOf(collect());
        this.listeners = frozen;
        log.info("领域事件监听器注册完成：共 {} 个", frozen.size());
    }

    /** 已注册监听器数量（诊断用：排查"监听器没被触发"时先看这个数）。 */
    public int listenerCount() {
        return listeners().size();
    }

    /**
     * 冻结的监听器快照：按 阶段 → order → bean 名 → 方法名 → 方法签名 升序，不可修改。
     * 方法签名兜底保证"同名重载"也有确定顺序（全序，不依赖反射返回顺序）。
     *
     * @throws IllegalStateException 上下文尚未刷新完成（快照未冻结）
     */
    List<RegisteredListener> listeners() {
        List<RegisteredListener> frozen = this.listeners;
        if (frozen == null) {
            throw new IllegalStateException("监听器快照尚未冻结：请在上下文刷新完成（afterSingletonsInstantiated）后再读取");
        }
        return frozen;
    }

    private List<RegisteredListener> collect() {
        List<RegisteredListener> collected = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                continue;
            }
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null) {
                continue;
            }
            List<Method> listenerMethods = listenerMethodsOf(beanType);
            if (listenerMethods.isEmpty()) {
                continue;
            }
            Object bean = beanFactory.getBean(beanName);
            requireModule(beanName, AopUtils.getTargetClass(bean));
            for (Method listenerMethod : listenerMethods) {
                collected.add(createRegisteredListener(beanName, bean, listenerMethod));
            }
        }
        collected.sort(LISTENER_ORDER);
        return collected;
    }

    private void requireModule(String beanName, Class<?> targetClass) {
        if (moduleRegistry.moduleOf(targetClass).isEmpty()) {
            throw new IllegalStateException("监听器 bean " + beanName + "（类型 " + targetClass.getName()
                    + "）不属于任何 @Domain 领域模块（已注册模块=" + moduleRegistry.names()
                    + "）；请把监听器放进领域模块的扫描包内，或补充 myddd.domain.scan-packages 配置");
        }
    }

    private static RegisteredListener createRegisteredListener(String beanName, Object bean, Method method) {
        DomainEventListener annotation = AnnotationUtils.getAnnotation(method, DomainEventListener.class);
        DomainEventStage stage = annotation.stage();
        boolean async = annotation.async();
        validate(beanName, method, stage, async);
        return new RegisteredListener(stage, async, annotation.order(), beanName, bean, method);
    }

    private static void validate(String beanName, Method method, DomainEventStage stage, boolean async) {
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
        Class<?> parameterType = method.getParameterTypes()[0];
        if (!DomainEvent.class.isAssignableFrom(parameterType)) {
            throw invalid(target, "参数类型必须是 " + DomainEvent.class.getName() + " 的子类型，实际 " + parameterType.getName());
        }
        if (async && stage == DomainEventStage.IN_TRANSACTION) {
            throw invalid(target, "async=true 不能与 stage=IN_TRANSACTION 组合："
                    + "异步监听器不在事务线程内执行，无法参与事务回滚");
        }
    }

    private static IllegalStateException invalid(String target, String reason) {
        return new IllegalStateException("非法的 @" + DomainEventListener.class.getSimpleName() + " 方法 "
                + target + "：" + reason);
    }

    /** 有注解的方法，按"最具体优先"去重（子类覆写与基类/接口声明同签名只注册一次）。 */
    private static List<Method> listenerMethodsOf(Class<?> beanType) {
        Map<String, Method> selected = new LinkedHashMap<>();
        for (Method method : candidateMethodsOf(beanType)) {
            if (AnnotationUtils.getAnnotation(method, DomainEventListener.class) != null) {
                selected.putIfAbsent(signatureOf(method), method);
            }
        }
        return List.copyOf(selected.values());
    }

    /** 候选方法：本类 → 父类（最具体优先），再全部接口。 */
    private static List<Method> candidateMethodsOf(Class<?> beanType) {
        List<Method> candidates = new ArrayList<>();
        for (Class<?> current = beanType; current != null && current != Object.class; current = current.getSuperclass()) {
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

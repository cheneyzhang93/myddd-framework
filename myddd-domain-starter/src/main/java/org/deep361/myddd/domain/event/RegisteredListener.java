package org.deep361.myddd.domain.event;

import org.deep361.myddd.kernel.ddd.event.DomainEventStage;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 一个已冻结的监听器：投递元数据 + 目标 bean 实例 + 监听方法。
 *
 * <p>{@link #invoke(Object)} 抛出监听器方法自身抛出的异常（已解包 {@link InvocationTargetException}），
 * 由调用方按阶段决定"上抛回滚"还是"隔离记录"。
 */
record RegisteredListener(DomainEventStage stage, boolean async, int order, String beanName, Object bean, Method method) {

    RegisteredListener {
        Objects.requireNonNull(stage, "投递阶段不能为 null");
        Objects.requireNonNull(beanName, "bean 名不能为 null");
        Objects.requireNonNull(bean, "监听器 bean 不能为 null");
        Objects.requireNonNull(method, "监听器方法不能为 null");
    }

    /** 事件运行时类型能否传给本监听器（参数类型是事件类型本身或其父类型/接口）。 */
    boolean supports(Class<?> eventType) {
        return method.getParameterTypes()[0].isAssignableFrom(eventType);
    }

    /**
     * 调用监听方法。方法在目标类上解析（接口/基类上声明的注解解析到最具体的实现），
     * 不可访问时抛 {@link IllegalStateException}。
     */
    void invoke(Object event) throws Throwable {
        Method invocable = resolveInvocableMethod();
        ReflectionUtils.makeAccessible(invocable);
        try {
            invocable.invoke(bean, event);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("监听器方法不可访问：" + describe(), ex);
        }
    }

    /**
     * 解析出可在 bean 实例上直接调用的方法：目标类上的方法优先；JDK 动态代理只实现接口，目标类方法在代理
     * 实例上不可调用，此时按同一签名在 bean 运行时类型上重找（落到接口方法，由代理转发给目标）。
     */
    private Method resolveInvocableMethod() {
        Method invocable = AopUtils.selectInvocableMethod(method, AopUtils.getTargetClass(bean));
        if (invocable != null && invocable.getDeclaringClass().isInstance(bean)) {
            return invocable;
        }
        Method onRuntimeType = ReflectionUtils.findMethod(bean.getClass(), method.getName(), method.getParameterTypes());
        if (onRuntimeType != null) {
            return onRuntimeType;
        }
        return invocable != null ? invocable : method;
    }

    /** 诊断名：{@code beanName#methodName}。 */
    String describe() {
        return beanName + "#" + method.getName();
    }
}

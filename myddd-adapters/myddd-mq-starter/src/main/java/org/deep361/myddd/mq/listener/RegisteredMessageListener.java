package org.deep361.myddd.mq.listener;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.kernel.port.message.InboundMessage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 一条已通过签名校验的 {@code @MessageListener} 注册项。
 *
 * <p>{@code method} 的声明类可能是 bean 的父类或实现的接口（扫描按"本类 → 父类 → 接口"进行），
 * {@code bean} 是容器里的实例（可能是它的代理）——两者组合起来正好是 Spring 自身对 CGLIB 代理的调用方式，
 * 所以 {@code @Transactional}／审计切面照常生效。
 *
 * @param beanName    宿主 bean 名（排序键与诊断前缀）
 * @param channel     订阅通道
 * @param group       消费组；空串表示"用适配器的缺省组"
 * @param description 注解上的说明文字（仅诊断用）
 * @param bean        宿主实例
 * @param method      监听方法（已 {@code makeAccessible}）
 */
public record RegisteredMessageListener(String beanName, String channel, String group, String description,
                                        Object bean, Method method) {

    public RegisteredMessageListener {
        Objects.requireNonNull(beanName, "beanName 不能为 null");
        Objects.requireNonNull(channel, "channel 不能为 null");
        Objects.requireNonNull(group, "group 不能为 null");
        Objects.requireNonNull(description, "description 不能为 null");
        Objects.requireNonNull(bean, "bean 不能为 null");
        Objects.requireNonNull(method, "method 不能为 null");
    }

    /** {@code beanName#methodName}：诊断与重复判定的唯一键。 */
    public String id() {
        return beanName + "#" + method.getName();
    }

    /** 方法唯一参数的类型（扫描时已保证"恰好一个参数"）。 */
    public Class<?> parameterType() {
        return method.getParameterTypes()[0];
    }

    /** {@code true}＝参数是 {@link InboundMessage}（原样投递）；{@code false}＝参数是业务载荷（需 JSON 转换）。 */
    public boolean consumesInboundMessage() {
        return InboundMessage.class.isAssignableFrom(parameterType());
    }

    /**
     * 调用监听方法，并把反射层的不透明性拆掉：
     * <ul>
     *   <li>方法抛出的 {@code RuntimeException}/{@code Error} <b>原样传播</b>——适配器的重试策略要看到业务异常本身
     *       （内核 {@code @MessageListener} 文档："方法抛出的异常视为消费失败"）；</li>
     *   <li>方法抛出的受检异常包成 {@link FrameworkException}（端口只声明 runtime，厂商回调不接受受检异常）；</li>
     *   <li>反射本身失败（不可访问、参数不匹配）包成 {@link FrameworkException}，消息里带方法签名。</li>
     * </ul>
     *
     * @param argument 唯一实参，形态由 {@link #consumesInboundMessage()} 决定
     */
    public void invoke(Object argument) {
        try {
            method.invoke(bean, argument);
        } catch (InvocationTargetException ex) {
            rethrowTarget(ex.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException ex) {
            throw new FrameworkException("监听方法反射调用失败：" + id() + "，签名 " + method.toGenericString()
                    + "，实参 " + (argument == null ? "null" : argument.getClass().getName()), ex);
        }
    }

    private void rethrowTarget(Throwable target) {
        if (target instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (target instanceof Error error) {
            throw error;
        }
        throw new FrameworkException("监听方法抛出受检异常：" + id() + " → " + target.getClass().getName(), target);
    }
}

package org.deep361.myddd.mq.listener.fixture;

import org.deep361.myddd.mq.listener.MessageListenerRegistrar;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import java.beans.Introspector;

/**
 * 测试夹具（非用例）：真容器 + 冻结快照的起步点。
 *
 * <p>用 {@link GenericApplicationContext} 而不是假 beanFactory：{@code SmartInitializingSingleton} 回调、
 * scope 代理 bean 名、bean 类型推断这些"注册表一半的价值"只有真容器会给。启动期快速失败也在这里现形——
 * {@link #refresh} 直接把 {@code FrameworkException} 抛给用例，不吞、不包。
 *
 * <p>路由与 provider 不在这里出现：消费计划测的是"注册表 + 路由表"的组合，路由表用
 * {@code MessageRouteTable} 的构造器直接给（装配面的键绑定归 AC 组）。
 */
public final class MqContexts {

    private MqContexts() {
    }

    /** 只注册注册表的 bean 定义、<b>不</b>刷新：代理 bean／{@code scopedTarget.*} 这类要自己塞 bean 的用例从这里起步。 */
    public static GenericApplicationContext registrarOnly() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean("messageListenerRegistrar", MessageListenerRegistrar.class,
                () -> new MessageListenerRegistrar(context));
        return context;
    }

    /** 一个从未刷新的 beanFactory：LS15 的"快照尚未冻结"。 */
    public static DefaultListableBeanFactory unrefreshed() {
        return new DefaultListableBeanFactory();
    }

    /**
     * 注册注册表与给定业务 bean（bean 名＝类简单名首字母小写）后刷新。
     *
     * @return 快照已冻结的容器
     * @throws org.deep361.myddd.kernel.common.error.FrameworkException 签名非法或同通道两个组（启动期快速失败）
     */
    public static GenericApplicationContext refresh(Class<?>... beanClasses) {
        GenericApplicationContext context = registrarOnly();
        for (Class<?> beanClass : beanClasses) {
            register(context, beanClass);
        }
        context.refresh();
        return context;
    }

    /** 注册表本身（快照已冻结）。 */
    public static MessageListenerRegistrar registrarOf(GenericApplicationContext context) {
        return context.getBean(MessageListenerRegistrar.class);
    }

    private static <T> void register(GenericApplicationContext context, Class<T> beanClass) {
        String beanName = beanNameOf(beanClass);
        context.registerBean(beanName, beanClass, () -> newInstanceOf(beanName, beanClass));
    }

    private static <T> T newInstanceOf(String beanName, Class<T> beanClass) {
        try {
            return beanClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("夹具 bean " + beanName + "（" + beanClass.getName()
                    + "）需要一个无参构造器才能被注册成 bean", ex);
        }
    }

    /** bean 名＝类简单名首字母小写：排序断言（beanName 是排序键之一）因此有确定结果。 */
    private static String beanNameOf(Class<?> beanClass) {
        return Introspector.decapitalize(beanClass.getSimpleName());
    }
}

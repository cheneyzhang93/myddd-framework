package org.deep361.myddd.persistence.outbox;

import org.deep361.myddd.kernel.ddd.event.DomainEventPublisher;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 把容器里的每一个 {@link DomainEventPublisher} 换成 {@link AggregateTypeUnwrapper}。
 *
 * <p><b>为什么用包装型 BPP 而不是在装配类里 {@code @Bean}</b>：已交付的发布器 bean 属于
 * {@code MydddDomainEventAutoConfiguration}，它带 {@code @ConditionalOnMissingBean}。要在装配类里产出"装饰过的
 * 发布器"，就得在自己的 {@code @AutoConfiguration} 上声明 {@code after = MydddDomainEventAutoConfiguration.class}，
 * 并在 bean 方法参数里收那个 bean——这条路把"谁先装配"变成正确性前提，而排序提示在两方都无元数据时才按类名字母序
 * （Task 13 要点 2 处理的就是这条规则）。BPP 不参与自动配置排序：它在 bean 初始化的尾端生效，无论发布器 bean
 * 由框架产出还是由业务自己 {@code @Bean} 产出，都会被装饰一次，且只有一个。
 *
 * <p><b>判定的两面</b>：只按类型匹配 {@link DomainEventPublisher}，非发布器 bean 原样返回；已经是
 * {@link AggregateTypeUnwrapper} 的 bean 不再包一层（业务完全可能自己 {@code new} 一个装饰器注册进容器，
 * 双层装饰会让"取到的坐标是谁修正的"变成两层问题）。
 *
 * <p><b>已知后果（不是缺陷，写清楚）</b>：装饰之后，容器暴露的 singleton 是 {@link AggregateTypeUnwrapper}，
 * 按被装饰者的<b>具体类</b>查这个 bean 会得到 {@code NoSuchBeanDefinitionException}（实测消息
 * {@code No qualifying bean of type '…'} ）。这是所有包装型 BPP 的固有语义，Spring 自己的
 * {@code AnnotationAwareAspectJAutoProxyCreator} 同理。业务侧的唯一契约是<b>按接口注入</b>——已交付的 bean
 * 方法声明类型就是接口（{@code MydddDomainEventAutoConfiguration.java:87}），因此这条路一直是通的；G5 把反面
 * 钉成一条会红的用例，而不是留到生产环境里现形。
 */
public class AggregateTypeUnwrapperBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DomainEventPublisher publisher && !(publisher instanceof AggregateTypeUnwrapper)) {
            return new AggregateTypeUnwrapper(publisher);
        }
        return bean;
    }
}

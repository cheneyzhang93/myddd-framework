package org.deep361.myddd.persistence.routing;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明本次调用走哪个命名数据源（取值是 {@code myddd.persistence.datasources.<value>} 的 {@code <value>}）。
 *
 * <p><b>只能标在实现上</b>：可以标在实现类或实现方法上，方法级优先于类级。<b>只标在接口方法上不生效</b>
 * ——AspectJ 的 {@code @annotation}/{@code @within} 按目标实现类解析（实测，见
 * {@code DataSourceRoutingAspectTest#should_not_route_when_the_annotation_is_declared_only_on_the_interface_method}）。
 * 这与 {@code @Transactional} 允许接口注解的经验不同，别类推。
 *
 * <p><b>与事务的先后</b>：本注解必须在外层、{@code @Transactional} 在内层。连接在事务开启那一刻就绑定到
 * 具体数据源，事务中途换不了库；框架已把切面顺序钉在事务通知器之前（{@link DataSourceRoutingAspect#ASPECT_ORDER}），
 * 业务只要不自己给切面加 {@code @Order} 即可。同一条事务里的所有语句只能落在同一个库——需要跨库写就拆成
 * 两个事务（本脚手架不提供跨库事务，见 spec §5.2 的一致性边界）。
 *
 * <p>取值必须是 {@code [a-z][a-z0-9_]{0,63}}（{@link DataSourceKeyHolder#bind(String)} 在绑定时校验形状），
 * 且已在 {@code myddd.persistence.datasources} 里定义（{@link RoutingDataSource} 在取连接时校验归属）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface UseDataSource {

    /** 数据源键。 */
    String value();
}

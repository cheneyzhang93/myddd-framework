package org.deep361.myddd.persistence.routing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切面怎么找注解、怎么绑键、怎么复原。
 *
 * <p>这里手工织代理（{@link AspectJProxyFactory}）而不是起完整上下文：切面体只做"找注解 + bind + finally
 * restore"三件事。它与 {@code @Transactional} 的相对顺序不在本类测，那是
 * {@link RoutingDataSourceTransactionTest} 的职责（需要真的事务管理器与真的连接）。
 */
class DataSourceRoutingAspectTest {

    @UseDataSource("primary")
    static class ClassLevel {

        String report() {
            return DataSourceKeyHolder.current();
        }

        @UseDataSource("report")
        String overridden() {
            return DataSourceKeyHolder.current();
        }

        String nesting() {
            String inner = DataSourceKeyHolder.runWith("inner", DataSourceKeyHolder::current);
            return inner + "/" + DataSourceKeyHolder.current();
        }

        String boom() {
            throw new IllegalStateException("业务炸了");
        }
    }

    interface Reported {
        @UseDataSource("report")
        String report();
    }

    static class InterfaceOnly implements Reported {

        @Override
        public String report() {
            return DataSourceKeyHolder.current();
        }
    }

    @AfterEach
    void unbind() {
        DataSourceKeyHolder.restore(null);
    }

    private static <T> T proxy(T target, Class<T> ifc) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new DataSourceRoutingAspect());
        factory.addInterface(ifc);
        return factory.getProxy();
    }

    private static ClassLevel classLevelProxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new ClassLevel());
        factory.addAspect(new DataSourceRoutingAspect());
        factory.setProxyTargetClass(true);
        return factory.getProxy();
    }

    @Test
    void should_bind_the_class_level_annotation_for_methods_without_their_own() {
        assertThat(classLevelProxy().report()).isEqualTo("primary");
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_prefer_the_method_annotation_over_the_class_annotation() {
        assertThat(classLevelProxy().overridden()).isEqualTo("report");
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_restore_the_callers_key_after_the_advised_call_and_even_when_the_call_fails() {
        ClassLevel proxy = classLevelProxy();

        // "复原到调用方的键"必须在最外层作用域还活着的时候观察：runWith 返回时它自己也已经复原了。
        String seenInsideOuter = DataSourceKeyHolder.runWith("outer", () -> {
            String reported = proxy.report();
            return reported + "|" + DataSourceKeyHolder.current();
        });
        assertThat(seenInsideOuter).isEqualTo("primary|outer");
        assertThat(DataSourceKeyHolder.current()).isNull();

        assertThatThrownBy(proxy::boom).isInstanceOf(IllegalStateException.class).hasMessage("业务炸了");
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_keep_the_aspect_bound_key_when_the_method_body_nests_run_with() {
        assertThat(classLevelProxy().nesting()).isEqualTo("inner/primary");
        assertThat(DataSourceKeyHolder.current()).isNull();
    }

    @Test
    void should_not_route_when_the_annotation_is_declared_only_on_the_interface_method() {
        Reported proxy = proxy(new InterfaceOnly(), Reported.class);

        // 实测：AspectJ 的 @annotation/@within 按目标实现类解析，接口方法上的注解不会让这个调用被路由。
        // 这条用例钉的是"边界已知"，不是"这个行为理想"——业务必须把 @UseDataSource 标在实现上。
        assertThat(proxy.report()).isNull();

        AspectJProxyFactory classProxyFactory = new AspectJProxyFactory(new InterfaceOnly());
        classProxyFactory.addAspect(new DataSourceRoutingAspect());
        classProxyFactory.setProxyTargetClass(true);
        assertThat(((Reported) classProxyFactory.getProxy()).report()).isNull();
    }
}

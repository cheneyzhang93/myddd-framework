package org.deep361.myddd.persistence.autoconfigure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.mybatis.AuditMetaObjectHandler;
import org.deep361.myddd.persistence.mybatis.PrincipalProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计填充的装配语义：默认给什么、开关关掉少什么、业务自己声明时谁让位。
 *
 * <p>runner 只放入我们自己的装配类：审计的三方对手是 {@code MybatisPlusAutoConfiguration} 里的
 * {@code getBean(MetaObjectHandler.class)}，它需要 DataSource 才会被创建，放进 runner 只会把
 * "谁让位"这件事糊成上下文启动失败。MP 侧的真实接线由 Task 20 的 MySQL 集成用例负责。
 */
class MydddPersistenceAuditAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MydddPersistenceAutoConfiguration.class));

    @Test
    void should_assemble_meta_object_handler_and_fallback_principal() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AuditMetaObjectHandler.class);
            assertThat(context).hasSingleBean(PrincipalProvider.class);
            assertThat(context.getBean(PrincipalProvider.class).currentPrincipal()).isEmpty();
        });
    }

    @Test
    void should_back_off_both_audit_beans_when_switch_disabled() {
        this.runner.withPropertyValues("myddd.persistence.audit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditMetaObjectHandler.class);
            assertThat(context).doesNotHaveBean(PrincipalProvider.class);
        });
    }

    @Test
    void should_let_framework_handler_consume_business_principal_provider() {
        // withBean 注册的是单例：容器里 PrincipalProvider 只有一个候选，故它必是构造器收到的那个
        this.runner
                .withBean("businessPrincipalProvider", PrincipalProvider.class,
                        BusinessPrincipalProvider::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AuditMetaObjectHandler.class);
                    assertThat(context).hasSingleBean(PrincipalProvider.class);
                    assertThat(context.getBean(PrincipalProvider.class).currentPrincipal())
                            .contains("alice");
                });
    }

    @Test
    void should_back_off_when_application_declares_its_own_handler() {
        // 设计要点 2：两个 MetaObjectHandler 会让 MP 的 getBean(MetaObjectHandler.class) 在
        // SqlSessionFactory 创建期抛 NoUniqueBeanDefinitionException（或被 @Primary 静默绕过），
        // 所以冲突必须在源头消失——我们退出，而不是事后检查。
        this.runner.withBean("businessMetaObjectHandler", MetaObjectHandler.class,
                BusinessMetaObjectHandler::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AuditMetaObjectHandler.class);
            assertThat(context).hasSingleBean(MetaObjectHandler.class);
            assertThat(context.getBean(MetaObjectHandler.class)).isInstanceOf(BusinessMetaObjectHandler.class);
        });
    }

    @Test
    void should_keep_business_handler_when_audit_disabled() {
        // 与测试 4 对照：让位与开关是两条独立的路，关掉我们的开关不应该动业务的 bean 分毫
        this.runner.withPropertyValues("myddd.persistence.audit.enabled=false")
                .withBean("businessMetaObjectHandler", MetaObjectHandler.class, BusinessMetaObjectHandler::new)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MetaObjectHandler.class);
                    assertThat(context).doesNotHaveBean(PrincipalProvider.class);
                });
    }

    @Test
    void should_fail_startup_and_name_the_key_when_unknown_principal_is_blank() {
        this.runner.withPropertyValues("myddd.persistence.audit.unknown-principal=")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasRootCauseInstanceOf(FrameworkException.class)
                        .hasStackTraceContaining("myddd.persistence.audit.unknown-principal"));
    }

    /** 业务的接入形态：一个从自己的会话里取人的实现。 */
    static class BusinessPrincipalProvider implements PrincipalProvider {

        @Override
        public Optional<String> currentPrincipal() {
            return Optional.of("alice");
        }
    }

    /** 只满足"存在一个 MetaObjectHandler bean"，不做任何填充——用于验证框架让位。 */
    static class BusinessMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            // 故意留空：本用例断言的是 bean 归属，不是填充行为
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            // 同上
        }
    }
}

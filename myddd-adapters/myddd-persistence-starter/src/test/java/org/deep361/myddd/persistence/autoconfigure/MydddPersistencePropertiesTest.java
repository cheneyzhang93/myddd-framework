package org.deep361.myddd.persistence.autoconfigure;

import org.deep361.myddd.kernel.common.error.FrameworkException;
import org.deep361.myddd.persistence.ddl.DdlReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置键默认值与启动期校验。
 *
 * <p>只测"默认交付值是什么"与"哪种配法会让应用起不来"，不测绑定机制（枚举转换、松散绑定是三方的规矩）。
 */
class MydddPersistencePropertiesTest {

    @Test
    void should_default_to_every_interceptor_on_and_max_limit_one_thousand() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();

        assertThat(properties.isEnabled()).isTrue();
        MydddPersistenceProperties.Interceptor interceptor = properties.getInterceptor();
        assertThat(interceptor.getPagination().isEnabled()).isTrue();
        // 分页方言缺省留空，由 MyBatis-Plus 按当前连接自动识别（字节码实测 PaginationInnerInterceptor.findIDialect
        // 的 null 分支：DialectFactory.getDialect(JdbcUtils.getDbType(executor))，且 (DbType) 构造器对 null 无校验）。
        // 缺省写死 MYSQL 等于替用户宣布这套脚手架只支持一个库。
        assertThat(interceptor.getPagination().getDbType()).isNull();
        assertThat(interceptor.getPagination().getMaxLimit()).isEqualTo(1000L);
        assertThat(interceptor.getOptimisticLock().isEnabled()).isTrue();
        assertThat(interceptor.getBlockAttack().isEnabled()).isTrue();
        assertThat(properties.getAudit().isEnabled()).isTrue();
        assertThat(properties.getAudit().getUnknownPrincipal()).isEqualTo("system");
    }

    @Test
    void should_default_the_outbox_store_to_disabled_and_the_table_to_the_reference_ddl() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();

        // 默认 false 的理由在 MydddPersistenceProperties.Outbox 的 javadoc：参考 DDL 不自动注入迁移，
        // 默认打开会让"刚引依赖、还没建表"的应用直接起不来。
        assertThat(properties.getOutbox().isEnabled()).isFalse();
        assertThat(properties.getOutbox().getTable()).isEqualTo(DdlReference.OUTBOX_TABLE);
    }

    @Test
    void should_default_the_dead_letter_query_limit_to_two_hundred() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();

        // 一个键而不是两个：单页上限与单批重放上限管的是同一件事——"一口气别太多"。
        // 200 而不是内核的 500：死信列表是人在逐条看，不是程序在批量拉。
        assertThat(properties.getOutbox().getDeadLetter().getQueryLimit()).isEqualTo(200);
    }

    @Test
    void should_fail_startup_and_name_the_key_when_max_limit_is_negative() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getInterceptor().getPagination().setMaxLimit(-1L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.interceptor.pagination.max-limit")
                .hasMessageContaining("-1");
    }

    @Test
    void should_accept_zero_max_limit_as_unlimited() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getInterceptor().getPagination().setMaxLimit(0L);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_skip_max_limit_validation_when_pagination_off() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getInterceptor().getPagination().setEnabled(false);
        properties.getInterceptor().getPagination().setMaxLimit(-5L);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_skip_every_validation_when_master_switch_off() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.setEnabled(false);
        properties.getInterceptor().getPagination().setMaxLimit(-5L);

        // 关掉的不是"校验"，是整个装配：留着校验会让"引了依赖但不用"这种工程形态起不来
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void should_reject_blank_unknown_principal_when_audit_on() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getAudit().setUnknownPrincipal("   ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(FrameworkException.class)
                .hasMessageContaining("myddd.persistence.audit.unknown-principal");
    }

    @Test
    void should_skip_unknown_principal_check_when_audit_off() {
        MydddPersistenceProperties properties = new MydddPersistenceProperties();
        properties.getAudit().setEnabled(false);
        properties.getAudit().setUnknownPrincipal("");

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}

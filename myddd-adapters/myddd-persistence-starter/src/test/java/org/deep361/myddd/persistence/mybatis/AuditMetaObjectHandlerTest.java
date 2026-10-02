package org.deep361.myddd.persistence.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditMetaObjectHandlerTest {

    private static final Instant T0 = Instant.parse("2026-09-26T02:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-26T03:30:00Z");
    private static final Clock CLOCK_T0 = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Clock CLOCK_T1 = Clock.fixed(T1, ZoneOffset.UTC);

    @BeforeAll
    static void registerTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AuditOrderPO.class);
        TableInfoHelper.initTableInfo(assistant, PlainPO.class);
    }

    @Test
    void should_write_all_four_audit_fields_on_insert() {
        AuditOrderPO po = new AuditOrderPO();

        handler(CLOCK_T0, "alice", "system").insertFill(metaOf(po));

        assertThat(po.getCreateTime()).isEqualTo(T0);
        assertThat(po.getCreator()).isEqualTo("alice");
        assertThat(po.getUpdateTime()).isEqualTo(T0);
        assertThat(po.getUpdater()).isEqualTo("alice");
    }

    @Test
    void should_not_overwrite_audit_fields_set_by_business() {
        AuditOrderPO po = new AuditOrderPO();
        po.setCreateTime(T0);
        po.setCreator("数据迁移脚本");

        handler(CLOCK_T1, "alice", "system").insertFill(metaOf(po));

        assertThat(po.getCreateTime()).isEqualTo(T0);
        assertThat(po.getCreator()).isEqualTo("数据迁移脚本");
        assertThat(po.getUpdateTime()).isEqualTo(T1);
        assertThat(po.getUpdater()).isEqualTo("alice");
    }

    @Test
    void should_touch_only_update_time_and_updater_on_update() {
        AuditOrderPO po = new AuditOrderPO();
        po.setCreateTime(T0);
        po.setCreator("alice");
        po.setUpdateTime(T0);
        po.setUpdater("alice");

        handler(CLOCK_T1, "bob", "system").updateFill(metaOf(po));

        assertThat(po.getUpdateTime()).isEqualTo(T1);
        assertThat(po.getUpdater()).isEqualTo("bob");
        assertThat(po.getCreateTime()).isEqualTo(T0);
        assertThat(po.getCreator()).isEqualTo("alice");
    }

    @Test
    void should_fall_back_to_the_unknown_principal_instead_of_throwing() {
        AuditOrderPO po = new AuditOrderPO();

        handler(CLOCK_T0, null, "system").insertFill(metaOf(po));

        assertThat(po.getCreator()).isEqualTo("system");
        assertThat(po.getUpdater()).isEqualTo("system");
    }

    @Test
    void should_treat_blank_principal_as_unavailable() {
        AuditOrderPO po = new AuditOrderPO();

        handler(CLOCK_T0, "   ", "system").insertFill(metaOf(po));

        assertThat(po.getCreator()).isEqualTo("system");
    }

    @Test
    void should_skip_same_named_fields_without_fill_annotation() {
        // 设计点 3：决定"填哪个字段"的是 @TableField(fill=...) 声明，不是字段名。
        // PlainPO 自己声明了四个同名属性但一个 fill 都没标——审计必须一个都不碰。
        PlainPO po = new PlainPO();

        handler(CLOCK_T0, "alice", "system").insertFill(metaOf(po));
        handler(CLOCK_T1, "bob", "system").updateFill(metaOf(po));

        assertThat(po.getCreateTime()).isNull();
        assertThat(po.getCreator()).isNull();
        assertThat(po.getUpdateTime()).isNull();
        assertThat(po.getUpdater()).isNull();
    }

    @Test
    void should_read_the_clock_on_every_fill() {
        // 夹具自己 new 处理器：一次插入 + 一次更新共用同一个实例，才能钉住"每次填充现读时钟"
        // （变异 M7 把读取上提到构造期，若这里每次都重新 handler() 它就杀不掉）
        SettableClock clock = new SettableClock();
        AuditMetaObjectHandler filler = handler(clock, "alice", "system");
        AuditOrderPO po = new AuditOrderPO();

        filler.insertFill(metaOf(po));
        assertThat(po.getCreateTime()).isEqualTo(T0);

        clock.advanceTo(T1);
        filler.updateFill(metaOf(po));

        assertThat(po.getUpdateTime()).isEqualTo(T1);
        assertThat(po.getCreateTime()).isEqualTo(T0);
    }

    private static AuditMetaObjectHandler handler(Clock clock, String principal, String unknown) {
        return new AuditMetaObjectHandler(() -> Optional.ofNullable(principal), clock, unknown);
    }

    private static MetaObject metaOf(Object po) {
        return SystemMetaObject.forObject(po);
    }
    static class AuditOrderPO extends AbstractPO {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    static class PlainPO {

        private Instant createTime;
        private String creator;
        private Instant updateTime;
        private String updater;

        public Instant getCreateTime() {
            return createTime;
        }

        public void setCreateTime(Instant createTime) {
            this.createTime = createTime;
        }

        public String getCreator() {
            return creator;
        }

        public void setCreator(String creator) {
            this.creator = creator;
        }

        public Instant getUpdateTime() {
            return updateTime;
        }

        public void setUpdateTime(Instant updateTime) {
            this.updateTime = updateTime;
        }

        public String getUpdater() {
            return updater;
        }

        public void setUpdater(String updater) {
            this.updater = updater;
        }
    }

    static class SettableClock extends Clock {

        private Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        void advanceTo(Instant next) {
            this.now = next;
        }
    }
}

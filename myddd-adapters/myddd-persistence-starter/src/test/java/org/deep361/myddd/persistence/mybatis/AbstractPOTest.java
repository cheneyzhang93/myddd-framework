package org.deep361.myddd.persistence.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AbstractPO} 的约定本体：四个审计字段的名字、列名、填充时机与类型。
 *
 * <p>这些断言看起来像在"测注解"，但注解正是本类对 MP 的全部承诺：填充时机写错（{@code INSERT} 与
 * {@code INSERT_UPDATE} 互换）在运行期表现为"更新时 creator 被改写"或"插入行的 updater 为空"，
 * 而后者的责任要到排障时才追回来。类型必须是 {@code Instant}/{@code String}：MP 的 strict 填充按
 * {@code Class} 匹配，类型不符会静默不填（设计要点 3）。
 */
class AbstractPOTest {

    /** 业务侧最小的继承者：证明"继承即生效"，不需要重复标注解。 */
    static class AuditOrderPO extends AbstractPO {
        @TableId
        private Long id;
        private String orderNo;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public void setOrderNo(String orderNo) {
            this.orderNo = orderNo;
        }
    }

    private static Map<String, TableFieldInfo> fieldsOf(Class<?> poType) {
        TableInfo tableInfo = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), poType);
        return tableInfo.getFieldList().stream()
                .collect(Collectors.toMap(info -> info.getField().getName(), Function.identity()));
    }

    private static Field declaredField(String name) {
        try {
            return AbstractPO.class.getDeclaredField(name);
        } catch (NoSuchFieldException ex) {
            throw new AssertionError("AbstractPO 应当声明字段 " + name, ex);
        }
    }

    @Test
    void should_fill_the_four_audit_fields_at_different_times() {
        Map<String, TableFieldInfo> fields = fieldsOf(AuditOrderPO.class);

        // 实测 `getFieldList()` 只排除主键（所以标了 `@TableId` 的 `id` 不在其中）：夹具自己的
        // `orderNo` 加基类的四个审计列，正好五个。少一个就是基类的字段没被 MP 认下来。
        assertThat(fields).containsOnlyKeys("orderNo", "createTime", "updateTime", "creator", "updater");
        // 创建列与操作人只在插入时写一次，更新不得改写历史
        assertThat(fields.get("createTime").getFieldFill()).isEqualTo(FieldFill.INSERT);
        assertThat(fields.get("creator").getFieldFill()).isEqualTo(FieldFill.INSERT);
        // 更新列与更新人两种时机都要写
        assertThat(fields.get("updateTime").getFieldFill()).isEqualTo(FieldFill.INSERT_UPDATE);
        assertThat(fields.get("updater").getFieldFill()).isEqualTo(FieldFill.INSERT_UPDATE);
    }

    @Test
    void should_name_audit_columns_in_snake_case_with_their_types() {
        Map<String, TableFieldInfo> fields = fieldsOf(AuditOrderPO.class);

        assertThat(fields.get("createTime").getColumn()).isEqualTo("create_time");
        assertThat(fields.get("updateTime").getColumn()).isEqualTo("update_time");
        assertThat(declaredField("createTime").getType()).isEqualTo(Instant.class);
        assertThat(declaredField("updateTime").getType()).isEqualTo(Instant.class);
        assertThat(declaredField("creator").getType()).isEqualTo(String.class);
        assertThat(declaredField("updater").getType()).isEqualTo(String.class);
    }

    @Test
    void should_expose_fillable_metadata_only_to_subclasses() {
        TableInfo inherited = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditOrderPO.class);
        assertThat(inherited.isWithInsertFill()).isTrue();
        assertThat(inherited.isWithUpdateFill()).isTrue();

        TableInfo plain = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlainPO.class);
        // 设计要点 3：没标 fill 的实体一行都不写，这个断言就是那条边界的证明
        assertThat(plain.isWithInsertFill()).isFalse();
        assertThat(plain.isWithUpdateFill()).isFalse();
    }

    /** 不继承基类的对照实体。 */
    static class PlainPO {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}

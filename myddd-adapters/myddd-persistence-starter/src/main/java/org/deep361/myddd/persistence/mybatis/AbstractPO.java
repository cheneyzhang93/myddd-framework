package org.deep361.myddd.persistence.mybatis;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.Instant;

/**
 * 持久化对象基类：四个审计字段由框架自动填充，业务不再手写"取当前时间、取当前用户"。
 *
 * <p>填充时机写在注解上，取值由 {@link AuditMetaObjectHandler} 提供：
 * <ul>
 *   <li>{@code createTime / creator}：仅插入时写，更新永不改写（历史由它们承担）；</li>
 *   <li>{@code updateTime / updater}：插入与更新都写（插入时与创建列同值，便于"最近一次操作"单列查询）。</li>
 * </ul>
 *
 * <p><b>不继承也能用</b>：MP 只填标了 {@link FieldFill} 的字段，业务实体若对审计有别的语义（例如
 * {@code LocalDateTime} 或列名不同），不继承本类即可，框架不会去动它（设计要点 3）。
 *
 * <p><b>版本与逻辑删除不在本基类</b>：{@code @Version} 与 {@code @TableLogic} 是逐实体决策（并非所有表都有
 * 乐观锁需求），放进基类等于替全仓做主。
 */
public abstract class AbstractPO {

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Instant createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Instant updateTime;

    @TableField(value = "creator", fill = FieldFill.INSERT)
    private String creator;

    @TableField(value = "updater", fill = FieldFill.INSERT_UPDATE)
    private String updater;

    public Instant getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Instant createTime) {
        this.createTime = createTime;
    }

    public Instant getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Instant updateTime) {
        this.updateTime = updateTime;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getUpdater() {
        return updater;
    }

    public void setUpdater(String updater) {
        this.updater = updater;
    }
}

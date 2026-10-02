package org.deep361.myddd.persistence.it;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.deep361.myddd.persistence.mybatis.AbstractPO;

/**
 * 审计接线的夹具实体（只服务 Task 20 的真库验收，不进交付物）。
 *
 * <p>继承 {@link AbstractPO} 即拿到四个审计列，本类只加主键与业务列：这正是要在真实
 * MyBatis-Plus 上验的"继承即生效"。
 */
@TableName("it_audit_probe")
public class AuditProbePO extends AbstractPO {

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
